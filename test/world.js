import { createIdentity } from '../src/crypto.js';
import { createNode } from '../src/node.js';
import { rng } from '../src/rng.js';

// A tiny radio: frames reach whoever is currently linked, minus random loss.
// `opts.authority` (public key) and `opts.uplink` turn on emergency mail; `online` says which phones have internet.
export function world(names, loss = 0, seed = 1, opts = {}) {
  const r = rng(seed), ids = Object.fromEntries(names.map((n) => [n, createIdentity()]));
  const links = new Set(), inbox = Object.fromEntries(names.map((n) => [n, []])), nodes = {}, clock = { t: 1_800_000_000_000 };
  const online = new Set(), delivered = Object.fromEntries(names.map((n) => [n, []]));
  for (const n of names)
    nodes[n] = createNode({
      identity: ids[n], now: () => clock.t,
      authority: opts.authority ?? null, uplink: opts.uplink ?? null, online: () => online.has(n),
      onEmergencyDelivered: (id) => delivered[n].push(id),
      authoritySign: opts.authoritySign ?? null, downlink: opts.downlink ?? null,
      onAnnouncement: (d) => opts.onAnnouncement?.(n, d),
      contacts: new Map(names.filter((m) => m !== n).map((m) => [m, ids[m].pub])),
      onMessage: (from, text) => inbox[n].push([from, text]),
      send: (f) => { for (const m of names) if (links.has(n + m) && r() >= loss) queueMicrotask(() => nodes[m].receive(f)); },
    });
  return {
    nodes, inbox, clock, online, delivered,
    link(a, b, on = true) { for (const k of [a + b, b + a]) on ? links.add(k) : links.delete(k); },
    async run(rounds) {
      for (let i = 0; i < rounds; i++) {
        clock.t += 2000;
        for (const n of names) nodes[n].tick();
        await new Promise((res) => setImmediate(res));
      }
    },
  };
}
