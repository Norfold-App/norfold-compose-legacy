import assert from 'node:assert/strict'
import { EditorState, TextSelection } from 'prosemirror-state'
import { Schema } from 'prosemirror-model'
import { uniqueBlockIdPlugin } from '../src/block-ids.js'
import { blockDocToPM, pmToBlockDoc } from '../src/block-document-projection.js'

const nodes = {
  doc: { content: 'block+' },
  paragraph: { content: 'inline*', group: 'block', attrs: { id: { default: null }, align: { default: 'start' }, style: { default: 'NONE' } } },
  text: { group: 'inline' },
  image_atom: { group: 'block', atom: true, selectable: true, attrs: { id: { default: null }, a11yName: { default: 'Image' }, source: { default: '' }, caption: { default: '' }, layout: { default: 'Fit' }, displayHeightDp: { default: 220 } } },
}
const schema = new Schema({ nodes })

const para = (id, value) => ({ v: 1, block: { kind: 'paragraph', id, content: [{ kind: 'text', value }], align: 'start', style: 'NONE' } })

// Split reidentifies the second half: ids stay unique (B1.6 / A6.1).
{
  const doc = [para('p1', 'HelloWorld')]
  let state = EditorState.create({
    schema,
    doc: schema.nodeFromJSON(blockDocToPM(doc, schema)),
    plugins: [uniqueBlockIdPlugin(() => 'fresh-id')],
  })
  state = state.apply(state.tr.split(1 + 5))
  const ids = pmToBlockDoc(state.doc, schema).map((e) => e.block.id)
  assert.deepEqual(ids, ['p1', 'fresh-id'])
}

// Same-document paste of an atom reidentifies the copy; cross-document paste preserves identity.
{
  const mk = (plugins = []) => {
    const base = [para('p1', 'Before'), { v: 1, block: { kind: 'image', id: 'img1', source: 'image://x', caption: 'Cap', layout: 'Fit', displayHeightDp: 220 } }, para('p2', 'After')]
    return EditorState.create({ schema, doc: schema.nodeFromJSON(blockDocToPM(base, schema)), plugins })
  }
  let counter = 0
  let state = mk([uniqueBlockIdPlugin(() => `copy-${++counter}`)])
  state = state.apply(state.tr.setSelection(TextSelection.create(state.doc, 2, state.doc.content.size - 2)))
  const slice = state.selection.content()
  state = state.apply(state.tr.setSelection(TextSelection.create(state.doc, state.doc.content.size - 1)))
  state = state.apply(state.tr.replaceSelection(slice))
  const ids = []
  state.doc.descendants((node) => {
    if (node.type.name === 'image_atom') ids.push(node.attrs.id)
  })
  assert.deepEqual(ids, ['img1', 'copy-1'])
}

// Untouched document: plugin is a no-op (returns null, no extra transaction).
{
  const doc = [para('p1', 'A'), para('p2', 'B')]
  const state = EditorState.create({
    schema,
    doc: schema.nodeFromJSON(blockDocToPM(doc, schema)),
    plugins: [uniqueBlockIdPlugin(() => { throw new Error('must not generate') })],
  })
  const next = state.apply(state.tr.setSelection(TextSelection.create(state.doc, 2)))
  assert.equal(next.doc, state.doc)
}

console.log('PASS stage2b identity: split/paste reidentify duplicates, quiet otherwise')
