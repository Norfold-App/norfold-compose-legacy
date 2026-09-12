// FLOW-03 C3 Stage-1 spike. Real ProseMirror/Tiptap document: image/table/chart
// are explicit atomic block nodes with stable Norfold IDs in attributes, NOT
// U+FFFC characters. Exposes window.C3 for a persistent Android WebView host and
// reports compact events (no whole-document JSON per keystroke) via Norfold.onEvent.
import { Editor, Node, Mark, mergeAttributes } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { NodeSelection } from '@tiptap/pm/state'

const Atom = (name, label) =>
  Node.create({
    name,
    group: 'block',
    atom: true,
    selectable: true,
    draggable: true,
    addAttributes() {
      return { id: { default: null }, a11yName: { default: label } }
    },
    parseHTML() {
      return [{ tag: `div[data-atom="${name}"]` }]
    },
    renderHTML({ HTMLAttributes }) {
      return [
        'div',
        mergeAttributes(HTMLAttributes, {
          'data-atom': name,
          class: `c3-atom c3-${name}`,
          'aria-label': HTMLAttributes.a11yName,
          role: 'img',
          tabindex: '0',
        }),
        `\u27E6${name}:${HTMLAttributes.id}\u27E7`,
      ]
    },
  })

const Tag = Mark.create({
  name: 'tag',
  parseHTML() {
    return [{ tag: 'span[data-tag]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes, { class: 'c3-tag', 'data-tag': '' }), 0]
  },
})

const Mention = Mark.create({
  name: 'mention',
  parseHTML() {
    return [{ tag: 'span[data-mention]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes, { class: 'c3-mention', 'data-mention': '' }), 0]
  },
})

function fixtureDoc() {
  return {
    type: 'doc',
    content: [
      {
        type: 'paragraph',
        content: [
          { type: 'text', text: 'Alpha paragraph with ' },
          { type: 'text', marks: [{ type: 'bold' }], text: 'bold' },
          { type: 'text', text: ' and ' },
          {
            type: 'text',
            marks: [{ type: 'link', attrs: { href: 'https://example.com/flow-stage1' } }],
            text: 'link',
          },
          { type: 'text', text: '.' },
        ],
      },
      { type: 'paragraph', content: [{ type: 'text', text: 'Text before image.' }] },
      { type: 'image_atom', attrs: { id: 'img1', a11yName: 'Stage-1 fixture image' } },
      { type: 'paragraph', content: [{ type: 'text', text: 'Text after image.' }] },
      { type: 'table_atom', attrs: { id: 'tbl1', a11yName: 'Stage-1 table, 2 by 2' } },
      { type: 'paragraph', content: [{ type: 'text', text: 'Paragraph after table.' }] },
      { type: 'chart_atom', attrs: { id: 'chart1', a11yName: 'Stage-1 chart' } },
      {
        type: 'paragraph',
        content: [
          { type: 'text', text: 'See ' },
          { type: 'text', marks: [{ type: 'tag' }], text: '#stage1' },
          { type: 'text', text: ' and ' },
          { type: 'text', marks: [{ type: 'mention' }], text: '@qa' },
          { type: 'text', text: ' for context.' },
        ],
      },
      { type: 'paragraph', content: [{ type: 'text', text: 'Final paragraph.' }] },
    ],
  }
}

let editor = null
let internalClipboard = null

function evt(o) {
  try {
    window.Norfold && window.Norfold.onEvent(JSON.stringify(o))
  } catch (e) {
    /* bridge absent */
  }
}
function log(m) {
  evt({ t: 'log', m })
  try {
    window.__uiLog && window.__uiLog(m)
  } catch (e) {
    /* no ui */
  }
}

function findAtom(id) {
  let hit = null
  editor.state.doc.descendants((node, pos) => {
    if (node.attrs && node.attrs.id === id && node.type.name.endsWith('_atom')) {
      hit = { pos, node }
      return false
    }
  })
  return hit
}

function selectionInfo() {
  const sel = editor.state.selection
  const node = sel.node
  return {
    kind: sel instanceof NodeSelection ? 'node' : 'text',
    from: sel.from,
    to: sel.to,
    empty: sel.empty,
    node: node ? { type: node.type.name, id: node.attrs.id } : null,
    text: sel.empty ? '' : editor.state.doc.textBetween(sel.from, sel.to, '\n', '\uFFFC'),
  }
}

function outline() {
  const lines = []
  editor.state.doc.descendants((node, pos) => {
    if (node.type.name.endsWith('_atom')) {
      lines.push(`${node.type.name}#${node.attrs.id} @${pos}`)
    } else if (node.isTextblock) {
      lines.push(`${node.type.name}: "${node.textContent}"`)
    }
  })
  return lines
}

function attach() {
  editor.on('transaction', ({ transaction }) => {
    if (transaction.docChanged) {
      evt({ t: 'doc', steps: transaction.steps.length, docSize: editor.state.doc.nodeSize })
    }
    if (transaction.docChanged || transaction.selectionSet) {
      evt({ t: 'sel', sel: selectionInfo() })
    }
  })
  const dom = editor.view.dom
  dom.addEventListener('compositionstart', () => evt({ t: 'comp', phase: 'start' }))
  dom.addEventListener('compositionupdate', (e) => evt({ t: 'comp', phase: 'update', data: e.data }))
  dom.addEventListener('compositionend', (e) => evt({ t: 'comp', phase: 'end', data: e.data }))
}

window.C3 = {
  mount(el) {
    editor = new Editor({
      element: el,
      extensions: [
        StarterKit,
        Atom('image_atom', 'Stage-1 fixture image'),
        Atom('table_atom', 'Stage-1 table, 2 by 2'),
        Atom('chart_atom', 'Stage-1 chart'),
        Tag,
        Mention,
      ],
      content: fixtureDoc(),
      onCreate: () => log('editor-created'),
    })
    attach()
    log('mounted')
  },
  focus() {
    editor.commands.focus()
  },
  selectAcrossAtom(id) {
    const f = findAtom(id)
    if (!f) return log('atom-not-found ' + id)
    const from = Math.max(1, f.pos - 6)
    const to = Math.min(editor.state.doc.content.size, f.pos + f.node.nodeSize + 6)
    editor.commands.setTextSelection({ from, to })
    log('text-selection ' + from + '-' + to)
  },
  selectNode(id) {
    const f = findAtom(id)
    if (!f) return log('atom-not-found ' + id)
    editor.commands.setNodeSelection(f.pos)
    log('node-selection ' + id + ' @' + f.pos)
  },
  copyInternal() {
    internalClipboard = editor.state.selection.content()
    log('copy-internal size=' + internalClipboard.size + ' kind=' + selectionInfo().kind)
  },
  pasteInternal() {
    if (!internalClipboard) return log('internal-clipboard-empty')
    const tr = editor.state.tr.replaceSelection(internalClipboard).scrollIntoView()
    editor.view.dispatch(tr)
    log('paste-internal ok')
  },
  cutInternal() {
    internalClipboard = editor.state.selection.content()
    editor.commands.deleteSelection()
    log('cut-internal size=' + internalClipboard.size)
  },
  systemCopy() {
    editor.commands.focus()
    try {
      const ok = document.execCommand('copy')
      log('system-copy execCommand=' + ok)
    } catch (e) {
      log('system-copy error ' + e)
    }
  },
  selectAll() {
    editor.commands.selectAll()
    log('select-all')
  },
  report() {
    const info = { sel: selectionInfo(), outline: outline(), docSize: editor.state.doc.nodeSize }
    log('report ' + JSON.stringify(info.sel))
    return info
  },
}
