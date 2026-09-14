import assert from 'node:assert/strict'
import { Schema } from 'prosemirror-model'
import { blockDocToPM, pmToBlockDoc, roundTripCanonical } from '../src/block-document-projection.js'

const nodes = {
  doc: { content: 'block+' },
  paragraph: { content: 'inline*', group: 'block', attrs: { id: { default: null }, align: { default: 'start' }, style: { default: 'NONE' } } },
  heading: { content: 'inline*', group: 'block', attrs: { id: { default: null }, level: { default: 1 }, align: { default: 'start' }, style: { default: 'NONE' } } },
  text: { group: 'inline' },
  emoji_inline: { group: 'inline', inline: true, atom: true, attrs: { shortcode: { default: '' }, unicode: { default: '' } } },
  math_inline: { group: 'inline', inline: true, atom: true, attrs: { tex: { default: '' } } },
  image_atom: { group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Image' }, source: { default: '' }, caption: { default: '' }, layout: { default: 'Fit' }, displayHeightDp: { default: 220 } } },
  table_atom: { group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Table' }, headers: { default: [] }, rows: { default: [] }, columnWidthsDp: { default: [] }, columnAlignments: { default: [] } } },
  chart_atom: { group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Chart' }, vegaLiteSpec: { default: '{}' }, editorHeightDp: { default: 180 } } },
  math_atom: { group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Formula' }, tex: { default: '' }, display: { default: true }, editorHeightDp: { default: 140 } } },
  unknown_atom: { group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Unknown block' }, rawJson: { default: '' } } },
}

const marks = {
  bold: {},
  italic: {},
  strike: {},
  underline: {},
  highlight: { attrs: { color: { default: null } } },
  font_family: { attrs: { family: { default: '' } } },
  font_size: { attrs: { sizeSp: { default: 0 } } },
  code: {},
  link: { attrs: { href: {} } },
  tag: {},
  mention: {},
}
const schema = new Schema({ nodes, marks })

const doc = [
  { v: 1, block: { kind: 'heading', id: 'h1', level: 2, content: [{ kind: 'text', value: 'Hostile ' }, { kind: 'italic', children: [{ kind: 'text', value: 'head' }] }], align: 'start', style: 'NONE' } },
  {
    v: 1, block: {
      kind: 'paragraph', id: 'p1', align: 'start', style: 'NONE', content: [
        { kind: 'text', value: 'plain ' },
        { kind: 'strike', children: [{ kind: 'text', value: 'struck' }] },
        { kind: 'text', value: ' ' },
        { kind: 'underline', children: [{ kind: 'text', value: 'under' }] },
        { kind: 'text', value: ' ' },
        { kind: 'highlight', color: '#ff0', children: [{ kind: 'text', value: 'lit' }] },
        { kind: 'text', value: ' ' },
        { kind: 'font_family', family: 'serif', children: [{ kind: 'text', value: 'fam' }] },
        { kind: 'text', value: ' ' },
        { kind: 'font_size', sizeSp: 20, children: [{ kind: 'text', value: 'big' }] },
        { kind: 'text', value: ' ' },
        { kind: 'code', value: 'x()' },
        { kind: 'text', value: ' ' },
        { kind: 'emoji', shortcode: 'smile', unicode: '😀' },
        { kind: 'text', value: ' ' },
        { kind: 'math', tex: 'a^2' },
      ],
    },
  },
]

assert.deepEqual(roundTripCanonical(doc, schema), doc)

const pm = schema.nodeFromJSON(blockDocToPM(doc, schema))
assert.equal(pm.child(0).type.name, 'heading')
assert.equal(pm.child(0).attrs.level, 2)

console.log('PASS stage2b inline marks + heading')
