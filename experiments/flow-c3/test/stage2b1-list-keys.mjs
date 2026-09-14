import assert from 'node:assert/strict'
import { liftListItem } from '@tiptap/pm/schema-list'
import { Schema } from '@tiptap/pm/model'
import { EditorState, TextSelection } from '@tiptap/pm/state'
import { blockDocToPM, pmToBlockDoc } from '../src/block-document-projection.js'
import { uniqueBlockIdPlugin } from '../src/block-ids.js'
import { createCanonicalHistory } from '../src/canonical-history.js'
import { runListEnter } from '../src/list-key-behavior.js'

const schema = new Schema({
  nodes: {
    doc: { content: 'block+' },
    paragraph: {
      content: 'inline*',
      group: 'block',
      attrs: {
        id: { default: null },
        align: { default: 'start' },
        style: { default: 'NONE' },
      },
    },
    text: { group: 'inline' },
    bullet_list: {
      content: 'list_item+',
      group: 'block',
      attrs: { id: { default: null }, style: { default: 'NONE' } },
    },
    ordered_list: {
      content: 'list_item+',
      group: 'block',
      attrs: {
        id: { default: null },
        start: { default: 1 },
        style: { default: 'NONE' },
      },
    },
    list_item: {
      content: 'paragraph (bullet_list | ordered_list)?',
      attrs: { id: { default: null } },
      defining: true,
    },
  },
})

let generatedId = 0

function paragraph(id, text = '') {
  return {
    type: 'paragraph',
    attrs: { id, align: 'start', style: 'NONE' },
    content: text ? [{ type: 'text', text }] : undefined,
  }
}

function item(id, text, children = []) {
  return {
    type: 'list_item',
    attrs: { id },
    content: [paragraph(`${id}-p`, text), ...children],
  }
}

function list(type, id, items, start = 1) {
  return {
    type,
    attrs: type === 'ordered_list'
      ? { id, start, style: 'NONE' }
      : { id, style: 'NONE' },
    content: items,
  }
}

function doc(...content) {
  return { type: 'doc', content }
}

function createState(content) {
  return EditorState.create({
    schema,
    doc: schema.nodeFromJSON(content),
    plugins: [uniqueBlockIdPlugin(() => `generated-${++generatedId}`)],
  })
}

function setCursor(state, paragraphId, offset = 0) {
  let position = null
  state.doc.descendants((node, pos) => {
    if (node.type.name === 'paragraph' && node.attrs.id === paragraphId) {
      position = pos + 1 + offset
      return false
    }
    return undefined
  })
  assert.notEqual(position, null, `paragraph ${paragraphId} exists`)
  return state.apply(state.tr.setSelection(TextSelection.create(state.doc, position)))
}

function run(state, command) {
  let next = null
  assert.equal(command(state, (tr) => { next = state.apply(tr) }), true)
  assert.notEqual(next, null)
  return next
}

const editorContext = {
  extensionManager: {
    attributes: [
      ['paragraph', 'id'],
      ['paragraph', 'align'],
      ['paragraph', 'style'],
      ['bullet_list', 'id'],
      ['bullet_list', 'style'],
      ['ordered_list', 'id'],
      ['ordered_list', 'start'],
      ['ordered_list', 'style'],
      ['list_item', 'id'],
    ].map(([type, name]) => ({ type, name, attribute: { keepOnSplit: true } })),
    splittableMarks: [],
  },
}

function enter(state, dispatch) {
  const tr = state.tr
  let dispatched = false
  const handled = runListEnter({
    state,
    tr,
    dispatch: (next) => {
      dispatched = true
      dispatch?.(next)
    },
    editor: editorContext,
  })
  if (handled && !dispatched && tr.docChanged) dispatch?.(tr)
  return handled
}

function allIds(state) {
  const ids = []
  state.doc.descendants((node) => {
    if (node.attrs?.id) ids.push(node.attrs.id)
  })
  return ids
}

function assertUniqueIds(state) {
  const ids = allIds(state)
  assert.equal(new Set(ids).size, ids.length, JSON.stringify(ids))
}

function assertIdsInclude(state, expected) {
  const ids = new Set(allIds(state))
  for (const id of expected) assert.ok(ids.has(id), `${id} is preserved in ${JSON.stringify([...ids])}`)
}

function maxListDepth(state) {
  let max = 0
  state.doc.descendants((node, pos) => {
    if (node.type.name !== 'bullet_list' && node.type.name !== 'ordered_list') return
    const $pos = state.doc.resolve(pos)
    let depth = 1
    for (let d = 0; d <= $pos.depth; d += 1) {
      const ancestor = $pos.node(d)
      if (ancestor.type.name === 'bullet_list' || ancestor.type.name === 'ordered_list') depth += 1
    }
    max = Math.max(max, depth)
  })
  return max
}

function rootTypes(state) {
  return state.doc.toJSON().content.map((node) => node.type)
}

function listTexts(state) {
  const texts = []
  state.doc.descendants((node) => {
    if (node.type.name === 'list_item') texts.push(node.textContent)
  })
  return texts
}

const backspaceAtItemStart = liftListItem(schema.nodes.list_item)

for (const { type, start } of [
  { type: 'bullet_list', start: 1 },
  { type: 'ordered_list', start: 3 },
]) {
  // Removing the split command would leave one item and duplicate identity on split.
  {
    let state = createState(doc(list(type, `${type}-root`, [item(`${type}-one`, 'Alpha')], start)))
    state = setCursor(state, `${type}-one-p`, 5)
    state = run(state, enter)

    const root = state.doc.toJSON().content[0]
    assert.equal(root.type, type)
    assert.equal(root.content.length, 2)
    assert.deepEqual(listTexts(state), ['Alpha', ''])
    if (type === 'ordered_list') assert.equal(root.attrs.start, 3)
    assertUniqueIds(state)
    assertIdsInclude(state, [`${type}-root`, `${type}-one`, `${type}-one-p`])
  }

  // Removing nested split handling would skip directly to prose or do nothing.
  {
    const nested = list(type, `${type}-nested`, [item(`${type}-child`, '')], start + 4)
    let state = createState(doc(list(type, `${type}-root`, [item(`${type}-parent`, 'Parent', [nested])], start)))
    assert.equal(maxListDepth(state), 2)
    state = setCursor(state, `${type}-child-p`)
    state = run(state, enter)

    assert.equal(maxListDepth(state), 1)
    assert.deepEqual(rootTypes(state), [type])
    assert.equal(state.doc.toJSON().content[0].content.length, 2)
    if (type === 'ordered_list') assert.equal(state.doc.toJSON().content[0].attrs.start, 3)
    assertUniqueIds(state)
    assertIdsInclude(state, [
      `${type}-root`,
      `${type}-parent`,
      `${type}-parent-p`,
      `${type}-child`,
      `${type}-child-p`,
    ])
  }

  // Removing the lift-empty fallback would strand an empty outer list item.
  {
    let state = createState(doc(list(type, `${type}-root`, [item(`${type}-empty`, '')], start)))
    state = setCursor(state, `${type}-empty-p`)
    state = run(state, enter)

    assert.deepEqual(rootTypes(state), ['paragraph'])
    assertUniqueIds(state)
    assertIdsInclude(state, [`${type}-empty-p`])
  }

  // Tiptap's list Backspace route ultimately lifts/joins the current item.
  {
    let state = createState(doc(list(type, `${type}-root`, [
      item(`${type}-one`, 'One'),
      item(`${type}-two`, 'Two'),
    ], start)))
    state = setCursor(state, `${type}-two-p`)
    state = run(state, backspaceAtItemStart)

    assert.equal(state.doc.textContent, 'OneTwo')
    assert.deepEqual(rootTypes(state), [type, 'paragraph'])
    assert.deepEqual(listTexts(state), ['One'])
    if (type === 'ordered_list') assert.equal(state.doc.toJSON().content[0].attrs.start, 3)
    assertUniqueIds(state)
    assertIdsInclude(state, [
      `${type}-root`,
      `${type}-one`,
      `${type}-one-p`,
      `${type}-two-p`,
    ])
  }
}

// Removing canonical history integration would not restore this structural split.
{
  let state = createState(doc(list('ordered_list', 'history-root', [item('history-one', 'Alpha')], 3)))
  const history = createCanonicalHistory({
    getSnapshot: () => JSON.stringify(pmToBlockDoc(state.doc, schema)),
    getSelection: () => ({
      kind: 'cursor',
      from: state.selection.from,
      to: state.selection.to,
      empty: true,
      node: null,
    }),
    restore: (snapshot, selection) => {
      state = EditorState.create({
        schema,
        doc: schema.nodeFromJSON(blockDocToPM(snapshot, schema)),
        plugins: state.plugins,
      })
      state = state.apply(state.tr.setSelection(TextSelection.create(
        state.doc,
        Math.min(selection.from, state.doc.content.size),
      )))
    },
  })
  const baseline = JSON.stringify(pmToBlockDoc(state.doc, schema))
  state = setCursor(state, 'history-one-p', 5)
  let transaction = null
  assert.equal(enter(state, (tr) => { transaction = tr }), true)
  state = state.apply(transaction)
  history.onTransaction(transaction)
  history.flush()
  const split = JSON.stringify(pmToBlockDoc(state.doc, schema))

  assert.notEqual(split, baseline)
  assert.equal(history.info().entries, 1)
  assert.deepEqual(history.undo(), { ok: true })
  assert.equal(JSON.stringify(pmToBlockDoc(state.doc, schema)), baseline)
  assert.deepEqual(history.redo(), { ok: true })
  assert.equal(JSON.stringify(pmToBlockDoc(state.doc, schema)), split)
  assertUniqueIds(state)
}

console.log('PASS Stage-2B.1 list Enter/outdent/exit, Backspace, history, and stable IDs')
