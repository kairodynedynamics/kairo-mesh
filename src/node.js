// A Kairo node: sealed messages travel through nearby devices as couriers until one meets the recipient.
// Transport-agnostic: give it `send(frame)` (broadcast to whoever is in range) and feed it `receive(frame)`.
//
// Frames (first byte is the type):
//   HELLO  eid, [msgId, tag]...           what I carry
//   SCORE  from, to, msgId, q             "I lack that one"; q = how often I meet its recipient (0-3), 4 = it is mine
//   DATA   from, to, msgId, tag, receiptHash, tokens, k, seed, symbol   one fountain packet of the envelope
//   ACK    from, to, msgId, receipt       hand-over confirmed; a valid receipt proves delivery and purges copies
import { randomBytes, randomInt } from 'node:crypto';
import { encoder, decoder } from './fountain.js';
import { ephemeralId, recognise, EPOCH_MS } from './ids.js';
import { seal, open, sha256, recipientTag, recognitionKey, DAY_MS } from './crypto.js';

const HELLO = 1, SCORE = 2, DATA = 3, ACK = 4, MINE = 4;
const SYM = 400, MAX_K = 164;            // envelopes up to ~64 KiB
const MAX_STORE = 40, MAX_PARTIAL = 16, MAX_TOKENS = 8, HELLO_SLOTS = 18;
const TTL_MS = DAY_MS, PARTIAL_MS = 60000, HALF_LIFE_MS = 7200000, REENCOUNTER_MS = 300000;
const hex = (b) => b.toString('hex');

export function createNode({ identity, contacts = new Map(), send, onMessage = () => {}, now = Date.now, L = 4 }) {
  const store = new Map();   // msgId -> { id, tag, rh, env, tokens, at }
  const partial = new Map(); // msgId -> { dec, at }
  const pending = new Map(); // msgId+peer -> copies promised (0 = peer says it is the recipient)
  const seen = new Map();    // msgId -> receipt, for messages already delivered to me
  const score = new Map();   // contact -> { p, t }: how reliably I meet them
  const recognition = new Map();
  let cursor = 0;

  const myRk = recognitionKey(identity.pub);
  const eid = () => ephemeralId(myRk, now());
  const isMe = (id) => id.equals(eid()) || id.equals(ephemeralId(myRk, now() - EPOCH_MS));
  const tagsOf = (pub) => [recipientTag(pub, now()), recipientTag(pub, now() - DAY_MS)];
  const isMine = (tag) => tagsOf(identity.pub).some((t) => t.equals(tag));

  function addContact(name, pub) {
    if (pub?.length !== 32) throw new RangeError('contact key must be 32 bytes');
    contacts.set(name, pub);
    recognition.set(name, recognitionKey(pub));
  }
  for (const [name, pub] of [...contacts]) addContact(name, pub);

  const decayed = (s) => s.p * 0.5 ** ((now() - s.t) / HALF_LIFE_MS);
  function met(name) {
    const s = score.get(name);
    if (s && now() - s.t < REENCOUNTER_MS) return;
    const p = s ? decayed(s) : 0;
    score.set(name, { p: p + (1 - p) * 0.75, t: now() });
  }
  function quality(tag) {
    for (const [name, pub] of contacts)
      if (tagsOf(pub).some((t) => t.equals(tag))) { const s = score.get(name); return s ? Math.round(decayed(s) * 3) : 0; }
    return 0;
  }

  const ack = (to, id, receipt = Buffer.alloc(16)) => send(Buffer.concat([Buffer.from([ACK]), eid(), to, id, receipt]));

  function onHello(b) {
    const from = b.subarray(1, 9), n = b[9];
    if (b.length !== 10 + n * 24 || isMe(from)) return;
    const who = recognise(from, recognition, now());
    if (who) met(who);
    for (let i = 0; i < n; i++) {
      const id = b.subarray(10 + i * 24, 18 + i * 24), tag = b.subarray(18 + i * 24, 34 + i * 24), key = hex(id);
      if (store.has(key)) continue;
      if (seen.has(key)) { ack(from, id, seen.get(key)); continue; }
      const mine = isMine(tag);
      if (!mine && store.size >= MAX_STORE) continue;
      send(Buffer.concat([Buffer.from([SCORE]), eid(), from, id, Buffer.from([mine ? MINE : quality(tag)])]));
    }
  }

  function onScore(b) {
    if (b.length !== 26 || !isMe(b.subarray(9, 17))) return;
    const from = b.subarray(1, 9), id = b.subarray(17, 25), q = b[25], m = store.get(hex(id));
    if (!m || q > MINE) return;
    let give = 0;
    if (q !== MINE) {
      const mq = quality(m.tag);
      // Split copies toward whoever meets the recipient more; the last copy only moves to a clearly better carrier.
      if (m.tokens > 1) give = q + mq > 0 ? Math.min(m.tokens - 1, Math.round((m.tokens * q) / (q + mq))) : m.tokens >> 1;
      else give = q > mq ? 1 : 0;
      if (give < 1) return;
    }
    const key = hex(id) + hex(from), retry = pending.has(key);
    pending.set(key, give);
    const enc = encoder(m.env, SYM);
    const head = Buffer.concat([Buffer.from([DATA]), eid(), from, id, m.tag, m.rh, Buffer.from([give, enc.k >> 8, enc.k & 255])]);
    for (let i = 0; i < enc.k + 2; i++) {
      // First attempt sends the plain fragments; retries send fresh combinations so nothing is a duplicate.
      const p = enc.packet(!retry && i < enc.k ? i : enc.k + randomInt(2 ** 32 - MAX_K - 1));
      const seed = Buffer.alloc(4);
      seed.writeUInt32BE(p.seed);
      send(Buffer.concat([head, seed, p.data]));
    }
  }

  function onData(b) {
    if (b.length !== 64 + SYM || !isMe(b.subarray(9, 17))) return;
    const from = b.subarray(1, 9), id = b.subarray(17, 25), key = hex(id);
    const tag = Buffer.from(b.subarray(25, 41)), rh = Buffer.from(b.subarray(41, 57));
    const tokens = b[57], k = b.readUInt16BE(58);
    if (store.has(key)) return ack(from, id);
    if (seen.has(key)) return ack(from, id, seen.get(key));
    const mine = isMine(tag);
    let p = partial.get(key);
    if (!p) {
      if (k < 1 || k > MAX_K || partial.size >= MAX_PARTIAL || (!mine && store.size >= MAX_STORE)) return;
      partial.set(key, (p = { dec: decoder(k, SYM), at: now() }));
    }
    p.dec.add({ seed: b.readUInt32BE(60), data: b.subarray(64) });
    if (!p.dec.done) return;
    partial.delete(key);
    let env;
    try { env = p.dec.result(); } catch { return; }
    if (!sha256(env, rh).subarray(0, 8).equals(id)) return; // corrupted or forged packets
    if (!mine) {
      store.set(key, { id: Buffer.from(id), tag, rh, env, tokens: Math.min(Math.max(tokens, 1), MAX_TOKENS), at: now() });
      return ack(from, id);
    }
    let msg;
    try { msg = open(identity, env); } catch { return; }
    if (msg.body.length < 16) return;
    const receipt = Buffer.from(msg.body.subarray(0, 16));
    if (seen.size >= 1000) seen.delete(seen.keys().next().value);
    seen.set(key, receipt);
    ack(from, id, receipt);
    const sender = [...contacts].find(([, pub]) => pub.equals(msg.senderPub));
    onMessage(sender ? sender[0] : `unknown:${hex(msg.senderPub).slice(0, 8)}`, msg.body.subarray(16).toString('utf8'));
  }

  function onAck(b) {
    if (b.length !== 41) return;
    const from = b.subarray(1, 9), id = b.subarray(17, 25), key = hex(id), m = store.get(key);
    if (!m) return;
    // Anyone may relay a receipt: only the recipient could have produced it.
    if (sha256(b.subarray(25, 41)).subarray(0, 16).equals(m.rh)) return void store.delete(key);
    if (!isMe(b.subarray(9, 17))) return;
    const give = pending.get(key + hex(from));
    pending.delete(key + hex(from));
    if (!give) return; // unknown hand-over, or a claimed recipient that sent no receipt: keep the copy
    if (m.tokens > 1) m.tokens -= Math.min(give, m.tokens - 1); else store.delete(key);
  }

  return {
    pub: identity.pub,
    addContact,
    get carrying() { return store.size; },
    sendMessage(name, text) {
      const pub = contacts.get(name);
      if (!pub) throw new Error(`unknown contact: ${name}`);
      const receipt = randomBytes(16), rh = sha256(receipt).subarray(0, 16);
      const env = seal(identity, pub, Buffer.concat([receipt, Buffer.from(text, 'utf8')]));
      if (Math.ceil((env.length + 4) / SYM) > MAX_K) throw new RangeError('message too large');
      const id = sha256(env, rh).subarray(0, 8);
      store.set(hex(id), { id, tag: recipientTag(pub, now()), rh, env, tokens: L, at: now() });
    },
    receive(frame) {
      try {
        if (!Buffer.isBuffer(frame) || frame.length < 10) return;
        ({ [HELLO]: onHello, [SCORE]: onScore, [DATA]: onData, [ACK]: onAck })[frame[0]]?.(frame);
      } catch { /* malformed frame from an untrusted radio: drop it */ }
    },
    // Call every few seconds: expires old state and announces what we carry.
    tick() {
      for (const [k, m] of store) if (now() - m.at > TTL_MS) store.delete(k);
      for (const [k, p] of partial) if (now() - p.at > PARTIAL_MS) partial.delete(k);
      if (pending.size > 1000) pending.clear();
      const all = [...store.values()], n = Math.min(all.length, HELLO_SLOTS);
      const parts = [Buffer.from([HELLO]), eid(), Buffer.from([n])];
      for (let i = 0; i < n; i++) { const m = all[(cursor + i) % all.length]; parts.push(m.id, m.tag); }
      cursor += n;
      send(Buffer.concat(parts));
    },
  };
}
