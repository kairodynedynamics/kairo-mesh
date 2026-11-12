// Identity keys and sealed envelopes (X25519 + HKDF-SHA256 + ChaCha20-Poly1305).
// The seal follows the shape of Noise X: the sender's key is hidden from carriers and
// authenticated to the recipient. Unaudited; no forward secrecy for stored envelopes.
import { generateKeyPairSync, diffieHellman, createPublicKey, createPrivateKey, hkdfSync,
  createCipheriv, createDecipheriv, createHash, createHmac } from 'node:crypto';

const SPKI = Buffer.from('302a300506032b656e032100', 'hex');
const PKCS8 = Buffer.from('302e020100300506032b656e04220420', 'hex');
const NONCE = Buffer.alloc(12); // every key below is derived from a fresh ephemeral, so it is used once
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

const dh = (privateKey, pub) =>
  diffieHellman({ privateKey, publicKey: createPublicKey({ key: Buffer.concat([SPKI, pub]), format: 'der', type: 'spki' }) });
const kdf = (secret, salt, info) => Buffer.from(hkdfSync('sha256', secret, salt, info, 32));

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

export function seal(sender, recipientPub, body) {
  const eph = createIdentity();
  const salt = Buffer.concat([eph.pub, recipientPub]);
  const es = dh(eph.privateKey, recipientPub);
  const who = aead(kdf(es, salt, 'kairo-seal-1'), sender.pub);
  const ss = dh(sender.privateKey, recipientPub);
  return Buffer.concat([eph.pub, who, aead(kdf(Buffer.concat([es, ss]), salt, 'kairo-seal-2'), pad(body), who)]);
}

// Returns { senderPub, body }, or throws if the envelope is not for us or was altered.
export function open(recipient, envelope) {
  if (envelope.length < 32 + 48 + 16 + 256) throw new Error('short envelope');
  const ephPub = envelope.subarray(0, 32), who = envelope.subarray(32, 80);
  const salt = Buffer.concat([ephPub, recipient.pub]);
  const es = dh(recipient.privateKey, ephPub);
  const senderPub = unaead(kdf(es, salt, 'kairo-seal-1'), who);
  const ss = dh(recipient.privateKey, senderPub);
  const padded = unaead(kdf(Buffer.concat([es, ss]), salt, 'kairo-seal-2'), envelope.subarray(80), who);
  const len = padded.readUInt32BE(0);
  if (len > padded.length - 4) throw new Error('bad padding');
  return { senderPub, body: padded.subarray(4, 4 + len) };
}

// What carriers route on: changes daily, computable only by someone who knows the recipient's key.
export const recipientTag = (pub, timeMs) =>
  createHmac('sha256', sha256(pub)).update(String(Math.floor(timeMs / DAY_MS))).digest().subarray(0, 16);
export const recognitionKey = (pub) => sha256('kairo-rk', pub);
