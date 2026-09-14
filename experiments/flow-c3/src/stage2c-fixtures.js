const OBJECT_KINDS = ['image', 'table', 'chart', 'math', 'file', 'embed', 'mermaid', 'shape', 'unknown']
const WORDS = ['norfold', 'continuous', 'structured', 'editor', 'android', 'document', 'selection', 'object']

function text(index, count) {
  const words = []
  for (let offset = 0; offset < count; offset += 1) {
    words.push(WORDS[(index + offset) % WORDS.length])
  }
  return words.join(' ')
}

function inline(value) {
  return [{ kind: 'text', value }]
}

function paragraph(id, value) {
  return { kind: 'paragraph', id, content: inline(value), align: 'start', style: 'NONE' }
}

function textBlock(index, wordsPerBlock) {
  const id = `scale-text-${index}`
  const value = text(index, wordsPerBlock)
  switch (index % 50) {
    case 1:
      return { kind: 'heading', id, level: 2, content: inline(value), align: 'start', style: 'NONE' }
    case 2: {
      const parts = value.split(' ')
      const midpoint = Math.floor(parts.length / 2)
      return {
        kind: 'bullet_list',
        id,
        style: 'NONE',
        items: [
          { id: `${id}-item-1`, content: inline(parts.slice(0, midpoint).join(' ')), children: [] },
          {
            id: `${id}-item-2`,
            content: inline(parts.slice(midpoint).join(' ')),
            children: [{ id: `${id}-item-2-1`, content: inline('nested item'), children: [] }],
          },
        ],
      }
    }
    case 3: {
      const parts = value.split(' ')
      const midpoint = Math.floor(parts.length / 2)
      return {
        kind: 'numbered_list',
        id,
        start: 3,
        style: 'NONE',
        items: [
          { id: `${id}-item-1`, content: inline(parts.slice(0, midpoint).join(' ')), children: [] },
          { id: `${id}-item-2`, content: inline(parts.slice(midpoint).join(' ')), children: [] },
        ],
      }
    }
    case 4:
      return { kind: 'quote', id, style: 'NONE', children: [paragraph(`${id}-body`, value)] }
    case 5:
      return {
        kind: 'callout',
        id,
        tone: 'note',
        title: 'Scale note',
        collapsed: false,
        style: 'NONE',
        children: [paragraph(`${id}-body`, value)],
      }
    case 6:
      return { kind: 'code', id, language: 'text', code: value, editorHeightDp: 180, style: 'NONE' }
    default:
      return paragraph(id, value)
  }
}

function objectBlock(index) {
  const id = `scale-object-${index}`
  switch (OBJECT_KINDS[index % OBJECT_KINDS.length]) {
    case 'image':
      return { kind: 'image', id, source: 'image://scale', caption: `Scale image ${index}`, layout: 'Fit', displayHeightDp: 220 }
    case 'table':
      return { kind: 'table', id, headers: ['A', 'B'], rows: [['1', '2'], ['3', '4']], columnWidthsDp: [120, 120], columnAlignments: ['start', 'end'] }
    case 'chart':
      return { kind: 'chart', id, vegaLiteSpec: JSON.stringify({ data: { values: [{ label: 'A', value: 1 }, { label: 'B', value: 2 }] } }), editorHeightDp: 180 }
    case 'math':
      return { kind: 'math', id, tex: 'x^2 + y^2', display: true, editorHeightDp: 140 }
    case 'file':
      return { kind: 'file', id, name: `scale-${index}.pdf`, mimeType: 'application/pdf', sizeBytes: 4096, uri: `file://scale-${index}` }
    case 'embed':
      return { kind: 'embed', id, url: `https://example.test/${index}`, metadata: { title: `Embed ${index}`, description: 'Local scale fixture', faviconPath: null }, displayHeightDp: 112 }
    case 'mermaid':
      return { kind: 'mermaid', id, code: 'graph TD; A-->B;', editorHeightDp: 180 }
    case 'shape':
      return { kind: 'shape', id, shapeKind: 'RoundedRectangle', text: `Shape ${index}`, fillColor: 4292927712, strokeColor: 4281545523, strokeWidthDp: 2, displayHeightDp: 180, rotationDegrees: 0, style: 'NONE' }
    default:
      return { kind: 'unknown', id, rawJson: JSON.stringify({ v: 99, block: { kind: 'future_scale', id, index } }) }
  }
}

export function createScaleFixture(tier) {
  const config = tier === 'normal'
    ? { total: 250, atoms: 20, words: 80 }
    : tier === 'large'
      ? { total: 1_000, atoms: 40, words: 100 }
      : null
  if (!config) throw new Error(`Unknown Stage-2C fixture tier: ${tier}`)

  const objectPositions = new Set()
  for (let index = 0; index < config.atoms; index += 1) {
    objectPositions.add(Math.floor(((index + 0.5) * config.total) / config.atoms))
  }

  const fixture = []
  let textIndex = 0
  let objectIndex = 0
  for (let index = 0; index < config.total; index += 1) {
    const block = objectPositions.has(index)
      ? objectBlock(objectIndex++)
      : textBlock(textIndex++, config.words)
    fixture.push({ v: 1, block })
  }
  return fixture
}

function inlineWords(nodes) {
  if (!Array.isArray(nodes)) return 0
  return nodes.reduce((total, node) => {
    if (typeof node.value === 'string') return total + node.value.trim().split(/\s+/).filter(Boolean).length
    return total + inlineWords(node.children)
  }, 0)
}

export function fixtureStats(fixture) {
  const ids = []
  let wordCount = 0
  let atomCount = 0

  function visit(block) {
    if (block.id) ids.push(block.id)
    if (OBJECT_KINDS.includes(block.kind)) atomCount += 1
    wordCount += inlineWords(block.content)
    if (typeof block.code === 'string') wordCount += block.code.trim().split(/\s+/).filter(Boolean).length
    for (const child of block.children || []) visit(child)
    for (const item of block.items || []) {
      if (item.id) ids.push(item.id)
      wordCount += inlineWords(item.content)
      for (const child of item.children || []) {
        if (child.id) ids.push(child.id)
        wordCount += inlineWords(child.content)
      }
    }
  }

  for (const entry of fixture) visit(entry.block)
  const seen = new Set()
  const duplicateIds = []
  for (const id of ids) {
    if (seen.has(id)) duplicateIds.push(id)
    seen.add(id)
  }
  return {
    topLevelBlocks: fixture.length,
    wordCount,
    atomCount,
    idCount: ids.length,
    uniqueIdCount: seen.size,
    duplicateIds,
    canonicalBytes: JSON.stringify(fixture).length,
  }
}
