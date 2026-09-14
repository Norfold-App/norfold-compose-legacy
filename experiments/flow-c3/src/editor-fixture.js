// FLOW-03 C3 Stage-1 spike. Real ProseMirror/Tiptap document: image/table/chart
// are explicit atomic block nodes with stable Norfold IDs in attributes, NOT
// U+FFFC characters. Exposes window.C3 for a persistent Android WebView host and
// reports compact events (no whole-document JSON per keystroke) via Norfold.onEvent.
import { Editor, Node, Mark, Extension, mergeAttributes } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import ParagraphNode from '@tiptap/extension-paragraph'
import { NodeSelection } from '@tiptap/pm/state'
import { blockDocToPM as projectBlockDocToPM, blockToPM, pmToBlockDoc as projectPMToBlockDoc } from './block-document-projection.js'
import { uniqueBlockIdPlugin } from './block-ids.js'
import { ListKeyBehavior, LIST_KEYMAP_OPTIONS } from './list-key-behavior.js'
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
          imageEditDocument: { default: null },
        })
      } else if (name === 'table_atom') {
        Object.assign(attributes, {
          headers: { default: [] },
          rows: { default: [] },
          columnWidthsDp: { default: [] },
          columnAlignments: { default: [] },
          calculationPayload: { default: null },
          calculationTableId: { default: null },
          calculationSchemaVersion: { default: null },
        })
      } else if (name === 'chart_atom') {
        Object.assign(attributes, {
          vegaLiteSpec: { default: '{}' },
          editorHeightDp: { default: 180 },
          chartDocument: { default: null },
        })
      } else if (name === 'file_atom') {
        Object.assign(attributes, {
          name: { default: '' },
          mimeType: { default: 'application/octet-stream' },
          sizeBytes: { default: 0 },
          uri: { default: '' },
        })
      } else if (name === 'embed_atom') {
        Object.assign(attributes, {
          url: { default: '' },
          metadata: { default: {} },
          displayHeightDp: { default: 112 },
        })
      } else if (name === 'mermaid_atom') {
        Object.assign(attributes, {
          code: { default: '' },
          editorHeightDp: { default: 180 },
          diagramEditDocument: { default: null },
        })
      } else if (name === 'shape_atom') {
        Object.assign(attributes, {
          shapeKind: { default: 'RoundedRectangle' },
          text: { default: '' },
          fillColor: { default: 0 },
          strokeColor: { default: 0 },
          strokeWidthDp: { default: 2 },
          displayHeightDp: { default: 180 },
          rotationDegrees: { default: 0 },
          style: { default: 'NONE' },
          shapeEditDocument: { default: null },
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
    if (node.kind === 'code') return node.value
    if (node.kind === 'emoji') return node.unicode
    if (node.kind === 'math') return node.tex
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
    const role = type === 'table_atom' ? 'table'
      : type === 'math_atom' ? 'math'
      : type === 'file_atom' ? 'link'
      : type === 'embed_atom' ? 'link'
      : 'img'
    dom.setAttribute('role', role)
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

  function renderFile(node) {
    const row = document.createElement('div')
    row.className = 'c3-file-row'
    const name = document.createElement('span')
    name.className = 'c3-file-name'
    name.textContent = node.attrs.name || 'File'
    const meta = document.createElement('span')
    meta.className = 'c3-file-meta'
    meta.textContent = `${node.attrs.mimeType || ''} · ${node.attrs.sizeBytes || 0} bytes`
    row.append(name, meta)
    dom.appendChild(row)
  }

  function renderEmbed(node) {
    const box = document.createElement('div')
    box.className = 'c3-embed-box'
    const title = document.createElement('div')
    title.className = 'c3-embed-title'
    title.textContent = node.attrs.metadata?.title || node.attrs.url || 'Embed'
    const url = document.createElement('div')
    url.className = 'c3-embed-url'
    url.textContent = node.attrs.url || ''
    box.append(title, url)
    dom.appendChild(box)
  }

  function renderMermaid(node) {
    const pre = document.createElement('pre')
    pre.className = 'c3-mermaid-source'
    pre.textContent = node.attrs.code || ''
    dom.appendChild(pre)
  }

  function renderShape(node) {
    const box = document.createElement('div')
    box.className = 'c3-shape-box'
    box.textContent = node.attrs.text || node.attrs.shapeKind || 'Shape'
    dom.appendChild(box)
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
    else if (node.type.name === 'file_atom') renderFile(node)
    else if (node.type.name === 'embed_atom') renderEmbed(node)
    else if (node.type.name === 'mermaid_atom') renderMermaid(node)
    else if (node.type.name === 'shape_atom') renderShape(node)
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

const InlineMark = (name, attrs = {}) =>
  Mark.create({
    name,
    addAttributes() {
      return attrs
    },
    parseHTML() {
      return [{ tag: `span[data-mark="${name}"]` }]
    },
    renderHTML({ HTMLAttributes }) {
      return ['span', mergeAttributes(HTMLAttributes, { class: `c3-${name}`, 'data-mark': name }), 0]
    },
  })

const Italic = InlineMark('italic')
const Strike = InlineMark('strike')
const Underline = InlineMark('underline')
const Highlight = InlineMark('highlight', { color: { default: null } })
const FontFamily = InlineMark('font_family', { family: { default: '' } })
const FontSize = InlineMark('font_size', { sizeSp: { default: 0 } })
const CodeInline = InlineMark('code')

const Heading = Node.create({
  name: 'heading',
  group: 'block',
  content: 'inline*',
  defining: true,
  addAttributes() {
    return {
      id: { default: null },
      level: { default: 1 },
      align: { default: 'start' },
      style: { default: 'NONE' },
    }
  },
  parseHTML() {
    return [{ tag: 'h1[data-block="heading"]' }, { tag: 'h2[data-block="heading"]' }, { tag: 'h3[data-block="heading"]' }]
  },
  renderHTML({ node, HTMLAttributes }) {
    const level = Math.min(6, Math.max(1, node.attrs.level || 1))
    return [`h${level}`, mergeAttributes(HTMLAttributes, { class: 'c3-heading', 'data-block': 'heading' }), 0]
  },
})

const BulletList = Node.create({
  name: 'bullet_list',
  group: 'block',
  content: 'list_item+',
  addAttributes() {
    return { id: { default: null }, style: { default: 'NONE' } }
  },
  parseHTML() {
    return [{ tag: 'ul[data-block="bullet_list"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['ul', mergeAttributes(HTMLAttributes, { class: 'c3-bullet-list', 'data-block': 'bullet_list' }), 0]
  },
})

const OrderedList = Node.create({
  name: 'ordered_list',
  group: 'block',
  content: 'list_item+',
  addAttributes() {
    return { id: { default: null }, start: { default: 1 }, style: { default: 'NONE' } }
  },
  parseHTML() {
    return [{ tag: 'ol[data-block="ordered_list"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['ol', mergeAttributes(HTMLAttributes, { class: 'c3-ordered-list', 'data-block': 'ordered_list' }), 0]
  },
})

const ListItem = Node.create({
  name: 'list_item',
  content: 'paragraph (bullet_list | ordered_list)?',
  defining: true,
  addAttributes() {
    return { id: { default: null } }
  },
  parseHTML() {
    return [{ tag: 'li[data-block="list_item"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['li', mergeAttributes(HTMLAttributes, { class: 'c3-list-item', 'data-block': 'list_item' }), 0]
  },
})

const TodoList = Node.create({
  name: 'todo_list',
  group: 'block',
  content: 'todo_item+',
  addAttributes() {
    return { id: { default: null }, style: { default: 'NONE' } }
  },
  parseHTML() {
    return [{ tag: 'ul[data-block="todo_list"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['ul', mergeAttributes(HTMLAttributes, { class: 'c3-todo-list', 'data-block': 'todo_list' }), 0]
  },
})

const TodoItem = Node.create({
  name: 'todo_item',
  content: 'inline*',
  defining: true,
  addAttributes() {
    return { id: { default: null }, checked: { default: false } }
  },
  parseHTML() {
    return [{ tag: 'li[data-block="todo_item"]' }]
  },
  renderHTML({ node, HTMLAttributes }) {
    return ['li', mergeAttributes(HTMLAttributes, { class: 'c3-todo-item', 'data-block': 'todo_item', 'data-checked': String(!!node.attrs.checked) }), 0]
  },
  addNodeView() {
    return ({ node, editor, getPos }) => {
      const item = document.createElement('li')
      item.className = 'c3-todo-item'
      const box = document.createElement('input')
      box.type = 'checkbox'
      box.checked = !!node.attrs.checked
      box.setAttribute('aria-label', 'Todo checked')
      box.tabIndex = 0
      const content = document.createElement('span')
      content.className = 'c3-todo-content'
      item.append(box, content)
      box.addEventListener('change', () => {
        const pos = getPos()
        if (typeof pos !== 'number') return
        editor.view.dispatch(editor.state.tr.setNodeMarkup(pos, undefined, { ...node.attrs, checked: box.checked }))
      })
      return {
        dom: item,
        contentDOM: content,
        update(next) {
          if (next.type.name !== 'todo_item') return false
          node = next
          box.checked = !!next.attrs.checked
          return true
        },
        ignoreMutation(mutation) {
          return mutation.target === box
        },
      }
    }
  },
})

const Blockquote = Node.create({
  name: 'blockquote',
  group: 'block',
  content: 'block+',
  defining: true,
  addAttributes() {
    return { id: { default: null }, style: { default: 'NONE' } }
  },
  parseHTML() {
    return [{ tag: 'blockquote[data-block="blockquote"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['blockquote', mergeAttributes(HTMLAttributes, { class: 'c3-quote', 'data-block': 'blockquote' }), 0]
  },
})

const Callout = Node.create({
  name: 'callout',
  group: 'block',
  content: 'block+',
  defining: true,
  addAttributes() {
    return {
      id: { default: null },
      tone: { default: 'note' },
      title: { default: 'Note' },
      collapsed: { default: false },
      style: { default: 'NONE' },
    }
  },
  parseHTML() {
    return [{ tag: 'aside[data-block="callout"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['aside', mergeAttributes(HTMLAttributes, { class: 'c3-callout', 'data-block': 'callout' }), 0]
  },
})

const Container = Node.create({
  name: 'container',
  group: 'block',
  content: 'block+',
  defining: true,
  addAttributes() {
    return { id: { default: null }, axis: { default: 'Column' }, weights: { default: [] } }
  },
  parseHTML() {
    return [{ tag: 'div[data-block="container"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { class: 'c3-container', 'data-block': 'container' }), 0]
  },
})

const Divider = Node.create({
  name: 'horizontal_rule',
  group: 'block',
  atom: true,
  selectable: true,
  addAttributes() {
    return { id: { default: null } }
  },
  parseHTML() {
    return [{ tag: 'hr[data-block="divider"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['hr', mergeAttributes(HTMLAttributes, { class: 'c3-divider', 'data-block': 'divider' })]
  },
})

const CodeBlock = Node.create({
  name: 'code_block',
  group: 'block',
  content: 'text*',
  marks: '',
  defining: true,
  code: true,
  addAttributes() {
    return {
      id: { default: null },
      language: { default: '' },
      editorHeightDp: { default: 180 },
      style: { default: 'NONE' },
    }
  },
  parseHTML() {
    return [{ tag: 'pre[data-block="code"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['pre', mergeAttributes(HTMLAttributes, { class: 'c3-code', 'data-block': 'code' }), ['code', 0]]
  },
})

const EmojiInline = Node.create({
  name: 'emoji_inline',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: false,
  addAttributes() {
    return { shortcode: { default: '' }, unicode: { default: '' } }
  },
  parseHTML() {
    return [{ tag: 'span[data-inline="emoji"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes, { class: 'c3-emoji', 'data-inline': 'emoji' }), HTMLAttributes.unicode || '']
  },
})

const MathInline = Node.create({
  name: 'math_inline',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,
  addAttributes() {
    return { tex: { default: '' } }
  },
  parseHTML() {
    return [{ tag: 'span[data-inline="math"]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes, { class: 'c3-math-inline', 'data-inline': 'math' }), HTMLAttributes.tex || '']
  },
})

const UniqueIds = Extension.create({
  name: 'uniqueIds',
  addProseMirrorPlugins() {
    return [uniqueBlockIdPlugin()]
  },
})

function fixtureDoc() {
  const richPara = {
    type: 'paragraph',
    attrs: { id: 'hostile-rich', align: 'start', style: 'NONE' },
    content: [
      { type: 'text', text: 'Rich ' },
      { type: 'text', marks: [{ type: 'bold' }, { type: 'italic' }], text: 'bold-italic' },
      { type: 'text', text: ' ' },
      { type: 'text', marks: [{ type: 'strike' }, { type: 'underline' }], text: 'struck-under' },
      { type: 'text', text: ' ' },
      { type: 'text', marks: [{ type: 'highlight', attrs: { color: '#ff0' } }], text: 'lit' },
      { type: 'text', text: ' ' },
      { type: 'text', marks: [{ type: 'code' }], text: 'code()' },
      { type: 'text', text: ' ' },
      { type: 'emoji_inline', attrs: { shortcode: 'smile', unicode: '😀' } },
      { type: 'text', text: ' ' },
      { type: 'math_inline', attrs: { tex: 'a^2' } },
      { type: 'text', text: ' ' },
      {
        type: 'text',
        marks: [{ type: 'link', attrs: { href: 'https://example.com/flow-stage1' } }],
        text: 'link',
      },
      { type: 'text', text: ' ' },
      { type: 'text', marks: [{ type: 'tag' }], text: '#stage2b' },
      { type: 'text', text: ' ' },
      { type: 'text', marks: [{ type: 'mention' }], text: '@qa' },
      { type: 'text', text: '.' },
    ],
  }
  const para = (id, text) => ({
    type: 'paragraph',
    attrs: { id, align: 'start', style: 'NONE' },
    content: [{ type: 'text', text }],
  })
  return {
    type: 'doc',
    content: [
      { type: 'heading', attrs: { id: 'hostile-h1', level: 1, align: 'start', style: 'NONE' }, content: [{ type: 'text', text: 'Hostile Flow document' }] },
      { type: 'heading', attrs: { id: 'hostile-h2', level: 2, align: 'start', style: 'NONE' }, content: [{ type: 'text', text: 'Inline marks' }] },
      richPara,
      { type: 'heading', attrs: { id: 'hostile-h3', level: 2, align: 'start', style: 'NONE' }, content: [{ type: 'text', text: 'Lists' }] },
      {
        type: 'bullet_list',
        attrs: { id: 'hostile-bl', style: 'NONE' },
        content: [
          {
            type: 'list_item',
            attrs: { id: 'hostile-li1' },
            content: [
              para('hostile-li1p', 'Bullet one'),
              {
                type: 'bullet_list',
                attrs: { id: null, style: 'NONE' },
                content: [{ type: 'list_item', attrs: { id: 'hostile-li1a' }, content: [para('hostile-li1ap', 'Nested bullet')] }],
              },
            ],
          },
          { type: 'list_item', attrs: { id: 'hostile-li2' }, content: [para('hostile-li2p', 'Bullet two')] },
        ],
      },
      {
        type: 'ordered_list',
        attrs: { id: 'hostile-ol', start: 3, style: 'NONE' },
        content: [{ type: 'list_item', attrs: { id: 'hostile-n1' }, content: [para('hostile-n1p', 'Numbered from three')] }],
      },
      {
        type: 'todo_list',
        attrs: { id: 'hostile-tl', style: 'NONE' },
        content: [
          { type: 'todo_item', attrs: { id: 'hostile-t1', checked: true }, content: [{ type: 'text', text: 'Done item' }] },
          { type: 'todo_item', attrs: { id: 'hostile-t2', checked: false }, content: [{ type: 'text', text: 'Open item' }] },
        ],
      },
      {
        type: 'blockquote',
        attrs: { id: 'hostile-q', style: 'NONE' },
        content: [para('hostile-qp', 'A quoted line.')],
      },
      {
        type: 'callout',
        attrs: { id: 'hostile-c', tone: 'warning', title: 'Careful', collapsed: false, style: 'NONE' },
        content: [para('hostile-cp', 'Callout body.')],
      },
      {
        type: 'container',
        attrs: { id: 'hostile-k', axis: 'Row', weights: [1, 2] },
        content: [para('hostile-k1', 'Left cell.'), para('hostile-k2', 'Right cell.')],
      },
      { type: 'horizontal_rule', attrs: { id: 'hostile-d' } },
      {
        type: 'code_block',
        attrs: { id: 'hostile-code', language: 'kotlin', editorHeightDp: 180, style: 'NONE' },
        content: [{ type: 'text', text: 'fun hostile() {\n  check()\n}' }],
      },
      { type: 'image_atom', attrs: { id: 'img1', a11yName: 'Image: Morning study', source: REVIEW_IMAGE_SOURCE, caption: 'Morning study', layout: 'Fit', displayHeightDp: 220 } },
      { type: 'paragraph', attrs: { id: 'hostile-after-img', align: 'start', style: 'NONE' }, content: [{ type: 'text', text: 'Text after image.' }] },
      blockToPM({ kind: 'table', id: 'tbl1', headers: [{ content: [{ kind: 'text', value: 'Milestone' }] }, { content: [{ kind: 'text', value: 'Status' }] }], rows: [[{ content: [{ kind: 'text', value: 'Research' }] }, { content: [{ kind: 'text', value: 'Complete' }] }]], columnWidthsDp: [], columnAlignments: [] }),
      blockToPM({ kind: 'chart', id: 'chart1', vegaLiteSpec: '{"data":{"values":[{"month":"Jan","value":24},{"month":"Feb","value":42}]}}', editorHeightDp: 180 }),
      blockToPM({ kind: 'math', id: 'math1', tex: 'E = mc^2', display: true, editorHeightDp: 140 }),
      blockToPM({ kind: 'file', id: 'file1', name: 'deck.pdf', mimeType: 'application/pdf', sizeBytes: 41002, uri: 'file://deck' }),
      blockToPM({ kind: 'embed', id: 'embed1', url: 'https://example.test/v', metadata: { title: 'Vid', description: 'A video' }, displayHeightDp: 112 }),
      blockToPM({ kind: 'mermaid', id: 'mermaid1', code: 'graph TD; A-->B;', editorHeightDp: 180 }),
      blockToPM({ kind: 'shape', id: 'shape1', shapeKind: 'Ellipse', text: 'Hi', fillColor: 4284900966, strokeColor: 4291240959, strokeWidthDp: 2, displayHeightDp: 180, rotationDegrees: 0, style: 'NONE' }),
      { type: 'unknown_atom', attrs: { id: 'future1', a11yName: 'Unknown block', rawJson: '{"v":99,"block":{"kind":"hologram","id":"future1"}}' } },
      para('hostile-final', 'Final paragraph.'),
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
        StarterKit.configure({
          paragraph: false,
          undoRedo: false,
          heading: false,
          bulletList: false,
          orderedList: false,
          listItem: false,
          listKeymap: LIST_KEYMAP_OPTIONS,
          blockquote: false,
          codeBlock: false,
          horizontalRule: false,
          italic: false,
          strike: false,
          underline: false,
          code: false,
        }),
        Paragraph,
        Heading,
        BulletList,
        OrderedList,
        ListItem,
        ListKeyBehavior,
        TodoList,
        TodoItem,
        Blockquote,
        Callout,
        Container,
        Divider,
        CodeBlock,
        Atom('image_atom', 'Stage-1 fixture image'),
        Atom('table_atom', 'Stage-1 table, 2 by 2'),
        Atom('chart_atom', 'Stage-1 chart'),
        Atom('math_atom', 'Formula'),
        Atom('file_atom', 'File'),
        Atom('embed_atom', 'Embed'),
        Atom('mermaid_atom', 'Diagram'),
        Atom('shape_atom', 'Shape'),
        UnknownAtom,
        EmojiInline,
        MathInline,
        Tag,
        Mention,
        Italic,
        Strike,
        Underline,
        Highlight,
        FontFamily,
        FontSize,
        CodeInline,
        UniqueIds,
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
