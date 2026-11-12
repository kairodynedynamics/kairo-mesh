// Courier-routing simulator: people moving between neighbourhoods carry sealed messages
// for recipients who are out of radio reach. Compares hand-over policies on identical
// mobility and traffic (same seed => same world).
//
//   wait     binary spray-and-wait: the published Bitchat courier rule (budget L, hand over half)
//   focus    Kairo: same spray, then the last copy keeps moving toward carriers that meet
//            the recipient more often (encounter-history score)
//   epidemic copy to everyone: the delivery ceiling, at unbounded cost
//
// `social: true` gives everyone a contact list and only sends messages between contacts.
// `hardened: true` applies the rules the real node uses against liars: a device only knows scores for
// its own contacts, a stranger gets at most half the copies, the last copy only moves to a contact,
// and the sender never gives up its own last copy. `proofs: true` also trusts anyone who proves they are
// a contact of the recipient (the node's SCORE proof).
//
// shortcut: ignores per-contact bandwidth and storage caps and the 7-hop flood limit
// (all favour every policy equally); upgrade before using it for capacity planning.
import { rng } from './rng.js';

const DEFAULTS = {
  seed: 1, policy: 'focus', L: 4, social: false, hardened: false, proofs: false, extraContacts: 5,
  N: 80, clusters: 6, world: 2000, spread: 60, range: 30, dt: 2,
  hours: 8, warmupH: 1, sendUntilH: 5, ttlH: 3, messages: 300,
  halfLifeS: 7200, pEncounter: 0.75, beta: 0.25, minGain: 0.3, reencounterS: 300,
};

export function simulate(opts = {}) {
  const o = { ...DEFAULTS, ...opts };
  const { N } = o;
  const r = rng(o.seed);
  const gauss = () => Math.sqrt(-2 * Math.log(1 - r())) * Math.cos(2 * Math.PI * r());

  const centres = Array.from({ length: o.clusters }, () => ({ x: r() * o.world, y: r() * o.world }));
  const nodes = Array.from({ length: N }, (_, i) => {
    const home = i % o.clusters;
    const alt = (home + 1 + Math.floor(r() * (o.clusters - 1))) % o.clusters;
    const n = { home, alt, x: 0, y: 0, tx: 0, ty: 0, pause: 0, speed: 1 };
    retarget(n);
    n.x = n.tx; n.y = n.ty;
    return n;
  });
  // People mostly stay around home, regularly visit one other place, rarely go elsewhere.
  function retarget(n) {
    const u = r();
    const c = centres[u < 0.8 ? n.home : u < 0.95 ? n.alt : Math.floor(r() * o.clusters)];
    n.tx = c.x + gauss() * o.spread; n.ty = c.y + gauss() * o.spread;
    n.speed = 1 + r() * 0.5; n.pause = r() * 300;
  }

  // Contacts: everyone from the same neighbourhood plus a few people elsewhere.
  const friend = new Uint8Array(N * N);
  const contactsOf = Array.from({ length: N }, () => []);
  if (o.social || o.hardened) {
    const link = (a, b) => { if (a !== b && !friend[a * N + b]) { friend[a * N + b] = friend[b * N + a] = 1; contactsOf[a].push(b); contactsOf[b].push(a); } };
    for (let a = 0; a < N; a++) {
      for (let b = a + 1; b < N; b++) if (nodes[a].home === nodes[b].home) link(a, b);
      for (let i = 0; i < o.extraContacts; i++) link(a, Math.floor(r() * N));
    }
  }

  const T = o.hours * 3600;
  const msgs = Array.from({ length: o.messages }, () => {
    const src = Math.floor(r() * N);
    let dst = Math.floor(r() * (N - 1)); if (dst >= src) dst++;
    if (o.social) dst = contactsOf[src][Math.floor(r() * contactsOf[src].length)];
    return { src, dst, at: (o.warmupH + r() * (o.sendUntilH - o.warmupH)) * 3600, done: -1 };
  }).sort((a, b) => a.at - b.at);

  const P = Array.from({ length: N }, () => new Float64Array(N)); // P[a][b]: how likely a is to meet b
  const aged = new Float64Array(N);
  const lastEnc = new Float64Array(N * N).fill(-1e9);
  const decay = Math.log(0.5) / o.halfLifeS;
  const age = (a, t) => { const f = Math.exp(decay * (t - aged[a])); for (let i = 0; i < N; i++) P[a][i] *= f; aged[a] = t; };
  function encounter(a, b, t) {
    if (t - lastEnc[a * N + b] < o.reencounterS) return;
    lastEnc[a * N + b] = t;
    age(a, t); age(b, t);
    P[a][b] += (1 - P[a][b]) * o.pEncounter;
    P[b][a] += (1 - P[b][a]) * o.pEncounter;
    for (let c = 0; c < N; c++) {
      if (c === a || c === b) continue;
      P[a][c] = Math.max(P[a][c], P[a][b] * P[b][c] * o.beta);
      P[b][c] = Math.max(P[b][c], P[b][a] * P[a][c] * o.beta);
    }
  }

  const hold = Array.from({ length: N }, () => new Map()); // node -> (message index -> copies it may still hand out)
  let tx = 0, next = 0;
  function offer(a, b, t) {
    for (const [m, tokens] of hold[a]) {
      const msg = msgs[m];
      if (msg.done >= 0 || t - msg.at > o.ttlH * 3600) { hold[a].delete(m); continue; }
      if (hold[b].has(m)) continue;
      if (o.policy === 'epidemic') { hold[b].set(m, Infinity); tx++; }
      else if (o.policy === 'focus' && o.hardened) {
        // Scores as the node sees them: only for the recipient's own contacts, in four coarse levels.
        const q = (x) => (x === msg.dst || friend[x * N + msg.dst] ? Math.round(P[x][msg.dst] * 3) : 0);
        const qa = q(a), qb = q(b), known = friend[a * N + b] || (o.proofs && qb > 0);
        if (tokens > 1) {
          let give = qa + qb > 0 ? Math.min(tokens - 1, Math.round((tokens * qb) / (qa + qb))) : tokens >> 1;
          if (!known) give = Math.min(give, tokens >> 1);
          if (give < 1) continue;
          hold[a].set(m, tokens - give); hold[b].set(m, give); tx++;
        } else if (known && a !== msg.src && qb > qa) { hold[a].delete(m); hold[b].set(m, 1); tx++; }
      }
      else if (tokens > 1) {
        // Kairo splits copies in proportion to who meets the recipient more; Bitchat's rule is always half.
        const pa = P[a][msg.dst], pb = P[b][msg.dst];
        const give = o.policy === 'focus' && pa + pb > 0 ? Math.min(tokens - 1, Math.round((tokens * pb) / (pa + pb))) : tokens >> 1;
        if (give < 1) continue;
        hold[a].set(m, tokens - give); hold[b].set(m, give); tx++;
      }
      else if (o.policy === 'focus' && P[b][msg.dst] > P[a][msg.dst] + o.minGain) { hold[a].delete(m); hold[b].set(m, 1); tx++; }
    }
  }

  const comp = new Int32Array(N);
  const find = (x) => { while (comp[x] !== x) x = comp[x] = comp[comp[x]]; return x; };
  const r2 = o.range * o.range;
  const links = [];

  for (let t = 0; t < T; t += o.dt) {
    for (const n of nodes) {
      if (n.pause > 0) { n.pause -= o.dt; continue; }
      const dx = n.tx - n.x, dy = n.ty - n.y, d = Math.hypot(dx, dy), step = n.speed * o.dt;
      if (d <= step) { n.x = n.tx; n.y = n.ty; retarget(n); } else { n.x += (dx / d) * step; n.y += (dy / d) * step; }
    }
    while (next < msgs.length && msgs[next].at <= t) { hold[msgs[next].src].set(next, o.policy === 'epidemic' ? Infinity : o.L); next++; }

    links.length = 0;
    for (let i = 0; i < N; i++) comp[i] = i;
    for (let a = 0; a < N; a++)
      for (let b = a + 1; b < N; b++) {
        const dx = nodes[a].x - nodes[b].x, dy = nodes[a].y - nodes[b].y;
        if (dx * dx + dy * dy <= r2) { links.push(a, b); comp[find(a)] = find(b); encounter(a, b, t); }
      }
    for (let i = 0; i < links.length; i += 2) { offer(links[i], links[i + 1], t); offer(links[i + 1], links[i], t); }

    // A message arrives once any carrier is in the same connected mesh as the recipient.
    for (let a = 0; a < N; a++)
      for (const m of hold[a].keys()) {
        const msg = msgs[m];
        if (msg.done < 0 && t - msg.at <= o.ttlH * 3600 && find(a) === find(msg.dst)) msg.done = t;
      }
  }

  const lat = msgs.filter((m) => m.done >= 0).map((m) => m.done - m.at).sort((a, b) => a - b);
  return {
    delivery: lat.length / msgs.length,
    medianLatencyMin: lat.length ? lat[lat.length >> 1] / 60 : NaN,
    txPerMessage: tx / msgs.length,
  };
}
