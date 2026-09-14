// FLOW-03 C3 Stage-1 spike. Real ProseMirror/Tiptap document: image/table/chart
// are explicit atomic block nodes with stable Norfold IDs in attributes, NOT
// U+FFFC characters. Exposes window.C3 for a persistent Android WebView host and
// reports compact events (no whole-document JSON per keystroke) via Norfold.onEvent.
import { Editor, Node, Mark, mergeAttributes } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import ParagraphNode from '@tiptap/extension-paragraph'
import { NodeSelection } from '@tiptap/pm/state'
import { blockDocToPM as projectBlockDocToPM, blockToPM, pmToBlockDoc as projectPMToBlockDoc } from './block-document-projection.js'
import { createCanonicalHistory } from './canonical-history.js'
import { chartValues, resizedHeight } from './owner-review-model.js'

const Atom = (name, label) =>
  Node.create({
    name,
    group: 'block',
    atom: true,
    selectable: true,
    draggable: true,
    addAttributes() {
      const attributes = { id: { default: null }, a11yName: { default: label } }
      if (name === 'image_atom') {
        Object.assign(attributes, {
          source: { default: '' },
          caption: { default: '' },
          layout: { default: 'Fit' },
          displayHeightDp: { default: 220 },
        })
      } else if (name === 'table_atom') {
        Object.assign(attributes, {
          headers: { default: [] },
          rows: { default: [] },
          columnWidthsDp: { default: [] },
          columnAlignments: { default: [] },
        })
      } else if (name === 'chart_atom') {
        Object.assign(attributes, {
          vegaLiteSpec: { default: '{}' },
          editorHeightDp: { default: 180 },
        })
      } else if (name === 'math_atom') {
        Object.assign(attributes, {
          tex: { default: '' },
          display: { default: true },
          editorHeightDp: { default: 140 },
        })
      }
      return attributes
    },
    addNodeView() {
      return ({ node, editor, getPos }) => createAtomNodeView(node, editor, getPos)
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
          role: name === 'table_atom' ? 'table' : name === 'math_atom' ? 'math' : 'img',
          tabindex: '0',
        }),
        `\u27E6${name}:${HTMLAttributes.id}\u27E7`,
      ]
    },
  })

function inlineText(nodes = []) {
  return nodes.map((node) => {
    if (node.kind === 'text') return node.value
    if (node.children) return inlineText(node.children)
    return node.value || ''
  }).join('')
}

function clearChildren(element) {
  while (element.firstChild) element.removeChild(element.firstChild)
}

function createAtomNodeView(initialNode, editor, getPos) {
  let currentNode = initialNode
  let selected = false
  let resizeSession = null
  const dom = document.createElement('div')

  function setMetadata(node) {
    const type = node.type.name
    const id = node.attrs.id || ''
    const name = node.attrs.a11yName || type
    dom.className = `c3-atom c3-${type}${selected ? ' ProseMirror-selectednode' : ''}`
    dom.dataset.atomType = type
    dom.dataset.atomId = id
    dom.dataset.atomName = name
    dom.setAttribute('role', type === 'image_atom' || type === 'chart_atom' ? 'img' : type === 'table_atom' ? 'table' : type === 'math_atom' ? 'math' : 'group')
    dom.setAttribute('aria-label', name)
    dom.setAttribute('aria-roledescription', 'atomic object')
    if (selected) dom.setAttribute('aria-selected', 'true')
    else dom.removeAttribute('aria-selected')
    dom.tabIndex = 0
  }

  function renderImage(node) {
    const image = document.createElement('img')
    image.alt = node.attrs.a11yName || 'Image'
    image.src = node.attrs.source || 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="240" height="120"><rect width="240" height="120" fill="%23e5e7eb"/><text x="120" y="65" text-anchor="middle" fill="%236b7280">Image</text></svg>'
    image.style.height = `${node.attrs.displayHeightDp}px`
    const figure = document.createElement('figure')
    figure.appendChild(image)
    if (node.attrs.caption) {
      const caption = document.createElement('figcaption')
      caption.textContent = node.attrs.caption
      figure.appendChild(caption)
    }
    dom.appendChild(figure)
  }

  function renderTable(node) {
    const table = document.createElement('table')
    table.setAttribute('aria-hidden', 'true')
    const head = document.createElement('thead')
    const headerRow = document.createElement('tr')
    for (const cell of node.attrs.headers || []) {
      const th = document.createElement('th')
      th.textContent = inlineText(cell.content)
      headerRow.appendChild(th)
    }
    if (headerRow.childNodes.length) head.appendChild(headerRow)
    table.appendChild(head)
    const body = document.createElement('tbody')
    for (const row of node.attrs.rows || []) {
      const tr = document.createElement('tr')
      for (const cell of row) {
        const td = document.createElement('td')
        td.textContent = inlineText(cell.content)
        tr.appendChild(td)
      }
      body.appendChild(tr)
    }
    table.appendChild(body)
    dom.appendChild(table)
  }

  function renderChart(node) {
    const values = chartValues(node.attrs.vegaLiteSpec)
    const figure = document.createElement('figure')
    const caption = document.createElement('figcaption')
    caption.textContent = 'Monthly activity'
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    svg.setAttribute('viewBox', '0 0 640 260')
    svg.setAttribute('aria-hidden', 'true')
    const maxValue = Math.max(1, ...values.map((entry) => entry.value))
    const slot = 560 / Math.max(1, values.length)
    values.forEach((entry, index) => {
      const height = (entry.value / maxValue) * 176
      const bar = document.createElementNS('http://www.w3.org/2000/svg', 'rect')
      bar.setAttribute('x', String(48 + index * slot + slot * 0.18))
      bar.setAttribute('y', String(206 - height))
      bar.setAttribute('width', String(slot * 0.64))
      bar.setAttribute('height', String(height))
      bar.setAttribute('rx', '4')
      const label = document.createElementNS('http://www.w3.org/2000/svg', 'text')
      label.setAttribute('x', String(48 + index * slot + slot * 0.5))
      label.setAttribute('y', '236')
      label.setAttribute('text-anchor', 'middle')
      label.textContent = entry.label
      svg.append(bar, label)
    })
    figure.append(caption, svg)
    dom.appendChild(figure)
  }

  function renderMath(node) {
    const expression = document.createElement('div')
    expression.className = 'c3-formula-expression'
    const parts = String(node.attrs.tex || '').split(/\^(\S+)/, 2)
    expression.append(document.createTextNode(parts[0]))
    if (parts[1]) {
      const superscript = document.createElement('sup')
      superscript.textContent = parts[1]
      expression.appendChild(superscript)
    }
    dom.appendChild(expression)
  }

  function updateHeight(height) {
    const position = getPos()
    if (typeof position !== 'number') return
    editor.view.dispatch(editor.state.tr.setNodeMarkup(position, undefined, {
      ...currentNode.attrs,
      displayHeightDp: height,
    }))
  }

  function endResize() {
    resizeSession = null
    window.removeEventListener('pointermove', onResizeMove)
    window.removeEventListener('pointerup', endResize)
    window.removeEventListener('pointercancel', endResize)
  }

  function onResizeMove(event) {
    if (!resizeSession || event.pointerId !== resizeSession.pointerId) return
    event.preventDefault()
    updateHeight(resizedHeight(resizeSession.height, event.clientY - resizeSession.y))
  }

  function createResizeHandle(node) {
    const handle = document.createElement('div')
    handle.className = 'c3-resize-handle'
    handle.hidden = !selected
    handle.tabIndex = 0
    handle.setAttribute('role', 'slider')
    handle.setAttribute('aria-label', 'Resize image')
    handle.setAttribute('aria-valuemin', '120')
    handle.setAttribute('aria-valuemax', '420')
    handle.setAttribute('aria-valuenow', String(node.attrs.displayHeightDp))
    handle.setAttribute('aria-valuetext', `${node.attrs.displayHeightDp} pixels high`)
    handle.addEventListener('pointerdown', (event) => {
      event.preventDefault()
      event.stopPropagation()
      resizeSession = { pointerId: event.pointerId, y: event.clientY, height: currentNode.attrs.displayHeightDp }
      window.addEventListener('pointermove', onResizeMove, { passive: false })
      window.addEventListener('pointerup', endResize)
      window.addEventListener('pointercancel', endResize)
    })
    handle.addEventListener('keydown', (event) => {
      if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return
      event.preventDefault()
      event.stopPropagation()
      updateHeight(resizedHeight(currentNode.attrs.displayHeightDp, event.key === 'ArrowUp' ? -8 : 8))
    })
    dom.appendChild(handle)
  }

  function createChartEditAction(node) {
    const action = document.createElement('button')
    action.className = 'c3-object-action'
    action.hidden = !selected
    action.type = 'button'
    action.textContent = 'Edit chart'
    action.setAttribute('aria-label', `Edit ${node.attrs.a11yName || 'chart'}`)
    action.addEventListener('click', (event) => {
      event.preventDefault()
      event.stopPropagation()
      evt({ t: 'object-edit', type: node.type.name, id: node.attrs.id })
    })
    dom.appendChild(action)
  }

  function render(node) {
    clearChildren(dom)
    setMetadata(node)
    if (node.type.name === 'image_atom') renderImage(node)
    else if (node.type.name === 'table_atom') renderTable(node)
    else if (node.type.name === 'chart_atom') renderChart(node)
    else if (node.type.name === 'math_atom') renderMath(node)
    else {
      const raw = document.createElement('pre')
      raw.textContent = node.attrs.rawJson || ''
      dom.appendChild(raw)
    }
    if (node.type.name === 'image_atom') createResizeHandle(node)
    if (node.type.name === 'chart_atom') createChartEditAction(node)
  }

  function selectAtom(event) {
    event.preventDefault()
    const position = getPos()
    if (position !== undefined) editor.commands.setNodeSelection(position)
  }

  function activateAtom(event) {
    if (event.key !== 'Enter' && event.key !== ' ') return
    selectAtom(event)
  }

  render(currentNode)
  dom.addEventListener('click', selectAtom)
  dom.addEventListener('keydown', activateAtom)
  return {
    dom,
    update(node) {
      if (node.type !== currentNode.type) return false
      currentNode = node
      render(node)
      return true
    },
    selectNode() {
      selected = true
      dom.classList.add('ProseMirror-selectednode')
      dom.setAttribute('aria-selected', 'true')
      dom.querySelectorAll('.c3-resize-handle, .c3-object-action').forEach((control) => { control.hidden = false })
    },
    deselectNode() {
      selected = false
      dom.classList.remove('ProseMirror-selectednode')
      dom.removeAttribute('aria-selected')
      dom.querySelectorAll('.c3-resize-handle, .c3-object-action').forEach((control) => { control.hidden = true })
    },
    stopEvent() {
      return true
    },
    ignoreMutation() {
      return true
    },
    destroy() {
      endResize()
      dom.removeEventListener('click', selectAtom)
      dom.removeEventListener('keydown', activateAtom)
      clearChildren(dom)
    },
  }
}

const REVIEW_IMAGE_SOURCE = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(`
  <svg xmlns="http://www.w3.org/2000/svg" width="960" height="624" viewBox="0 0 960 624">
    <defs><linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#dbe9ed"/><stop offset="1" stop-color="#f7eee1"/></linearGradient></defs>
    <rect width="960" height="624" fill="url(#sky)"/><circle cx="730" cy="154" r="70" fill="#f0bf68"/>
    <path d="M0 456 235 218l174 170 120-108 431 344H0Z" fill="#617873"/><path d="M0 510 252 303l178 156 135-102 395 267H0Z" fill="#334842"/>
    <path d="M0 542c174-42 316-35 474 11 153 45 302 48 486-5v76H0Z" fill="#dae1d5"/>
  </svg>`)

const UnknownAtom = Node.create({
  name: 'unknown_atom',
  group: 'block',
  atom: true,
  selectable: true,
  draggable: true,
  addAttributes() {
    return {
      id: { default: null },
      a11yName: { default: 'Unknown block' },
      rawJson: { default: '' },
    }
  },
  parseHTML() {
    return [{ tag: 'div[data-atom="unknown_atom"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        'data-atom': 'unknown_atom',
        class: 'c3-atom c3-unknown_atom',
        'aria-label': HTMLAttributes.a11yName,
        role: 'group',
        tabindex: '0',
      }),
      `\u27E6unknown:${HTMLAttributes.id}\u27E7`,
    ]
  },
})

const Paragraph = ParagraphNode.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      id: { default: null },
      align: { default: 'start' },
      style: { default: 'NONE' },
    }
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
      blockToPM({ kind: 'image', id: 'img1', source: REVIEW_IMAGE_SOURCE, caption: 'Morning study', layout: 'Fit', displayHeightDp: 220 }),
      { type: 'paragraph', content: [{ type: 'text', text: 'Text after image.' }] },
      blockToPM({ kind: 'table', id: 'tbl1', headers: [{ content: [{ kind: 'text', value: 'Milestone' }] }, { content: [{ kind: 'text', value: 'Status' }] }], rows: [[{ content: [{ kind: 'text', value: 'Research' }] }, { content: [{ kind: 'text', value: 'Complete' }] }], [{ content: [{ kind: 'text', value: 'Prototype' }] }, { content: [{ kind: 'text', value: 'In review' }] }], [{ content: [{ kind: 'text', value: 'Release' }] }, { content: [{ kind: 'text', value: 'Planned' }] }]], columnWidthsDp: [], columnAlignments: [] }),
      { type: 'paragraph', content: [{ type: 'text', text: 'Paragraph after table.' }] },
      blockToPM({ kind: 'chart', id: 'chart1', vegaLiteSpec: '{"data":{"values":[{"month":"Jan","value":24},{"month":"Feb","value":42},{"month":"Mar","value":31},{"month":"Apr","value":56}]}}', editorHeightDp: 180 }),
      blockToPM({ kind: 'math', id: 'math1', tex: 'E = mc^2', display: true, editorHeightDp: 140 }),
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
let canonicalHistory = null

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
    kind: sel instanceof NodeSelection ? 'node' : sel.empty ? 'cursor' : 'text',
    from: sel.from,
    to: sel.to,
    empty: sel.empty,
    node: node ? { type: node.type.name, id: node.attrs.id } : null,
    text: sel.empty ? '' : editor.state.doc.textBetween(sel.from, sel.to, '\n', '\uFFFC'),
  }
}

function restoreSelection(selection) {
  if (!selection) {
    editor.commands.setTextSelection(1)
    return
  }
  if (selection.kind === 'node' && selection.node) {
    const hit = findAtom(selection.node.id)
    if (hit) editor.commands.setNodeSelection(hit.pos)
    return
  }
  const from = Math.max(0, Math.min(selection.from, editor.state.doc.content.size))
  const to = Math.max(from, Math.min(selection.to, editor.state.doc.content.size))
  editor.commands.setTextSelection({ from, to })
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
    canonicalHistory?.onTransaction(transaction)
  })
  const dom = editor.view.dom
  dom.addEventListener('compositionstart', () => evt({ t: 'comp', phase: 'start' }))
  dom.addEventListener('compositionupdate', (e) => evt({ t: 'comp', phase: 'update', data: e.data }))
  dom.addEventListener('compositionend', (e) => {
    canonicalHistory?.flush()
    evt({ t: 'comp', phase: 'end', data: e.data })
  })
}

    window.C3 = {
  mount(el) {
    editor = new Editor({
      element: el,
      extensions: [
        StarterKit.configure({ paragraph: false, undoRedo: false }),
        Paragraph,
        Atom('image_atom', 'Stage-1 fixture image'),
        Atom('table_atom', 'Stage-1 table, 2 by 2'),
        Atom('chart_atom', 'Stage-1 chart'),
        Atom('math_atom', 'Formula'),
        UnknownAtom,
        Tag,
        Mention,
      ],
      content: fixtureDoc(),
      onCreate: () => log('editor-created'),
    })
    window.C3._view = editor
    canonicalHistory = createCanonicalHistory({
      getSnapshot: () => JSON.stringify(projectPMToBlockDoc(editor.state.doc, editor.state.schema)),
      getSelection: selectionInfo,
      restore: (blockDocJson, selection) => {
        const pmJson = projectBlockDocToPM(blockDocJson, editor.state.schema)
        const doc = editor.state.schema.nodeFromJSON(pmJson)
        editor.view.dispatch(editor.state.tr.replaceWith(0, editor.state.doc.content.size, doc).setMeta('canonicalHistoryRestore', true))
        restoreSelection(selection)
      },
    })
    attach()
    log('mounted')
  },
  _canonicalHistory: () => canonicalHistory,
  blockDocToPM(blockDocJson) {
    if (!editor) return { error: 'no-editor' }
    try {
      return projectBlockDocToPM(blockDocJson, editor.state.schema)
    } catch (e) {
      return { error: e.message }
    }
  },
  pmToBlockDoc(pmDocOrJson) {
    if (!editor) return { error: 'no-editor' }
    try {
      return projectPMToBlockDoc(pmDocOrJson, editor.state.schema)
    } catch (e) {
      return { error: e.message }
    }
  },
  roundTripCanonical(blockDocJson) {
    const pmDoc = this.blockDocToPM(blockDocJson)
    if (pmDoc.error) return pmDoc
    return this.pmToBlockDoc(pmDoc)
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
  objectInfo(id) {
    const found = findAtom(id)
    return found ? { type: found.node.type.name, id: found.node.attrs.id, attrs: { ...found.node.attrs }, pos: found.pos } : { error: 'atom-not-found' }
  },
  resizeImage(id, displayHeightDp) {
    const found = findAtom(id)
    if (!found || found.node.type.name !== 'image_atom') return { error: 'image-not-found' }
    const height = resizedHeight(displayHeightDp, 0)
    editor.view.dispatch(editor.state.tr.setNodeMarkup(found.pos, undefined, { ...found.node.attrs, displayHeightDp: height }))
    return { ok: true, id, displayHeightDp: height }
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
