import assert from 'node:assert/strict'
import { createCanonicalHistory } from '../src/canonical-history.js'

const transaction = {
  docChanged: true,
  selectionSet: false,
  getMeta: () => false,
}

// Dropping a capped entry must advance the undo baseline to that entry.
{
  let snapshot = 'A'
  let selection = 0
  const restored = []
  const history = createCanonicalHistory({
    getSnapshot: () => snapshot,
    getSelection: () => ({ from: selection }),
    restore: (value) => {
      snapshot = value
      restored.push(value)
    },
    maxEntries: 2,
  })
  for (const value of ['B', 'C', 'D']) {
    snapshot = value
    selection += 1
    history.onTransaction(transaction)
    history.flush()
  }

  assert.equal(history.info().entries, 2)
  assert.deepEqual(history.undo(), { ok: true })
  assert.deepEqual(history.undo(), { ok: true })
  assert.deepEqual(restored, ['C', 'B'])
  assert.deepEqual(history.undo(), { ok: false, reason: 'stack-empty' })
}

// Destroying an editor cancels pending capture and ignores later transactions.
{
  let snapshot = 'A'
  const history = createCanonicalHistory({
    getSnapshot: () => snapshot,
    getSelection: () => ({ from: 0 }),
    restore: () => {},
  })
  snapshot = 'B'
  history.onTransaction(transaction)
  history.dispose()
  history.flush()
  snapshot = 'C'
  history.onTransaction(transaction)
  history.flush()
  assert.equal(history.info().entries, 0)
  assert.deepEqual(history.undo(), { ok: false, reason: 'stack-empty' })
}

console.log('PASS Stage-2C history cap baseline and lifecycle disposal')
