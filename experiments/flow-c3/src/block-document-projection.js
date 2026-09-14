const SUPPORTED_MARKS = new Set(['bold', 'italic', 'strike', 'underline', 'highlight', 'font_family', 'font_size', 'code', 'link', 'tag', 'mention'])

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
      case 'italic':
        result.push(...markText(inlineNodesToPM(node.children), 'italic'))
        break
      case 'strike':
        result.push(...markText(inlineNodesToPM(node.children), 'strike'))
        break
      case 'underline':
        result.push(...markText(inlineNodesToPM(node.children), 'underline'))
        break
      case 'highlight':
        result.push(...markText(inlineNodesToPM(node.children), 'highlight', node.color == null ? undefined : { color: node.color }))
        break
      case 'font_family':
        result.push(...markText(inlineNodesToPM(node.children), 'font_family', { family: node.family }))
        break
      case 'font_size':
        result.push(...markText(inlineNodesToPM(node.children), 'font_size', { sizeSp: node.sizeSp }))
        break
      case 'code':
        result.push(...markText([{ type: 'text', text: node.value }], 'code'))
        break
      case 'emoji':
        result.push({ type: 'emoji_inline', attrs: { shortcode: node.shortcode, unicode: node.unicode } })
        break
      case 'math':
        result.push({ type: 'math_inline', attrs: { tex: node.tex } })
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

function listItemToPM(item, listType) {
  const content = [{
    type: 'paragraph',
    attrs: { id: null, align: 'start', style: 'NONE' },
    content: inlineNodesToPM(item.content),
  }]
  if (item.children && item.children.length) {
    content.push({
      type: listType,
      attrs: listType === 'ordered_list' ? { id: null, start: 1, style: 'NONE' } : { id: null, style: 'NONE' },
      content: item.children.map((child) => listItemToPM(child, listType)),
    })
  }
  return { type: 'list_item', attrs: { id: item.id }, content }
}

function listItemFromPM(node, listKind) {
  let content = []
  let children = []
  node.forEach((child) => {
    // Multiple paragraph children (e.g. after a raw split) concatenate so no
    // text is dropped; blockToPM always emits the single-paragraph form.
    if (child.type.name === 'paragraph') content = content.concat(inlineContentToNodes(child.content.content))
    else if (child.type.name === 'bullet_list' || child.type.name === 'ordered_list') {
      child.forEach((item) => children.push(listItemFromPM(item, listKind)))
    }
  })
  return { id: node.attrs?.id ?? null, content, children }
}

function childBlockToPM(block) {
  return blockToPM(block.kind ? block : block.block)
}

function childBlocksFromPM(node) {
  const children = []
  node.forEach((child) => children.push(pmNodeToBlock(child)))
  return children
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
    case 'heading': {
      const text = inlinePlainText(block.content)
      return text ? `Heading ${block.level}: ${text}` : `Heading ${block.level}`
    }
    case 'bullet_list':
      return `Bulleted list: ${(block.items || []).length} items`
    case 'numbered_list':
      return `Numbered list: ${(block.items || []).length} items`
    case 'todo_list': {
      const open = (block.items || []).filter((item) => !item.checked).length
      return `Todo list: ${open} open of ${(block.items || []).length}`
    }
    case 'quote':
      return 'Quote'
    case 'callout':
      return block.title ? `Callout: ${block.title}` : 'Callout'
    case 'container':
      return `Container: ${(block.children || []).length} blocks`
    case 'divider':
      return 'Divider'
    case 'code':
      return block.language ? `Code: ${block.language}` : 'Code'
    case 'file':
      return block.name ? `File: ${block.name}` : 'File'
    case 'embed':
      return block.metadata?.title ? `Embed: ${block.metadata.title}` : block.url ? `Embed: ${block.url}` : 'Embed'
    case 'mermaid':
      return 'Mermaid diagram'
    case 'shape':
      return block.text ? `Shape: ${block.text}` : `Shape: ${block.shapeKind}`
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
    case 'heading':
      return {
        type: 'heading',
        attrs: { id: block.id, level: block.level, align: block.align, style: block.style },
        content: inlineNodesToPM(block.content),
      }
    case 'bullet_list':
      return {
        type: 'bullet_list',
        attrs: { id: block.id, style: block.style },
        content: (block.items || []).map((item) => listItemToPM(item, 'bullet_list')),
      }
    case 'numbered_list':
      return {
        type: 'ordered_list',
        attrs: { id: block.id, start: block.start, style: block.style },
        content: (block.items || []).map((item) => listItemToPM(item, 'ordered_list')),
      }
    case 'todo_list':
      return {
        type: 'todo_list',
        attrs: { id: block.id, style: block.style },
        content: (block.items || []).map((item) => ({
          type: 'todo_item',
          attrs: { id: item.id, checked: item.checked },
          content: inlineNodesToPM(item.content),
        })),
      }
    case 'quote':
      return {
        type: 'blockquote',
        attrs: { id: block.id, style: block.style },
        content: (block.children || []).map(childBlockToPM),
      }
    case 'callout':
      return {
        type: 'callout',
        attrs: { id: block.id, tone: block.tone, title: block.title, collapsed: block.collapsed, style: block.style },
        content: (block.children || []).map(childBlockToPM),
      }
    case 'container':
      return {
        type: 'container',
        attrs: { id: block.id, axis: block.axis, weights: block.weights },
        content: (block.children || []).map(childBlockToPM),
      }
    case 'divider':
      return { type: 'horizontal_rule', attrs: { id: block.id } }
    case 'code':
      return {
        type: 'code_block',
        attrs: { id: block.id, language: block.language, editorHeightDp: block.editorHeightDp, style: block.style },
        content: block.code ? [{ type: 'text', text: block.code }] : [],
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
          ...(block.imageEditDocument != null ? { imageEditDocument: block.imageEditDocument } : {}),
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
          ...(block.calculationPayload != null ? { calculationPayload: block.calculationPayload } : {}),
          ...(block.calculationTableId != null ? { calculationTableId: block.calculationTableId } : {}),
          ...(block.calculationSchemaVersion != null ? { calculationSchemaVersion: block.calculationSchemaVersion } : {}),
          ...(block.legacyRecovery != null ? { legacyRecovery: block.legacyRecovery } : {}),
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
          ...(block.chartDocument != null ? { chartDocument: block.chartDocument } : {}),
        },
      }
    case 'file':
      return {
        type: 'file_atom',
        attrs: {
          id: block.id,
          a11yName: blockAccessibilityName(block),
          name: block.name,
          mimeType: block.mimeType,
          sizeBytes: block.sizeBytes,
          uri: block.uri,
        },
      }
    case 'embed':
      return {
        type: 'embed_atom',
        attrs: {
          id: block.id,
          a11yName: blockAccessibilityName(block),
          url: block.url,
          metadata: block.metadata,
          displayHeightDp: block.displayHeightDp,
        },
      }
    case 'mermaid':
      return {
        type: 'mermaid_atom',
        attrs: {
          id: block.id,
          a11yName: blockAccessibilityName(block),
          code: block.code,
          editorHeightDp: block.editorHeightDp,
          ...(block.diagramEditDocument != null ? { diagramEditDocument: block.diagramEditDocument } : {}),
        },
      }
    case 'shape':
      return {
        type: 'shape_atom',
        attrs: {
          id: block.id,
          a11yName: blockAccessibilityName(block),
          shapeKind: block.shapeKind,
          text: block.text,
          fillColor: block.fillColor,
          strokeColor: block.strokeColor,
          strokeWidthDp: block.strokeWidthDp,
          displayHeightDp: block.displayHeightDp,
          rotationDegrees: block.rotationDegrees,
          ...(block.vectorAsset != null ? { vectorAsset: block.vectorAsset } : {}),
          ...(block.fillPaint != null ? { fillPaint: block.fillPaint } : {}),
          ...(block.strokePaint != null ? { strokePaint: block.strokePaint } : {}),
          style: block.style,
          ...(block.shapeEditDocument != null ? { shapeEditDocument: block.shapeEditDocument } : {}),
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
      case 'italic':
        node = { kind: 'italic', children: [node] }
        break
      case 'strike':
        node = { kind: 'strike', children: [node] }
        break
      case 'underline':
        node = { kind: 'underline', children: [node] }
        break
      case 'highlight':
        node = { kind: 'highlight', color: mark.attrs?.color ?? null, children: [node] }
        break
      case 'font_family':
        node = { kind: 'font_family', family: mark.attrs?.family ?? '', children: [node] }
        break
      case 'font_size':
        node = { kind: 'font_size', sizeSp: mark.attrs?.sizeSp ?? 0, children: [node] }
        break
      case 'code':
        node = { kind: 'code', value: text }
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
    if (node.type.name === 'emoji_inline') {
      result.push({ kind: 'emoji', shortcode: node.attrs?.shortcode ?? '', unicode: node.attrs?.unicode ?? '' })
      continue
    }
    if (node.type.name === 'math_inline') {
      result.push({ kind: 'math', tex: node.attrs?.tex ?? '' })
      continue
    }
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
    case 'heading':
      return {
        kind: 'heading',
        id: attrs.id,
        level: attrs.level,
        content: inlineContentToNodes(node.content.content),
        align: attrs.align,
        style: attrs.style,
      }
    case 'bullet_list': {
      const items = []
      node.forEach((item) => items.push(listItemFromPM(item, 'bullet_list')))
      return { kind: 'bullet_list', id: attrs.id, items, style: attrs.style }
    }
    case 'ordered_list': {
      const items = []
      node.forEach((item) => items.push(listItemFromPM(item, 'numbered_list')))
      return { kind: 'numbered_list', id: attrs.id, start: attrs.start, items, style: attrs.style }
    }
    case 'todo_list': {
      const items = []
      node.forEach((item) => {
        if (item.type.name !== 'todo_item') throw new Error(`unsupported PM node in todo_list: ${item.type.name}`)
        items.push({ id: item.attrs?.id ?? null, checked: item.attrs?.checked ?? false, content: inlineContentToNodes(item.content.content) })
      })
      return { kind: 'todo_list', id: attrs.id, items, style: attrs.style }
    }
    case 'blockquote':
      return { kind: 'quote', id: attrs.id, children: childBlocksFromPM(node), style: attrs.style }
    case 'callout':
      return { kind: 'callout', id: attrs.id, tone: attrs.tone, title: attrs.title, collapsed: attrs.collapsed, children: childBlocksFromPM(node), style: attrs.style }
    case 'container':
      return { kind: 'container', id: attrs.id, axis: attrs.axis, children: childBlocksFromPM(node), weights: attrs.weights }
    case 'horizontal_rule':
      return { kind: 'divider', id: attrs.id }
    case 'code_block':
      return { kind: 'code', id: attrs.id, language: attrs.language, code: node.textContent, editorHeightDp: attrs.editorHeightDp, style: attrs.style }
    case 'image_atom':
      return {
        kind: 'image', id: attrs.id, source: attrs.source, caption: attrs.caption, layout: attrs.layout, displayHeightDp: attrs.displayHeightDp,
        ...(attrs.imageEditDocument != null ? { imageEditDocument: attrs.imageEditDocument } : {}),
      }
    case 'table_atom':
      return {
        kind: 'table', id: attrs.id, headers: attrs.headers, rows: attrs.rows, columnWidthsDp: attrs.columnWidthsDp, columnAlignments: attrs.columnAlignments,
        ...(attrs.calculationPayload != null ? { calculationPayload: attrs.calculationPayload } : {}),
        ...(attrs.calculationTableId != null ? { calculationTableId: attrs.calculationTableId } : {}),
        ...(attrs.calculationSchemaVersion != null ? { calculationSchemaVersion: attrs.calculationSchemaVersion } : {}),
        ...(attrs.legacyRecovery != null ? { legacyRecovery: attrs.legacyRecovery } : {}),
      }
    case 'chart_atom':
      return {
        kind: 'chart', id: attrs.id, vegaLiteSpec: attrs.vegaLiteSpec, editorHeightDp: attrs.editorHeightDp,
        ...(attrs.chartDocument != null ? { chartDocument: attrs.chartDocument } : {}),
      }
    case 'file_atom':
      return { kind: 'file', id: attrs.id, name: attrs.name, mimeType: attrs.mimeType, sizeBytes: attrs.sizeBytes, uri: attrs.uri }
    case 'embed_atom':
      return { kind: 'embed', id: attrs.id, url: attrs.url, metadata: attrs.metadata, displayHeightDp: attrs.displayHeightDp }
    case 'mermaid_atom':
      return {
        kind: 'mermaid', id: attrs.id, code: attrs.code, editorHeightDp: attrs.editorHeightDp,
        ...(attrs.diagramEditDocument != null ? { diagramEditDocument: attrs.diagramEditDocument } : {}),
      }
    case 'shape_atom':
      return {
        kind: 'shape', id: attrs.id, shapeKind: attrs.shapeKind, text: attrs.text, fillColor: attrs.fillColor, strokeColor: attrs.strokeColor, strokeWidthDp: attrs.strokeWidthDp, displayHeightDp: attrs.displayHeightDp, rotationDegrees: attrs.rotationDegrees,
        ...(attrs.vectorAsset != null ? { vectorAsset: attrs.vectorAsset } : {}),
        ...(attrs.fillPaint != null ? { fillPaint: attrs.fillPaint } : {}),
        ...(attrs.strokePaint != null ? { strokePaint: attrs.strokePaint } : {}),
        style: attrs.style,
        ...(attrs.shapeEditDocument != null ? { shapeEditDocument: attrs.shapeEditDocument } : {}),
      }
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
