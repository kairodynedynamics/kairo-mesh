// The authority side of emergency mail: opens envelopes sealed to its key and answers with their receipts.
// Runs on the authority's (or a partner's) server, never on a phone; a phone with internet only forwards bytes.
//
// What the authority learns per report: the sender's long-term public key, the sealed send time, and the report
// itself (triage fields, GPS location, free text). Couriers and gateways learn none of it.
import { createServer } from 'node:http';
import { open, sha256, DAY_MS } from './crypto.js';

const SKEW_MS = 3600000, MAX_REPORT = 64 * 1024;

export function createAuthority({ identity, now = Date.now, onReport = () => {} }) {
  const reports = new Map(); // envelope hash -> { receipt, report }: a report forwarded by many gateways counts once

  // Returns the 16-byte receipt, or null if the envelope is not for us, altered, stale or malformed.
  function receive(envelope) {
    const key = sha256(envelope).toString('hex');
    const known = reports.get(key);
    if (known) return known.receipt;
    let msg;
    try { msg = open(identity, envelope); } catch { return null; }
    if (msg.body.length < 24) return null;
    const sentAt = Number(msg.body.readBigUInt64BE(16));
    if (now() - sentAt > DAY_MS + SKEW_MS || sentAt - now() > SKEW_MS) return null;
    let report;
    try { report = JSON.parse(msg.body.subarray(24).toString('utf8')); } catch { return null; }
    const receipt = Buffer.from(msg.body.subarray(0, 16));
    const entry = { receipt, report, sender: msg.senderPub.toString('hex'), sentAt, receivedAt: now() };
    reports.set(key, entry);
    onReport(entry);
    return receipt;
  }

  return { pub: identity.pub, receive, get reports() { return [...reports.values()]; } };
}

// HTTP front for gateways: POST /v1/acil with the raw envelope, answered with the raw 16-byte receipt.
// GET /v1/acil lists reports for a dashboard. TLS, authentication of the dashboard and storage belong to the
// deployment (reverse proxy, database), not to this reference.
export function gatewayServer(authority) {
  return createServer((req, res) => {
    if (req.url !== '/v1/acil') return res.writeHead(404).end();
    if (req.method === 'GET') {
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify(authority.reports.map(({ receipt, ...r }) => r)));
    }
    if (req.method !== 'POST') return res.writeHead(405).end();
    const parts = [];
    let size = 0;
    req.on('data', (c) => { size += c.length; if (size > MAX_REPORT) req.destroy(); else parts.push(c); });
    req.on('end', () => {
      const receipt = authority.receive(Buffer.concat(parts));
      if (!receipt) return res.writeHead(422).end();
      res.writeHead(200, { 'content-type': 'application/octet-stream' }).end(receipt);
    });
  });
}

// What a phone's `uplink` does once it has internet: post the envelope, return the receipt (or null to retry later).
export const httpUplink = (url) => async (envelope) => {
  const res = await fetch(url, { method: 'POST', body: envelope, headers: { 'content-type': 'application/octet-stream' } });
  return res.ok ? Buffer.from(await res.arrayBuffer()) : null;
};
