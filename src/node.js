// A Kairo node: sealed messages travel through nearby devices as couriers until one meets the recipient.
// Transport-agnostic: give it `send(frame)` (broadcast to whoever is in range) and feed it `receive(frame)`.
//
// Frames (first byte is the type):
//   HELLO  eid, [handle, blindTag]...      what I carry, blinded with my current eid so the list cannot be linked across epochs
//   SCORE  from, to, handle, q, proof      "I lack that one"; q = how often I meet its recipient (0-3), 4 = it is mine;
//                                          proof shows I hold the recipient's key, i.e. I am one of their contacts
//   DATA   from, to, msgId, tag, receiptHash, give, k, seed, symbol   one fountain packet of the envelope
//   ACK    from, to, msgId, proof          proof of holding the envelope (hand-over) or the recipient's receipt (delivery)
import { randomBytes, randomInt } from 'node:crypto';
import { encoder, decoder } from './fountain.js';
import { ephemeralId, recognise, EPOCH_MS } from './ids.js';
//   NOTICE body, signature                 an announcement from the authority (assembly area, water point, unsafe
//                                          building), Ed25519-signed; phones verify it and pass it on
import { seal, open, sha256, recipientTag, recognitionKey, DAY_MS, addWork, hasWork, verifySignature } from './crypto.js';

const HELLO = 1, SCORE = 2, DATA = 3, ACK = 4, NOTICE = 5, MINE = 4;
const ACIL_STORE = 24, MAX_NOTICES = 32, NOTICE_TEXT = 600, NOTICE_EVERY_MS = 60000, NOTICES_PER_TICK = 2;
const SYM = 400, MAX_K = 164;            // envelopes up to ~64 KiB
const MAX_STORE = 40, MAX_PARTIAL = 16, MAX_TOKENS = 8, HELLO_SLOTS = 24, MAX_SEEN = 1000;
// Strangers may use at most half of each table, so they cannot crowd out mail from people we know.
const STRANGER_STORE = MAX_STORE / 2, STRANGER_PARTIAL = MAX_PARTIAL / 2;
// Per-tick transmit budgets: one forged frame must not make us send hundreds.
const SCORE_BUDGET = 48, DATA_BUDGET = 400;
const TTL_MS = DAY_MS, SKEW_MS = 3600000, PARTIAL_MS = 60000, HALF_LIFE_MS = 7200000, REENCOUNTER_MS = 300000;
const hex = (b) => b.toString('hex');
const blind = (value, eid) => sha256(value, eid).subarray(0, 8);
const proofOf = (env, eid) => sha256(env, 'ack', eid).subarray(0, 16);

// Emergency mail (sendEmergency) is sealed to the authority's public key (112 or a partner), which every app ships with.
// Nobody on the way can read it. Any phone that has internet becomes a gateway without its owner doing anything: it
// takes the envelope, uploads it through `uplink`, and the authority's server answers with the sealed receipt. That
// receipt then travels back through the mesh like any delivery receipt, so the sender learns the authority has it
// and every courier drops its copy. Couriers are scored by how often they have had internet.
const KURUM = '#kurum', ACIL_TOKENS = 8;

export function createNode({ identity, contacts = new Map(), send, onMessage = () => {}, now = Date.now, L = 4, delivered = [],
  authority = null, online = () => false, uplink = null, onEmergencyDelivered = () => {},
  authoritySign = null, downlink = null, onAnnouncement = () => {}, ayar = {} }) {
  // `ayar`: knobs for the security simulations (sim/guvenlik.js), defaults are what the phones run:
  //   acilKota: others' emergency mail carried at once; maxStore: the whole store;
  //   tahliye: which emergency makes room when the quota is full ('eski' oldest | 'rastgele' random);
  //   kaynakSiniri: others' emergencies accepted from one sending id per hour (default 3);
  //   sahteKimlik: an attacker's modified app that claims a new sending id in every frame;
  //   rastgele: random source for 'rastgele'.
  const ACIL = ayar.acilKota ?? ACIL_STORE, MAXS = ayar.maxStore ?? MAX_STORE;
  const tahliye = ayar.tahliye ?? 'eski', kaynakSiniri = ayar.kaynakSiniri ?? 3, rastgele = ayar.rastgele ?? Math.random;
  const kaynaklar = new Map(); // sending id -> times its emergencies were accepted (kaynakSiniri)
  const notices = new Map(); // id -> { frame, expires, sent }
  let lastDownlink = -Infinity;
  const store = new Map();   // msgId -> { id, tag, rh, env, tokens, at, own, trusted }
  const partial = new Map(); // msgId -> { dec, at, trusted }
  const pending = new Map(); // msgId+peer -> copies promised (0 = peer says it is the recipient)
  // msgId -> receipt, for messages already delivered to me; persist `delivered` so a restart does not reopen replays
  const seen = new Map(delivered.map(([key, receipt]) => [key, Buffer.from(receipt, 'hex')]));
  // msgId -> receipt for mail I carried and saw delivered: passing it on stops the sender and other couriers carrying it.
  // Kept apart from `seen` so a flood of genuine receipts cannot push my own deliveries out and reopen replays.
  const cleared = new Map();
  const score = new Map();   // contact -> { p, t }: how reliably I meet them
  const recognition = new Map();
  const burst = new Set();   // msgId+peer already served this tick
  let cursor = 0, scoreBudget = SCORE_BUDGET, dataBudget = DATA_BUDGET;
  // false on low battery: the phone still sends and receives its own mail but takes on nobody else's
  let relay = true;
  // peer id -> delivered handles blinded for that peer; rebuilding it costs a hash per entry on every HELLO
  const doneCache = new Map();

  const myRk = recognitionKey(identity.pub);
  const eid = ayar.sahteKimlik ? () => randomBytes(8) : () => ephemeralId(myRk, now());
  const isMe = (id) => id.equals(eid()) || id.equals(ephemeralId(myRk, now() - EPOCH_MS));
  const tagsOf = (pub) => [recipientTag(pub, now()), recipientTag(pub, now() - DAY_MS)];
  const known = (peer) => recognise(peer, recognition, now()) !== null;

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
  const isKurumTag = (tag) => authority !== null && tagsOf(authority).some((t) => t.equals(tag));
  const gateway = () => authority !== null && uplink !== null && online();

  // The contact whose tag `matches` accepts, and how often I meet them (0-3).
  // The authority counts as a contact everyone has; "meeting" it means having had internet.
  function lookup(matches) {
    for (const [name, pub] of authority ? [...contacts, [KURUM, authority]] : contacts)
      for (const tag of tagsOf(pub))
        if (matches(tag)) { const s = score.get(name); return { tag, q: s ? Math.round(decayed(s) * 3) : 0 }; }
    return { q: 0 };
  }
  const knows = (tag, eid) => sha256(tag, 'knows', eid).subarray(0, 8);

  // Makes room in a table: a stranger's entry may only displace another stranger's, never ours or a contact's.
  function makeRoom(table, max, strangerMax, trusted) {
    const strangers = [...table].filter(([, v]) => !v.trusted);
    if (table.size < max && (trusted || strangers.length < strangerMax)) return true;
    const victim = strangers[0] ?? (trusted ? [...table].find(([, v]) => !v.own) : undefined);
    if (!victim) return false;
    table.delete(victim[0]);
    return true;
  }

  function remember(table, key, receipt) {
    if (table.size >= MAX_SEEN) table.delete(table.keys().next().value);
    table.set(key, receipt);
    doneCache.clear();
  }
  function doneFor(peer) {
    let done = doneCache.get(hex(peer));
    if (!done) {
      if (doneCache.size >= 16) doneCache.clear(); // peers rotate their ids every 15 minutes
      done = new Map([...seen, ...cleared].map(([key, receipt]) => [hex(blind(Buffer.from(key, 'hex'), peer)), [key, receipt]]));
      doneCache.set(hex(peer), done);
    }
    return done;
  }
  const receiptFor = (key) => seen.get(key) ?? cleared.get(key);
  const ack = (to, id, proof) => send(Buffer.concat([Buffer.from([ACK]), eid(), to, id, proof]));

  // Drops a carried or own message whose receipt checks out, and remembers the receipt to pass on.
  function settle(key, receipt) {
    const m = store.get(key);
    if (!m || !sha256(receipt).subarray(0, 16).equals(m.rh)) return false;
    store.delete(key);
    remember(cleared, key, Buffer.from(receipt));
    if (m.own && m.acil) onEmergencyDelivered(key);
    return true;
  }

  // Gateway duty: hand an emergency envelope to the authority's server; on its receipt, announce the delivery.
  function upload(key, m) {
    if (m.uploading) return;
    m.uploading = true;
    Promise.resolve().then(() => uplink(m.env)).then((receipt) => {
      // an empty receipt: the authority refused the envelope for good, so carrying it on only wastes the
      // emergency quota and the radio (a junk flood was re-uploaded every tick for a day). The owner's own call
      // is sent again by the app, in a fresh envelope.
      if (receipt && receipt.length === 0) store.delete(key);
      else if (receipt && settle(key, receipt)) ack(Buffer.alloc(8), m.id, receipt);
      else m.uploading = false;
    }, () => { m.uploading = false; }); // no signal after all: keep carrying, retry on a later tick
  }

  function onHello(b) {
    const from = b.subarray(1, 9), n = b[9];
    if (n > HELLO_SLOTS || b.length !== 10 + n * 16 || isMe(from)) return;
    const who = recognise(from, recognition, now());
    if (who) met(who);
    if (!n) return;
    const have = new Set([...store.values()].map((m) => hex(blind(m.id, from))));
    const done = doneFor(from);
    const mine = tagsOf(identity.pub).map((t) => hex(blind(t, from)));
    // A phone with internet answers for the authority: it will upload the envelope at once.
    if (gateway()) mine.push(...tagsOf(authority).map((t) => hex(blind(t, from))));
    for (let i = 0; i < n; i++) {
      const h = b.subarray(10 + i * 16, 18 + i * 16), bt = b.subarray(18 + i * 16, 26 + i * 16);
      if (have.has(hex(h))) continue;
      const delivered = done.get(hex(h));
      if (delivered) { ack(from, Buffer.from(delivered[0], 'hex'), delivered[1]); continue; }
      const forMe = mine.includes(hex(bt));
      if (!forMe && !relay) continue;
      if (scoreBudget-- <= 0) return;
      const hit = forMe ? { q: MINE } : lookup((t) => blind(t, from).equals(bt));
      const proof = hit.tag && hit.q > 0 ? knows(hit.tag, from) : Buffer.alloc(8);
      send(Buffer.concat([Buffer.from([SCORE]), eid(), from, h, Buffer.from([hit.q]), proof]));
    }
  }

  function onScore(b) {
    const to = b.subarray(9, 17);
    if (b.length !== 34 || !isMe(to)) return;
    const from = b.subarray(1, 9), h = b.subarray(17, 25), q = b[25];
    const m = q > MINE ? undefined : [...store.values()].find((x) => blind(x.id, to).equals(h));
    if (!m) return;
    const key = hex(m.id) + hex(from);
    if (burst.has(key)) return;
    let give = 0;
    if (q !== MINE) {
      // Score claims cannot be verified, so a stranger never gets more than half the copies, and the
      // last copy only moves to someone we know. The sender never gives up its own last copy at all.
      const mq = lookup((t) => t.equals(m.tag)).q;
      // Emergency mail goes to anyone willing to carry it: reaching the authority beats the risk of a bad courier.
      const friend = m.acil || known(from) || (q > 0 && knows(m.tag, to).equals(b.subarray(26, 34)));
      if (m.tokens > 1) {
        give = q + mq > 0 ? Math.min(m.tokens - 1, Math.round((m.tokens * q) / (q + mq))) : m.tokens >> 1;
        if (!friend) give = Math.min(give, m.tokens >> 1);
      } else give = friend && !m.own && q > mq ? 1 : 0;
      if (give < 1) return;
    }
    const enc = encoder(m.env, SYM);
    if (dataBudget < enc.k + 2) return;
    dataBudget -= enc.k + 2;
    burst.add(key);
    const retry = pending.has(key);
    pending.set(key, give);
    const head = Buffer.concat([Buffer.from([DATA]), eid(), from, m.id, m.tag, m.rh, Buffer.from([give, enc.k >> 8, enc.k & 255])]);
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
    if (store.has(key)) return; // already carrying it: no ack, so the sender keeps its copies
    if (receiptFor(key)) return ack(from, id, receiptFor(key));
    const forMe = () => tagsOf(identity.pub).some((t) => t.equals(tag));
    const acil = isKurumTag(tag);
    if (!relay && !forMe() && !(acil && gateway())) return;
    // A stranger's emergency mail is carried like a contact's. Forging it in bulk is bounded by the proof of work
    // checked below and by its own quota in the store.
    const trusted = known(from) || acil;
    let p = partial.get(key);
    if (!p) {
      if (k < 1 || k > MAX_K || !makeRoom(partial, MAX_PARTIAL, STRANGER_PARTIAL, trusted)) return;
      partial.set(key, (p = { dec: decoder(k, SYM), at: now(), trusted }));
    }
    p.dec.add({ seed: b.readUInt32BE(60), data: b.subarray(64) });
    if (!p.dec.done) return;
    partial.delete(key);
    let env;
    try { env = p.dec.result(); } catch { return; }
    if (!sha256(env, rh).subarray(0, 8).equals(id)) return; // corrupted or forged packets
    if (acil && !hasWork(env)) return;
    if (!forMe()) {
      if (acil) {
        if (kaynakSiniri !== Infinity) {
          const k = hex(from), z = (kaynaklar.get(k) ?? []).filter((t) => now() - t < 3600000);
          if (z.length >= kaynakSiniri) return;
          kaynaklar.set(k, [...z, now()]);
        }
        // others' emergency mail has its own quota; when full, the oldest one (or a random one) makes room
        const others = [...store].filter(([, v]) => v.acil && !v.own);
        if (others.length >= ACIL)
          store.delete((tahliye === 'rastgele' ? others[Math.floor(rastgele() * others.length)] : others.sort((a, b) => a[1].at - b[1].at)[0])[0]);
      }
      if (!makeRoom(store, MAXS, MAXS / 2, trusted)) return;
      const m = { id: Buffer.from(id), tag, rh, env, tokens: Math.min(Math.max(tokens, 1), MAX_TOKENS), at: now(), trusted, acil };
      store.set(key, m);
      ack(from, id, proofOf(env, eid()));
      if (acil && gateway()) upload(key, m);
      return;
    }
    let msg;
    try { msg = open(identity, env); } catch { return; }
    if (msg.body.length < 24) return;
    // The sealed timestamp stops an old envelope from being replayed after our memory of it is gone.
    const sentAt = Number(msg.body.readBigUInt64BE(16));
    if (now() - sentAt > TTL_MS + SKEW_MS || sentAt - now() > SKEW_MS) return;
    const receipt = Buffer.from(msg.body.subarray(0, 16));
    remember(seen, key, receipt);
    ack(from, id, receipt);
    const sender = [...contacts].find(([, pub]) => pub.equals(msg.senderPub));
    onMessage(sender ? sender[0] : `unknown:${hex(msg.senderPub).slice(0, 8)}`, msg.body.subarray(24).toString('utf8'));
  }

  function onAck(b) {
    if (b.length !== 41) return;
    const from = b.subarray(1, 9), id = b.subarray(17, 25), proof = b.subarray(25, 41), key = hex(id), m = store.get(key);
    if (!m) return;
    // Anyone may relay a receipt: only the recipient could have produced it.
    if (settle(key, proof)) return;
    if (!isMe(b.subarray(9, 17))) return;
    const give = pending.get(key + hex(from));
    // A hand-over counts only if the peer proves it holds the whole envelope; overhearing the exchange is not enough.
    if (!give || !proofOf(m.env, from).equals(proof)) return;
    pending.delete(key + hex(from));
    if (m.tokens > 1) m.tokens -= Math.min(give, m.tokens - 1); else if (!m.own) store.delete(key);
  }

  // NOTICE: [5][id 8][issued 8][expires 8][len 2][text][sig 64]; signed over everything before the signature.
  function onNotice(b) {
    if (!authoritySign || b.length < 27 + 64) return;
    const len = b.readUInt16BE(25);
    if (len > NOTICE_TEXT || b.length !== 27 + len + 64) return;
    const key = hex(b.subarray(1, 9));
    if (notices.has(key)) return;
    const issued = Number(b.readBigUInt64BE(9)), expires = Number(b.readBigUInt64BE(17));
    if (expires <= now() || issued - now() > SKEW_MS) return;
    if (!verifySignature(authoritySign, b.subarray(0, 27 + len), b.subarray(27 + len))) return;
    if (notices.size >= MAX_NOTICES) {
      const soonest = [...notices].sort((x, y) => x[1].expires - y[1].expires)[0];
      if (soonest[1].expires > expires) return;
      notices.delete(soonest[0]);
    }
    notices.set(key, { frame: Buffer.from(b), expires, sent: -Infinity });
    onAnnouncement({ id: key, issued, expires, text: b.subarray(27, 27 + len).toString('utf8') });
  }

  function post(pub, body, tokens, acil) {
    const receipt = randomBytes(16), rh = sha256(receipt).subarray(0, 16), sentAt = Buffer.alloc(8);
    sentAt.writeBigUInt64BE(BigInt(now()));
    const sealed = seal(identity, pub, Buffer.concat([receipt, sentAt, body]));
    const env = acil ? addWork(sealed) : sealed;
    if (Math.ceil((env.length + 4) / SYM) > MAX_K) throw new RangeError('message too large');
    if (!makeRoom(store, MAXS, MAXS / 2, true)) throw new Error('outbox full');
    const id = sha256(env, rh).subarray(0, 8);
    store.set(hex(id), { id, tag: recipientTag(pub, now()), rh, env, tokens, at: now(), own: true, trusted: true, acil });
    return hex(id);
  }

  return {
    pub: identity.pub,
    addContact,
    get carrying() { return store.size; },
    get relay() { return relay; },
    set relay(on) { relay = on; },
    get delivered() { return [...seen].map(([key, receipt]) => [key, hex(receipt)]); },
    get copies() { return [...store.values()].reduce((sum, m) => sum + m.tokens, 0); },
    get notices() { return [...notices.values()].map((n) => n.frame); },
    sendMessage(name, text) {
      const pub = contacts.get(name);
      if (!pub) throw new Error(`unknown contact: ${name}`);
      return post(pub, Buffer.from(text, 'utf8'), L, false);
    },
    // `report` is the structured emergency record (JSON): triage fields, location from the phone's GPS (which works
    // without a network), time. Returns the message id; `onEmergencyDelivered(id)` fires when the authority has it.
    sendEmergency(report) {
      if (!authority) throw new Error('no authority key configured');
      return post(authority, Buffer.from(JSON.stringify(report), 'utf8'), ACIL_TOKENS, true);
    },
    receive(frame) {
      try {
        if (!Buffer.isBuffer(frame) || frame.length < 10) return;
        ({ [HELLO]: onHello, [SCORE]: onScore, [DATA]: onData, [ACK]: onAck, [NOTICE]: onNotice })[frame[0]]?.(frame);
      } catch { /* malformed frame from an untrusted radio: drop it */ }
    },
    // Call every few seconds: expires old state, refills transmit budgets and announces what we carry.
    tick() {
      for (const [k, m] of store) if (now() - m.at > TTL_MS) store.delete(k);
      for (const [k, p] of partial) if (now() - p.at > PARTIAL_MS) partial.delete(k);
      if (pending.size > 1000) pending.clear();
      burst.clear();
      scoreBudget = SCORE_BUDGET;
      dataBudget = DATA_BUDGET;
      if (gateway()) {
        met(KURUM);
        for (const [k, m] of store) if (m.acil) upload(k, m); // includes mail picked up while offline
      }
      // A phone with internet fetches the authority's announcements every few minutes and brings them into the mesh.
      if (downlink && online() && now() - lastDownlink > 5 * NOTICE_EVERY_MS) {
        lastDownlink = now();
        Promise.resolve().then(downlink).then((frames) => { for (const f of frames ?? []) onNotice(Buffer.from(f)); }, () => {});
      }
      for (const [k, n] of notices) if (n.expires <= now()) notices.delete(k);
      let sent = 0;
      for (const n of notices.values())
        if (sent < NOTICES_PER_TICK && now() - n.sent >= NOTICE_EVERY_MS) { n.sent = now(); sent++; send(n.frame); }
      // emergency mail is announced first, so it wins the HELLO slots and the transmit budget
      const all = [...store.values()].sort((a, b) => (b.acil ? 1 : 0) - (a.acil ? 1 : 0));
      const n = Math.min(all.length, HELLO_SLOTS), me = eid();
      const parts = [Buffer.from([HELLO]), me, Buffer.from([n])];
      for (let i = 0; i < n; i++) { const m = all[(cursor + i) % all.length]; parts.push(blind(m.id, me), blind(m.tag, me)); }
      cursor += n;
      send(Buffer.concat(parts));
    },
  };
}
