import assert from 'node:assert/strict'
import { EditorState, TextSelection } from 'prosemirror-state'
import { Schema } from 'prosemirror-model'
import StarterKit from '@tiptap/starter-kit'
import { createCanonicalHistory } from '../src/canonical-history.js'
import { blockDocToPM, pmToBlockDoc } from '../src/block-document-projection.js'

const nodes = {
  doc: { content: 'block+' },
  paragraph: { content: 'inline*', group: 'block', attrs: { id: { default: null }, align: { default: 'start' }, style: { default: 'NONE' } } },
  text: { group: 'inline' },
}
const schema = new Schema({ nodes })
const documentA = [{ v: 1, block: { kind: 'paragraph', id: 'p1', content: [{ kind: 'text', value: 'A' }], align: 'start', style: 'NONE' } }]
let state = EditorState.create({
  schema,
  doc: schema.nodeFromJSON(blockDocToPM(documentA, schema)),
  selection: TextSelection.create(schema.nodeFromJSON(blockDocToPM(documentA, schema)), 1),
})
let restoredSelection = null
const history = createCanonicalHistory({
  getSnapshot: () => JSON.stringify(pmToBlockDoc(state.doc, schema)),
  getSelection: () => ({ kind: 'cursor', from: state.selection.from, to: state.selection.to, empty: state.selection.empty, node: null }),
  restore: (snapshot, selection) => {
    state = EditorState.create({ schema, doc: schema.nodeFromJSON(blockDocToPM(snapshot, schema)) })
    const from = Math.min(selection.from, state.doc.content.size)
    state = state.apply(state.tr.setSelection(TextSelection.create(state.doc, from)))
    restoredSelection = { ...selection, from }
  },
})

const starterExtensions = StarterKit.configure({ undoRedo: false }).config.addExtensions.call({ options: { undoRedo: false } })
assert.deepEqual(starterExtensions.filter((extension) => extension.name === 'history' || extension.name === 'undoRedo'), [])
assert.equal(history.info().entries, 0)

const selectionTransaction = state.tr.setSelection(TextSelection.create(state.doc, 2))
state = state.apply(selectionTransaction)
history.onTransaction(selectionTransaction)

const transaction = state.tr.insertText('B', 2)
transaction.setSelection(TextSelection.create(transaction.doc, 3))
state = state.apply(transaction)
history.onTransaction(transaction)
history.flush()

const documentB = pmToBlockDoc(state.doc, schema)
assert.deepEqual(documentB, [{ v: 1, block: { kind: 'paragraph', id: 'p1', content: [{ kind: 'text', value: 'AB' }], align: 'start', style: 'NONE' } }])
assert.equal(history.info().entries, 1)
assert.deepEqual(history.info().snapshots.map((snapshot) => JSON.parse(snapshot.blockDocJson)), [documentB])

assert.deepEqual(history.undo(), { ok: true })
assert.deepEqual(pmToBlockDoc(state.doc, schema), documentA)
assert.deepEqual(restoredSelection, { kind: 'cursor', from: 2, to: 2, empty: true, node: null })

assert.deepEqual(history.redo(), { ok: true })
assert.deepEqual(pmToBlockDoc(state.doc, schema), documentB)
assert.deepEqual(restoredSelection, { kind: 'cursor', from: 3, to: 3, empty: true, node: null })
assert.equal(history.info().entries, 1)
assert.equal(history.info().source, 'canonical-block-document')

console.log('PASS canonical history seam: one snapshot stack, undo/redo, selection, no Tiptap undoRedo')
