// Identity keys and sealed envelopes. The seal is the Noise one-way pattern X, byte for byte:
// Noise_X_25519_ChaChaPoly_SHA256 (noiseprotocol.org, revision 34), checked against a published
// test vector. The sender's key is hidden from carriers and authenticated to the recipient.
// Unaudited; no forward secrecy for stored envelopes (a property of X, not of this code).
import { generateKeyPairSync, diffieHellman, createPublicKey, createPrivateKey, hkdfSync,
  createCipheriv, createDecipheriv, createHash, createHmac, sign as edSign, verify as edVerify } from 'node:crypto';

const SPKI = Buffer.from('302a300506032b656e032100', 'hex');
const PKCS8 = Buffer.from('302e020100300506032b656e04220420', 'hex');
const NONCE = Buffer.alloc(12); // Noise nonce n = 0: pattern X uses each key for exactly one encryption
const NOISE_NAME = Buffer.from('Noise_X_25519_ChaChaPoly_SHA256\0'); // 31 bytes, zero-padded to the hash length
export const PROLOGUE = Buffer.from('kairo-mesh-1'); // binds envelopes to this protocol version
export const DAY_MS = 86400000;

export const sha256 = (...parts) => parts.reduce((h, p) => h.update(p), createHash('sha256')).digest();

export function createIdentity(secret) {
  const privateKey = secret
    ? createPrivateKey({ key: Buffer.concat([PKCS8, secret]), format: 'der', type: 'pkcs8' })
    : generateKeyPairSync('x25519').privateKey;
  return {
    privateKey,
    pub: createPublicKey(privateKey).export({ format: 'der', type: 'spki' }).subarray(-32),
    secret: privateKey.export({ format: 'der', type: 'pkcs8' }).subarray(-32),
  };
}

// The authority's signing key (Ed25519), separate from its sealing key: announcements it broadcasts to the public
// are signed with it, and phones ship with the public half.
const ED_SPKI = Buffer.from('302a300506032b6570032100', 'hex');
const ED_PKCS8 = Buffer.from('302e020100300506032b657004220420', 'hex');
export function createSigner(secret) {
  const privateKey = secret
    ? createPrivateKey({ key: Buffer.concat([ED_PKCS8, secret]), format: 'der', type: 'pkcs8' })
    : generateKeyPairSync('ed25519').privateKey;
  return {
    pub: createPublicKey(privateKey).export({ format: 'der', type: 'spki' }).subarray(-32),
    secret: privateKey.export({ format: 'der', type: 'pkcs8' }).subarray(-32),
    sign: (msg) => edSign(null, msg, privateKey),
  };
}
export function verifySignature(pub, msg, sig) {
  if (pub?.length !== 32 || sig?.length !== 64) return false;
  try { return edVerify(null, msg, createPublicKey({ key: Buffer.concat([ED_SPKI, pub]), format: 'der', type: 'spki' }), sig); }
  catch { return false; }
}

// Proof of work on emergency envelopes: sha256(sealed || nonce) must start with POW_BITS zero bits. About 2^18
// hashes, under a second on a phone: nothing for one real call, a real cost for someone forging thousands.
export const POW_BITS = 18;
const zeros = (h) => { let n = 0; for (const b of h) { if (b === 0) { n += 8; continue; } n += Math.clz32(b) - 24; break; } return n; };
export function addWork(sealed, bits = POW_BITS) {
  const nonce = Buffer.alloc(8);
  for (let i = 0; ; i++) {
    nonce.writeBigUInt64BE(BigInt(i));
    if (zeros(sha256(sealed, nonce)) >= bits) return Buffer.concat([sealed, nonce]);
  }
}
export const hasWork = (env, bits = POW_BITS) => env.length > 8 && zeros(sha256(env.subarray(0, -8), env.subarray(-8))) >= bits;

const dh = (privateKey, pub) =>
  diffieHellman({ privateKey, publicKey: createPublicKey({ key: Buffer.concat([SPKI, pub]), format: 'der', type: 'spki' }) });

function aead(key, plaintext, aad = Buffer.alloc(0)) {
  const c = createCipheriv('chacha20-poly1305', key, NONCE, { authTagLength: 16 });
  c.setAAD(aad);
  return Buffer.concat([c.update(plaintext), c.final(), c.getAuthTag()]);
}
function unaead(key, box, aad = Buffer.alloc(0)) {
  const d = createDecipheriv('chacha20-poly1305', key, NONCE, { authTagLength: 16 });
  d.setAAD(aad);
  d.setAuthTag(box.subarray(-16));
  return Buffer.concat([d.update(box.subarray(0, -16)), d.final()]);
}

// Padded to a power-of-two size so carriers cannot read the message length.
function pad(body) {
  let size = 256;
  while (size < body.length + 4) size *= 2;
  const out = Buffer.alloc(size);
  out.writeUInt32BE(body.length, 0);
  body.copy(out, 4);
  return out;
}

// Noise SymmetricState after the pre-message `<- s`: h and ck start as the protocol name,
// then h absorbs the prologue and the recipient's static key.
function noise(prologue, recipientPub) {
  let h = NOISE_NAME, ck = NOISE_NAME, k;
  const mixHash = (data) => { h = sha256(h, data); };
  const mixKey = (ikm) => { // Noise HKDF with two outputs = RFC 5869 with salt ck and empty info
    const out = Buffer.from(hkdfSync('sha256', ikm, ck, Buffer.alloc(0), 64));
    ck = out.subarray(0, 32); k = out.subarray(32);
  };
  mixHash(prologue); mixHash(recipientPub);
  return {
    mixHash, mixKey,
    encryptAndHash: (plain) => { const c = aead(k, plain, h); mixHash(c); return c; },
    decryptAndHash: (box) => { const p = unaead(k, box, h); mixHash(box); return p; },
  };
}

// The Noise X message `-> e, es, s, ss` followed by the payload. `eph` is fixed only by tests.
export function noiseXWrite(sender, recipientPub, payload, prologue = PROLOGUE, eph = createIdentity()) {
  const st = noise(prologue, recipientPub);
  st.mixHash(eph.pub);
  st.mixKey(dh(eph.privateKey, recipientPub));
  const who = st.encryptAndHash(sender.pub);
  st.mixKey(dh(sender.privateKey, recipientPub));
  return Buffer.concat([eph.pub, who, st.encryptAndHash(payload)]);
}

export function noiseXRead(recipient, message, prologue = PROLOGUE) {
  if (message.length < 32 + 48 + 16) throw new Error('short envelope');
  const ephPub = message.subarray(0, 32);
  const st = noise(prologue, recipient.pub);
  st.mixHash(ephPub);
  st.mixKey(dh(recipient.privateKey, ephPub));
  const senderPub = st.decryptAndHash(message.subarray(32, 80));
  st.mixKey(dh(recipient.privateKey, senderPub));
  return { senderPub, payload: st.decryptAndHash(message.subarray(80)) };
}

export const seal = (sender, recipientPub, body) => noiseXWrite(sender, recipientPub, pad(body));

// Returns { senderPub, body }, or throws if the envelope is not for us or was altered.
export function open(recipient, envelope) {
  if (envelope.length < 32 + 48 + 16 + 256) throw new Error('short envelope');
  const { senderPub, payload: padded } = noiseXRead(recipient, envelope);
  const len = padded.readUInt32BE(0);
  if (len > padded.length - 4) throw new Error('bad padding');
  return { senderPub, body: padded.subarray(4, 4 + len) };
}

// What carriers route on: changes daily, computable only by someone who knows the recipient's key.
export const recipientTag = (pub, timeMs) =>
  createHmac('sha256', sha256(pub)).update(String(Math.floor(timeMs / DAY_MS))).digest().subarray(0, 16);
export const recognitionKey = (pub) => sha256('kairo-rk', pub);
