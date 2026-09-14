import assert from 'node:assert/strict'
import { createScaleFixture, fixtureStats } from '../src/stage2c-fixtures.js'

for (const tier of ['normal', 'large']) {
  const first = createScaleFixture(tier)
  const second = createScaleFixture(tier)
  assert.deepEqual(first, second, `${tier} fixture is deterministic`)

  const stats = fixtureStats(first)
  assert.equal(stats.duplicateIds.length, 0)
  assert.equal(stats.uniqueIdCount, stats.idCount)
  assert.ok(stats.canonicalBytes > 0)

  const kinds = new Set(first.map((entry) => entry.block.kind))
  const textKinds = ['paragraph', 'heading', 'bullet_list', 'numbered_list', 'quote', 'callout', 'code']
  const objectKinds = ['image', 'table', 'chart', 'math', 'file', 'embed', 'mermaid', 'shape', 'unknown']
  for (const kind of textKinds) {
    assert.ok(kinds.has(kind), `${tier} includes ${kind}`)
  }
  assert.deepEqual(
    [...kinds].sort(),
    [...textKinds, ...objectKinds].sort(),
    `${tier} top-level kind set is exactly the declared text and object kinds`,
  )
  assert.ok(!kinds.has('formula'), `${tier} no longer emits the retired formula kind`)

  const mathEntry = first.find((entry) => entry.block.kind === 'math')
  assert.ok(mathEntry, `${tier} includes a math atom`)
  assert.equal(mathEntry.block.tex, 'x^2 + y^2')
  assert.equal(mathEntry.block.display, true)
  assert.equal(mathEntry.block.editorHeightDp, 140)
}

const normalStats = fixtureStats(createScaleFixture('normal'))
assert.equal(normalStats.topLevelBlocks, 250)
assert.equal(normalStats.atomCount, 20)
assert.ok(normalStats.wordCount >= 15_000 && normalStats.wordCount <= 25_000)

const largeStats = fixtureStats(createScaleFixture('large'))
assert.equal(largeStats.topLevelBlocks, 1_000)
assert.equal(largeStats.atomCount, 40)
assert.ok(largeStats.wordCount >= 80_000 && largeStats.wordCount <= 120_000)

assert.throws(() => createScaleFixture('stress'), /Unknown Stage-2C fixture tier/)

console.log('PASS Stage-2C deterministic normal/large fixture size, mix, and identity')
