// Rotating on-air identifiers. Only someone holding a contact's recognition key
// (exchanged at pairing) can link two epochs to the same device.
import { createHmac, timingSafeEqual } from 'node:crypto';

export const EPOCH_MS = 15 * 60 * 1000;

export function ephemeralId(key, timeMs = Date.now()) {
  return createHmac('sha256', key).update(String(Math.floor(timeMs / EPOCH_MS))).digest().subarray(0, 8);
}

// contacts: Map<name, recognitionKey>. Accepts the neighbouring epochs to tolerate clock skew.
export function recognise(eid, contacts, timeMs = Date.now()) {
  if (eid?.length !== 8) return null;
  for (const [name, key] of contacts)
    for (const skew of [0, -EPOCH_MS, EPOCH_MS])
      if (timingSafeEqual(eid, ephemeralId(key, timeMs + skew))) return name;
  return null;
}
