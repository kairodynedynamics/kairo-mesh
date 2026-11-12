// Rateless (fountain) fragmentation over GF(2).
// Any ~k+2 distinct packets rebuild the message, whichever relays they came from,
// so no per-fragment retransmission or reconciliation round is needed.
import { rng } from './rng.js';

export const SYMBOL_SIZE = 469; // same fragment size Bitchat uses, for a like-for-like comparison
const MAX_K = 4096;

// Coefficient row for a packet: seeds below k are the plain fragments (systematic),
// higher seeds are random XOR combinations.
function coeffs(seed, k) {
  const c = new Uint32Array(Math.ceil(k / 32));
  if (seed < k) {
    c[seed >>> 5] = 1 << (seed & 31);
    return c;
  }
  const r = rng(seed);
  for (let i = 0; i < c.length; i++) c[i] = (r() * 4294967296) >>> 0;
  if (k & 31) c[c.length - 1] &= (1 << (k & 31)) - 1;
  if (lowestBit(c) < 0) c[0] = 1;
  return c;
}

function lowestBit(c) {
  for (let i = 0; i < c.length; i++) if (c[i]) return i * 32 + (31 - Math.clz32(c[i] & -c[i]));
  return -1;
}

function xor(dst, src) {
  for (let i = 0; i < dst.length; i++) dst[i] ^= src[i];
}

export function encoder(message, symbolSize = SYMBOL_SIZE) {
  const k = Math.max(1, Math.ceil((message.length + 4) / symbolSize));
  if (k > MAX_K) throw new RangeError('message too large');
  const buf = Buffer.alloc(k * symbolSize);
  buf.writeUInt32BE(message.length, 0);
  Buffer.from(message).copy(buf, 4);
  return {
    k,
    packet(seed) {
      const c = coeffs(seed >>> 0, k);
      const data = Buffer.alloc(symbolSize);
      for (let i = 0; i < k; i++)
        if (c[i >>> 5] & (1 << (i & 31))) xor(data, buf.subarray(i * symbolSize, (i + 1) * symbolSize));
      return { seed: seed >>> 0, data };
    },
  };
}

export function decoder(k, symbolSize = SYMBOL_SIZE) {
  if (!Number.isInteger(k) || k < 1 || k > MAX_K) throw new RangeError('bad k');
  const rows = new Array(k); // rows[p] has its lowest set coefficient at p
  let rank = 0;
  return {
    get rank() { return rank; },
    get done() { return rank === k; },
    // Returns true if the packet added new information.
    add(packet) {
      const { seed, data } = packet ?? {};
      if (!Number.isInteger(seed) || seed < 0 || seed > 0xffffffff || data?.length !== symbolSize) return false;
      const c = coeffs(seed, k);
      const d = Buffer.from(data);
      for (;;) {
        const p = lowestBit(c);
        if (p < 0) return false;
        if (!rows[p]) { rows[p] = { c, d }; rank++; return true; }
        xor(c, rows[p].c);
        xor(d, rows[p].d);
      }
    },
    result() {
      if (rank !== k) throw new Error('not enough packets');
      for (let p = k - 1; p > 0; p--)
        for (let q = 0; q < p; q++)
          if (rows[q].c[p >>> 5] & (1 << (p & 31))) { xor(rows[q].c, rows[p].c); xor(rows[q].d, rows[p].d); }
      const buf = Buffer.concat(rows.map((r) => r.d));
      const len = buf.readUInt32BE(0);
      if (len > buf.length - 4) throw new Error('corrupt length');
      return buf.subarray(4, 4 + len);
    },
  };
}
