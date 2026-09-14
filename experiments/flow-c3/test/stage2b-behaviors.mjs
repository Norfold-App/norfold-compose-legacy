import assert from 'node:assert/strict'
import { EditorState, TextSelection, NodeSelection } from 'prosemirror-state'
import { Schema } from 'prosemirror-model'
import { createCanonicalHistory } from '../src/canonical-history.js'
import { blockDocToPM, pmToBlockDoc } from '../src/block-document-projection.js'

const nodes = {
  doc: { content: 'block+' },
  paragraph: { content: 'inline*', group: 'block', attrs: { id: { default: null }, align: { default: 'start' }, style: { default: 'NONE' } } },
  text: { group: 'inline' },
  image_atom: { group: 'block', atom: true, selectable: true, attrs: { id: { default: null }, a11yName: { default: 'Image' }, source: { default: '' }, caption: { default: '' }, layout: { default: 'Fit' }, displayHeightDp: { default: 220 } } },
}
const schema = new Schema({ nodes, marks: { bold: {} } })

function harness(docJson) {
  let state = EditorState.create({ schema, doc: schema.nodeFromJSON(blockDocToPM(docJson, schema)) })
  const history = createCanonicalHistory({
    getSnapshot: () => JSON.stringify(pmToBlockDoc(state.doc, schema)),
    getSelection: () => {
      const sel = state.selection
      return sel instanceof NodeSelection
        ? { kind: 'node', from: sel.from, to: sel.to, empty: false, node: { type: sel.node.type.name, id: sel.node.attrs.id } }
        : { kind: sel.empty ? 'cursor' : 'text', from: sel.from, to: sel.to, empty: sel.empty, node: null }
    },
    restore: (snapshot, selection) => {
      state = EditorState.create({ schema, doc: schema.nodeFromJSON(blockDocToPM(snapshot, schema)) })
      if (selection.kind === 'node') {
        let pos = null
        state.doc.descendants((node, p) => {
          if (node.attrs && node.attrs.id === selection.node.id) { pos = p; return false }
        })
        state = state.apply(state.tr.setSelection(NodeSelection.create(state.doc, pos ?? 0)))
      } else {
        const from = Math.min(selection.from, state.doc.content.size)
        state = state.apply(state.tr.setSelection(TextSelection.create(state.doc, from)))
      }
    },
  })
  const api = {
    get state() { return state },
    dispatch(tr) { state = state.apply(tr); history.onTransaction(tr) },
    flush: () => history.flush(),
    undo: () => history.undo(),
    entries: () => history.info().entries,
    blockDoc: () => pmToBlockDoc(state.doc, schema),
  }
  return api
}

const para = (id, value) => ({ v: 1, block: { kind: 'paragraph', id, content: [{ kind: 'text', value }], align: 'start', style: 'NONE' } })
const img = (id, height = 220) => ({ v: 1, block: { kind: 'image', id, source: 'image://x', caption: 'Cap', layout: 'Fit', displayHeightDp: height } })

// 1. Enter/split: one paragraph -> two, single history entry, undo restores one.
{
  const h = harness([para('p1', 'HelloWorld')])
  const splitPos = 1 + 'Hello'.length
  h.dispatch(h.state.tr.split(splitPos))
  h.flush()
  assert.equal(h.entries(), 1)
  assert.deepEqual(h.blockDoc().map((e) => e.block.content.map((c) => c.value).join('')), ['Hello', 'World'])
  assert.deepEqual(h.undo(), { ok: true })
  assert.deepEqual(h.blockDoc(), [para('p1', 'HelloWorld')])
}

// 2. Backspace/merge across boundary: delete range spanning two paragraphs joins them.
{
  const h = harness([para('p1', 'Hello'), para('p2', 'World')])
  const endP1 = 1 + 'Hello'.length
  h.dispatch(h.state.tr.delete(endP1, endP1 + 2))
  h.flush()
  const merged = h.blockDoc()
  assert.equal(merged.length, 1)
  assert.equal(merged[0].block.content.map((c) => c.value).join(''), 'HelloWorld')
  assert.deepEqual(h.undo(), { ok: true })
  assert.equal(h.blockDoc().length, 2)
}

// 3. Cross-object text selection (B0.2): range over text->image->text leaves atoms intact.
{
  const h = harness([para('p1', 'Before'), img('img1'), para('p2', 'After')])
  const from = 2
  const to = h.state.doc.content.size - 2
  h.dispatch(h.state.tr.setSelection(TextSelection.create(h.state.doc, from, to)))
  assert.deepEqual(h.blockDoc().map((e) => e.block.kind), ['paragraph', 'image', 'paragraph'])
  assert.equal(h.blockDoc()[1].block.displayHeightDp, 220)
}

// 4. Mixed-range bold (A18.2): bold over text+image+text marks text only, atom untouched.
{
  const h = harness([para('p1', 'Before'), img('img1'), para('p2', 'After')])
  h.dispatch(h.state.tr.addMark(1, h.state.doc.content.size - 1, schema.marks.bold.create()))
  h.flush()
  const doc = h.blockDoc()
  assert.equal(doc[1].block.kind, 'image')
  assert.deepEqual(Object.keys(doc[1].block).sort(), ['caption', 'displayHeightDp', 'id', 'kind', 'layout', 'source'])
  const flatten = (nodes) => nodes.map((n) => (n.children ? flatten(n.children) : n.value)).join('')
  assert.ok(doc[0].block.content[0].kind === 'bold' && doc[2].block.content[0].kind === 'bold')
  assert.equal(flatten(doc[0].block.content), 'Before')
  assert.equal(flatten(doc[2].block.content), 'After')
}

// 5. Copy/cut/paste slice containing the atom reconstructs it with the same id.
{
  const h = harness([para('p1', 'Before'), img('img1'), para('p2', 'After')])
  h.dispatch(h.state.tr.setSelection(TextSelection.create(h.state.doc, 2, h.state.doc.content.size - 2)))
  const slice = h.state.selection.content()
  const h2 = harness([para('q1', 'Empty')])
  h2.dispatch(h2.state.tr.replaceSelection(slice))
  const pasted = h2.blockDoc()
  assert.ok(pasted.some((e) => e.block.kind === 'image' && e.block.id === 'img1'))
}

// 6. Resize image + undo restores height AND node selection (closes resize-undo-selection UNKNOWN).
{
  const h = harness([para('p1', 'Before'), img('img1'), para('p2', 'After')])
  let imgPos = null
  h.state.doc.descendants((node, pos) => {
    if (node.type.name === 'image_atom') { imgPos = pos; return false }
  })
  assert.notEqual(imgPos, null)
  h.dispatch(h.state.tr.setSelection(NodeSelection.create(h.state.doc, imgPos)))
  const node = h.state.doc.nodeAt(imgPos)
  h.dispatch(h.state.tr.setNodeMarkup(imgPos, undefined, { ...node.attrs, displayHeightDp: 300 }))
  h.dispatch(h.state.tr.setSelection(NodeSelection.create(h.state.doc, imgPos)))
  h.flush()
  assert.equal(h.entries(), 1)
  assert.equal(h.blockDoc()[1].block.displayHeightDp, 300)
  assert.deepEqual(h.undo(), { ok: true })
  assert.equal(h.blockDoc()[1].block.displayHeightDp, 220)
  assert.ok(h.state.selection instanceof NodeSelection)
  assert.equal(h.state.selection.node.attrs.id, 'img1')
}

console.log('PASS stage2b behaviors: split/merge, cross-object, mixed-format, clipboard, resize-undo-selection')
