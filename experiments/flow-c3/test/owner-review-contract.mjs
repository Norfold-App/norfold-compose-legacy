import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { Schema } from 'prosemirror-model'
import { blockDocToPM, pmToBlockDoc } from '../src/block-document-projection.js'
import { chartValues, resizedHeight } from '../src/owner-review-model.js'

const schema = new Schema({
  nodes: {
    doc: { content: 'block+' },
    paragraph: { content: 'inline*', group: 'block', attrs: { id: { default: null }, align: { default: 'start' }, style: { default: 'NONE' } } },
    text: { group: 'inline' },
    math_atom: { group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Formula' }, tex: { default: '' }, display: { default: true }, editorHeightDp: { default: 140 } } },
  },
})

const formula = [{ v: 1, block: { kind: 'math', id: 'math1', tex: 'E = mc^2', display: true, editorHeightDp: 140 } }]
assert.deepEqual(pmToBlockDoc(blockDocToPM(formula, schema), schema), formula)

assert.equal(resizedHeight(220, 36), 256)
assert.equal(resizedHeight(220, -500), 120)
assert.equal(resizedHeight(220, 900), 420)

assert.deepEqual(
  chartValues('{"data":{"values":[{"month":"Jan","value":24},{"month":"Feb","value":42}]}}'),
  [{ label: 'Jan', value: 24 }, { label: 'Feb', value: 42 }],
)
assert.deepEqual(chartValues('{broken'), [])

const reviewHtml = await readFile(new URL('../../../apps/android/src/debug/assets/flow-c3/review.html', import.meta.url), 'utf8')
assert.match(reviewHtml, /id="editor"/)
assert.doesNotMatch(reviewHtml, /id="bar"|id="status"|Gate [0-9]|doReport/)
assert.match(reviewHtml, /\.ProseMirror:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--selection\)/s)

console.log('PASS owner-review contract: formula, resize bounds, chart data, clean surface')
