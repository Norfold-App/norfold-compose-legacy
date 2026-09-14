import assert from 'node:assert/strict'
import { Schema } from 'prosemirror-model'
import { blockDocToPM, pmToBlockDoc, roundTripCanonical } from '../src/block-document-projection.js'

const nodes = {
  doc: { content: 'block+' },
  paragraph: { content: 'inline*', group: 'block', attrs: { id: { default: null }, align: { default: 'start' }, style: { default: 'NONE' } } },
  text: { group: 'inline' },
  image_atom: { group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Image' }, source: { default: '' }, caption: { default: '' }, layout: { default: 'Fit' }, displayHeightDp: { default: 220 } } },
  table_atom: { group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Table' }, headers: { default: [] }, rows: { default: [] }, columnWidthsDp: { default: [] }, columnAlignments: { default: [] } } },
  chart_atom: { group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Chart' }, vegaLiteSpec: { default: '{}' }, editorHeightDp: { default: 180 } } },
  math_atom: { group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Formula' }, tex: { default: '' }, display: { default: true }, editorHeightDp: { default: 140 } } },
  unknown_atom: { group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Unknown block' }, rawJson: { default: '' } } },
}

const marks = {
  bold: {},
  link: { attrs: { href: {} } },
  tag: {},
  mention: {},
}
const schema = new Schema({ nodes, marks })

const fixture = [
  { v: 1, block: { kind: 'paragraph', id: 'p1', content: [{ kind: 'text', value: 'Hello ' }, { kind: 'bold', children: [{ kind: 'text', value: 'bold' }] }, { kind: 'text', value: ' ' }, { kind: 'link', url: 'https://example.test', children: [{ kind: 'text', value: 'link' }] }, { kind: 'text', value: ' ' }, { kind: 'tag', value: 'flow' }, { kind: 'text', value: ' ' }, { kind: 'mention', value: 'qa' }], align: 'center', style: 'BODY' } },
  { v: 1, block: { kind: 'image', id: 'img1', source: 'image://one', caption: 'One', layout: 'Wide', displayHeightDp: 321 } },
  { v: 1, block: { kind: 'table', id: 'table1', headers: [{ content: [{ kind: 'text', value: 'H1' }] }, { content: [{ kind: 'bold', children: [{ kind: 'text', value: 'H2' }] }] }], rows: [[{ content: [{ kind: 'text', value: 'A1' }] }, { content: [{ kind: 'link', url: 'https://cell.test', children: [{ kind: 'text', value: 'A2' }] }] }]], columnWidthsDp: [100, 200], columnAlignments: ['Start', 'End'] } },
  { v: 1, block: { kind: 'chart', id: 'chart1', vegaLiteSpec: '{"mark":"bar","data":{"values":[{"x":1}]}}', editorHeightDp: 456 } },
  { v: 1, block: { kind: 'math', id: 'math1', tex: 'E = mc^2', display: true, editorHeightDp: 140 } },
  { v: 1, block: { kind: 'unknown', id: 'unknown1', rawJson: '{ "kind": "future", "data": [1, {"x":true}] }' } },
  { v: 1, block: { kind: 'paragraph', id: 'p2', content: [{ kind: 'text', value: 'Last' }], align: 'start', style: 'NONE' } },
]

assert.deepEqual(roundTripCanonical(fixture, schema), fixture)

const pm = schema.nodeFromJSON(blockDocToPM(fixture, schema))
assert.equal(pm.child(1).attrs.a11yName, 'Image: One')
assert.equal(pm.child(2).attrs.a11yName, 'Table: H1, H2; 1 row by 2 columns')
assert.equal(pm.child(3).attrs.a11yName, 'Chart: x 1')
assert.equal(pm.child(4).attrs.a11yName, 'Formula: E = mc^2')
const inserted = schema.nodes.paragraph.create({ id: 'p-new', align: 'start', style: 'NONE' }, [schema.text('Inserted')])
const boldLink = schema.text('edited', [schema.marks.bold.create(), schema.marks.link.create({ href: 'https://edit.test' })])
const originalChildren = []
pm.forEach((node) => originalChildren.push(node))
const first = originalChildren[0]
const markedFirst = first.copy(first.content.replaceChild(0, boldLink))
const reordered = schema.nodes.doc.create(null, [originalChildren[6], ...originalChildren.slice(1, 6), markedFirst, inserted])
const result = pmToBlockDoc(reordered, schema)
assert.deepEqual(result, [
  { v: 1, block: { kind: 'paragraph', id: 'p2', content: [{ kind: 'text', value: 'Last' }], align: 'start', style: 'NONE' } },
  { v: 1, block: { kind: 'image', id: 'img1', source: 'image://one', caption: 'One', layout: 'Wide', displayHeightDp: 321 } },
  { v: 1, block: { kind: 'table', id: 'table1', headers: [{ content: [{ kind: 'text', value: 'H1' }] }, { content: [{ kind: 'bold', children: [{ kind: 'text', value: 'H2' }] }] }], rows: [[{ content: [{ kind: 'text', value: 'A1' }] }, { content: [{ kind: 'link', url: 'https://cell.test', children: [{ kind: 'text', value: 'A2' }] }] }]], columnWidthsDp: [100, 200], columnAlignments: ['Start', 'End'] } },
  { v: 1, block: { kind: 'chart', id: 'chart1', vegaLiteSpec: '{"mark":"bar","data":{"values":[{"x":1}]}}', editorHeightDp: 456 } },
  { v: 1, block: { kind: 'math', id: 'math1', tex: 'E = mc^2', display: true, editorHeightDp: 140 } },
  { v: 1, block: { kind: 'unknown', id: 'unknown1', rawJson: '{ "kind": "future", "data": [1, {"x":true}] }' } },
  { v: 1, block: { kind: 'paragraph', id: 'p1', content: [{ kind: 'bold', children: [{ kind: 'link', url: 'https://edit.test', children: [{ kind: 'text', value: 'edited' }] }] }, { kind: 'bold', children: [{ kind: 'text', value: 'bold' }] }, { kind: 'text', value: ' ' }, { kind: 'link', url: 'https://example.test', children: [{ kind: 'text', value: 'link' }] }, { kind: 'text', value: ' ' }, { kind: 'tag', value: 'flow' }, { kind: 'text', value: ' ' }, { kind: 'mention', value: 'qa' }], align: 'center', style: 'BODY' } },
  { v: 1, block: { kind: 'paragraph', id: 'p-new', content: [{ kind: 'text', value: 'Inserted' }], align: 'start', style: 'NONE' } },
])

console.log('PASS projection round-trip and representative edits')
