function invalid() {
  throw new Error('Invalid Stage-2C session')
}

const VALID_TIERS = new Set(['hostile', 'normal', 'large', 'custom'])
const VALID_SELECTION_KINDS = new Set(['cursor', 'text', 'node'])
const MAX_SESSION_BYTES = 8 * 1024 * 1024

export function sessionByteLength(json) {
  return new TextEncoder().encode(json).length
}

export function encodeSession({ fixtureTier, blockDoc, selection }) {
  return JSON.stringify({ version: 1, fixtureTier, blockDoc, selection })
}

export function decodeSession(json) {
  if (typeof json !== 'string' || !json.length || sessionByteLength(json) > MAX_SESSION_BYTES) return invalid()
  let session
  try {
    session = JSON.parse(json)
  } catch (_error) {
    return invalid()
  }
  if (
    session?.version !== 1
    || !VALID_TIERS.has(session.fixtureTier)
    || !Array.isArray(session.blockDoc)
    || !session.blockDoc.length
    || session.blockDoc.some((entry) => (
      !Number.isFinite(entry?.v)
      || typeof entry?.block?.kind !== 'string'
      || !Object.prototype.hasOwnProperty.call(entry.block, 'id')
    ))
    || !session.selection
    || !VALID_SELECTION_KINDS.has(session.selection.kind)
    || !Number.isFinite(session.selection.from)
    || !Number.isFinite(session.selection.to)
    || session.selection.from < 0
    || session.selection.to < session.selection.from
  ) {
    return invalid()
  }
  return session
}

export function timingSummary(samples) {
  if (!Array.isArray(samples) || samples.length === 0) {
    return { count: 0, minMs: null, medianMs: null, p95Ms: null, maxMs: null }
  }
  if (!samples.every((sample) => typeof sample === 'number' && Number.isFinite(sample))) {
    throw new Error('Invalid Stage-2C timing samples')
  }
  const sorted = [...samples].sort((left, right) => left - right)
  const middle = Math.floor(sorted.length / 2)
  const medianMs = sorted.length % 2 === 1
    ? sorted[middle]
    : (sorted[middle - 1] + sorted[middle]) / 2
  const percentile = (value) => sorted[Math.max(0, Math.ceil(sorted.length * value) - 1)]
  return {
    count: sorted.length,
    minMs: sorted[0],
    medianMs,
    p95Ms: percentile(0.95),
    maxMs: sorted.at(-1),
  }
}
