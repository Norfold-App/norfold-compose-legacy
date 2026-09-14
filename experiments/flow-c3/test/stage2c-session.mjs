import assert from 'node:assert/strict'
import { decodeSession, encodeSession, sessionByteLength, timingSummary } from '../src/stage2c-session.js'

const session = {
  fixtureTier: 'large',
  blockDoc: [{ v: 1, block: { kind: 'paragraph', id: 'p1', content: [{ kind: 'text', value: 'Saved' }], align: 'start', style: 'NONE' } }],
  selection: { kind: 'cursor', from: 3, to: 3, empty: true, node: null, text: '' },
}
assert.deepEqual(decodeSession(encodeSession(session)), { version: 1, ...session })

for (const invalid of [
  '',
  '{}',
  '{"version":2,"blockDoc":[],"selection":{}}',
  '{"version":1,"fixtureTier":"large","blockDoc":{},"selection":{"kind":"cursor","from":0,"to":0}}',
  '{"version":1,"fixtureTier":"unknown","blockDoc":[],"selection":{"kind":"cursor","from":0,"to":0}}',
  '{"version":1,"fixtureTier":"large","blockDoc":[{}],"selection":{"kind":"cursor","from":0,"to":0}}',
  '{"version":1,"fixtureTier":"large","blockDoc":[],"selection":{"kind":"invalid","from":0,"to":0}}',
  '{"version":1,"fixtureTier":"large","blockDoc":[],"selection":{"kind":"cursor","from":2,"to":1}}',
]) {
  assert.throws(() => decodeSession(invalid), /Invalid Stage-2C session/)
}

assert.deepEqual(timingSummary([4, 1, 100, 2, 3]), {
  count: 5,
  minMs: 1,
  medianMs: 3,
  p95Ms: 100,
  maxMs: 100,
})
assert.deepEqual(timingSummary([]), {
  count: 0,
  minMs: null,
  medianMs: null,
  p95Ms: null,
  maxMs: null,
})

assert.equal(sessionByteLength('é'), 2)
assert.deepEqual(timingSummary([1, 2, 3, 4]).medianMs, 2.5)
assert.deepEqual(timingSummary([1, 2]).medianMs, 1.5)
assert.throws(() => timingSummary([1, NaN, 3]), /Invalid Stage-2C timing samples/)
assert.throws(() => timingSummary([1, '9', 3]), /Invalid Stage-2C timing samples/)

{
  const oversize = {
    fixtureTier: 'large',
    blockDoc: [{ v: 1, block: { kind: 'paragraph', id: 'p-big', content: [{ kind: 'text', value: 'é'.repeat(4195304) }], align: 'start', style: 'NONE' } }],
    selection: { kind: 'cursor', from: 0, to: 0, empty: true, node: null, text: '' },
  }
  const json = encodeSession(oversize)
  assert.ok(json.length <= 8 * 1024 * 1024, 'oversize fixture stays under the UTF-16 length guard')
  assert.ok(sessionByteLength(json) > 8 * 1024 * 1024, 'oversize fixture exceeds the UTF-8 byte guard')
  assert.throws(() => decodeSession(json), /Invalid Stage-2C session/)
}

console.log('PASS Stage-2C session encoding and timing summaries')
