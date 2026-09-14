const SUPPORTED_MARKS = new Set(['bold', 'link', 'tag', 'mention'])

function parse(value) {
  return typeof value === 'string' ? JSON.parse(value) : value
}

function markText(children, type, attrs) {
  return children.map((child) => {
    if (child.type !== 'text') return child
    return { ...child, marks: [...(child.marks || []), { type, ...(attrs ? { attrs } : {}) }] }
  })
}

function inlineNodesToPM(nodes) {
  const result = []
  for (const node of nodes || []) {
    switch (node.kind) {
      case 'text':
        result.push({ type: 'text', text: node.value })
        break
      case 'bold':
        result.push(...markText(inlineNodesToPM(node.children), 'bold'))
        break
      case 'link':
        result.push(...markText(inlineNodesToPM(node.children), 'link', { href: node.url }))
        break
      case 'tag':
        result.push(...markText([{ type: 'text', text: node.value }], 'tag'))
        break
      case 'mention':
        result.push(...markText([{ type: 'text', text: node.value }], 'mention'))
        break
      default:
        throw new Error(`unsupported inline kind: ${node.kind}`)
    }
  }
  return result
}

function inlinePlainText(nodes = []) {
  return nodes.map((node) => node.kind === 'text' ? node.value : node.children ? inlinePlainText(node.children) : node.value || '').join('')
}

function chartSummary(specJson) {
  try {
    return (JSON.parse(specJson).data?.values || []).map((row) => {
      const entries = Object.entries(row)
      const numeric = entries.find(([, value]) => typeof value === 'number' && Number.isFinite(value))
      const label = entries.find(([key, value]) => key !== numeric?.[0] && (typeof value === 'string' || typeof value === 'number'))
      if (numeric && label) return `${label[1]} ${numeric[1]}`
      if (numeric) return `${numeric[0]} ${numeric[1]}`
      return null
    }).filter(Boolean).join(', ')
  } catch {
    return ''
  }
}

export function blockAccessibilityName(block) {
  switch (block.kind) {
    case 'image':
      return block.caption ? `Image: ${block.caption}` : 'Image'
    case 'table': {
      const headers = (block.headers || []).map((cell) => inlinePlainText(cell.content)).filter(Boolean).join(', ')
      const rows = (block.rows || []).length
      const columns = (block.headers || []).length
      const dimensions = `${rows} ${rows === 1 ? 'row' : 'rows'} by ${columns} ${columns === 1 ? 'column' : 'columns'}`
      return headers ? `Table: ${headers}; ${dimensions}` : `Table: ${dimensions}`
    }
    case 'chart': {
      const summary = chartSummary(block.vegaLiteSpec)
      return summary ? `Chart: ${summary}` : 'Chart'
    }
    case 'math':
      return block.tex ? `Formula: ${block.tex}` : 'Formula'
    case 'unknown':
      return 'Unknown block'
    default:
      return block.kind
  }
}

export function blockToPM(block) {
  switch (block.kind) {
    case 'paragraph':
      return {
        type: 'paragraph',
        attrs: { id: block.id, align: block.align, style: block.style },
        content: inlineNodesToPM(block.content),
      }
    case 'image':
      return {
        type: 'image_atom',
        attrs: {
          id: block.id,
          a11yName: blockAccessibilityName(block),
          source: block.source,
          caption: block.caption,
          layout: block.layout,
          displayHeightDp: block.displayHeightDp,
        },
      }
    case 'table':
      return {
        type: 'table_atom',
        attrs: {
          id: block.id,
          a11yName: blockAccessibilityName(block),
          headers: block.headers,
          rows: block.rows,
          columnWidthsDp: block.columnWidthsDp,
          columnAlignments: block.columnAlignments,
        },
      }
    case 'chart':
      return {
        type: 'chart_atom',
        attrs: {
          id: block.id,
          a11yName: blockAccessibilityName(block),
          vegaLiteSpec: block.vegaLiteSpec,
          editorHeightDp: block.editorHeightDp,
        },
      }
    case 'math':
      return {
        type: 'math_atom',
        attrs: {
          id: block.id,
          a11yName: blockAccessibilityName(block),
          tex: block.tex,
          display: block.display,
          editorHeightDp: block.editorHeightDp,
        },
      }
    case 'unknown':
      return {
        type: 'unknown_atom',
        attrs: { id: block.id, a11yName: 'Unknown block', rawJson: block.rawJson },
      }
    default:
      throw new Error(`unsupported block kind: ${block.kind}`)
  }
}

function marksToInline(text, marks) {
  let node = { kind: 'text', value: text }
  for (let index = marks.length - 1; index >= 0; index -= 1) {
    const mark = marks[index]
    switch (mark.type) {
      case 'bold':
        node = { kind: 'bold', children: [node] }
        break
      case 'link':
        node = { kind: 'link', url: mark.attrs.href, children: [node] }
        break
      case 'tag':
        node = { kind: 'tag', value: text }
        break
      case 'mention':
        node = { kind: 'mention', value: text }
        break
      default:
        throw new Error(`unsupported PM mark: ${mark.type}`)
    }
  }
  return node
}

function inlineContentToNodes(content) {
  const result = []
  for (const node of content || []) {
    if (node.type.name !== 'text') throw new Error(`unsupported PM inline node: ${node.type.name}`)
    const marks = (node.marks || []).filter((mark) => SUPPORTED_MARKS.has(mark.type.name)).map((mark) => ({ type: mark.type.name, attrs: mark.attrs }))
    result.push(marksToInline(node.text, marks))
  }
  return result
}

function pmNodeToBlock(node) {
  const attrs = node.attrs || {}
  switch (node.type.name) {
    case 'paragraph':
      return {
        kind: 'paragraph',
        id: attrs.id,
        content: inlineContentToNodes(node.content.content),
        align: attrs.align,
        style: attrs.style,
      }
    case 'image_atom':
      return { kind: 'image', id: attrs.id, source: attrs.source, caption: attrs.caption, layout: attrs.layout, displayHeightDp: attrs.displayHeightDp }
    case 'table_atom':
      return { kind: 'table', id: attrs.id, headers: attrs.headers, rows: attrs.rows, columnWidthsDp: attrs.columnWidthsDp, columnAlignments: attrs.columnAlignments }
    case 'chart_atom':
      return { kind: 'chart', id: attrs.id, vegaLiteSpec: attrs.vegaLiteSpec, editorHeightDp: attrs.editorHeightDp }
    case 'math_atom':
      return { kind: 'math', id: attrs.id, tex: attrs.tex, display: attrs.display, editorHeightDp: attrs.editorHeightDp }
    case 'unknown_atom':
      return { kind: 'unknown', id: attrs.id, rawJson: attrs.rawJson }
    default:
      throw new Error(`unsupported PM node: ${node.type.name}`)
  }
}

export function blockDocToPM(blockDocJson, schema) {
  const envelopes = parse(blockDocJson)
  const content = envelopes.map((envelope) => blockToPM(envelope.block))
  return schema.nodeFromJSON({ type: 'doc', content }).toJSON()
}

export function pmToBlockDoc(pmDocOrJson, schema) {
  const value = parse(pmDocOrJson)
  const json = value && value.type === 'doc' ? value : { type: 'doc', content: value }
  const doc = value && typeof value.toJSON === 'function' ? value : schema.nodeFromJSON(json)
  const blocks = []
  doc.forEach((node) => blocks.push({ v: 1, block: pmNodeToBlock(node) }))
  return blocks
}

export function roundTripCanonical(blockDocJson, schema) {
  return pmToBlockDoc(blockDocToPM(blockDocJson, schema), schema)
}
