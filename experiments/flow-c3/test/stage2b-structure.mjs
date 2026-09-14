import assert from 'node:assert/strict'
import { Schema } from 'prosemirror-model'
import { roundTripCanonical } from '../src/block-document-projection.js'

const inner = '(paragraph | blockquote | callout | container | horizontal_rule | code_block)+'
const nodes = {
  doc: { content: 'block+' },
  paragraph: { content: 'inline*', group: 'block', attrs: { id: { default: null }, align: { default: 'start' }, style: { default: 'NONE' } } },
  text: { group: 'inline' },
  blockquote: { content: inner, group: 'block', attrs: { id: { default: null }, style: { default: 'NONE' } } },
  callout: { content: inner, group: 'block', attrs: { id: { default: null }, tone: { default: 'note' }, title: { default: 'Note' }, collapsed: { default: false }, style: { default: 'NONE' } } },
  container: { content: inner, group: 'block', attrs: { id: { default: null }, axis: { default: 'Column' }, weights: { default: [] } } },
  horizontal_rule: { group: 'block', attrs: { id: { default: null } } },
  code_block: { content: 'text*', group: 'block', marks: '', attrs: { id: { default: null }, language: { default: '' }, editorHeightDp: { default: 180 }, style: { default: 'NONE' } } },
}
const schema = new Schema({ nodes })

const para = (id, value) => ({ v: 1, block: { kind: 'paragraph', id, content: [{ kind: 'text', value }], align: 'start', style: 'NONE' } })

const doc = [
  { v: 1, block: { kind: 'quote', id: 'q1', style: 'NONE', children: [para('qp1', 'Quoted').block] } },
  {
    v: 1, block: {
      kind: 'callout', id: 'c1', tone: 'warning', title: 'Careful', collapsed: false, style: 'NONE',
      children: [para('cp1', 'Body').block, { kind: 'quote', id: 'q2', style: 'NONE', children: [para('qp2', 'Nested').block] }],
    },
  },
  {
    v: 1, block: {
      kind: 'container', id: 'k1', axis: 'Row', weights: [1, 2],
      children: [para('kp1', 'Left').block, para('kp2', 'Right').block],
    },
  },
  { v: 1, block: { kind: 'divider', id: 'd1' } },
  { v: 1, block: { kind: 'code', id: 'code1', language: 'kotlin', code: 'fun x() {\n  1\n}', editorHeightDp: 180, style: 'NONE' } },
]

assert.deepEqual(roundTripCanonical(doc, schema), doc)

console.log('PASS stage2b quote/callout/container/divider/code')
