import assert from 'node:assert/strict'
import { Schema } from 'prosemirror-model'
import { roundTripCanonical } from '../src/block-document-projection.js'

const atom = (extra) => ({ group: 'block', atom: true, attrs: { id: { default: null }, a11yName: { default: 'Object' }, ...extra } })
const nodes = {
  doc: { content: 'block+' },
  paragraph: { content: 'inline*', group: 'block', attrs: { id: { default: null }, align: { default: 'start' }, style: { default: 'NONE' } } },
  text: { group: 'inline' },
  image_atom: atom({ source: { default: '' }, caption: { default: '' }, layout: { default: 'Fit' }, displayHeightDp: { default: 220 }, imageEditDocument: { default: null } }),
  table_atom: atom({ headers: { default: [] }, rows: { default: [] }, columnWidthsDp: { default: [] }, columnAlignments: { default: [] }, calculationPayload: { default: null }, calculationTableId: { default: null }, calculationSchemaVersion: { default: null }, legacyRecovery: { default: null } }),
  chart_atom: atom({ vegaLiteSpec: { default: '{}' }, editorHeightDp: { default: 180 }, chartDocument: { default: null } }),
  file_atom: atom({ name: { default: '' }, mimeType: { default: 'application/octet-stream' }, sizeBytes: { default: 0 }, uri: { default: '' } }),
  embed_atom: atom({ url: { default: '' }, metadata: { default: {} }, displayHeightDp: { default: 112 } }),
  mermaid_atom: atom({ code: { default: '' }, editorHeightDp: { default: 180 }, diagramEditDocument: { default: null } }),
  shape_atom: atom({ shapeKind: { default: 'RoundedRectangle' }, text: { default: '' }, fillColor: { default: 0 }, strokeColor: { default: 0 }, strokeWidthDp: { default: 2 }, displayHeightDp: { default: 180 }, rotationDegrees: { default: 0 }, vectorAsset: { default: null }, fillPaint: { default: null }, strokePaint: { default: null }, style: { default: 'NONE' }, shapeEditDocument: { default: null } }),
  unknown_atom: atom({ rawJson: { default: '' } }),
}
const schema = new Schema({ nodes })

const doc = [
  { v: 1, block: { kind: 'image', id: 'img9', source: 'image://x', caption: 'Cap', layout: 'Fit', displayHeightDp: 220, imageEditDocument: '{"edits":[]}' } },
  { v: 1, block: { kind: 'table', id: 't9', headers: [], rows: [], columnWidthsDp: [], columnAlignments: [], calculationPayload: '{"wb":1}', calculationTableId: 'ct1', calculationSchemaVersion: 3, legacyRecovery: { headers: [], rows: [], columnWidthsDp: [], columnAlignments: [] } } },
  { v: 1, block: { kind: 'chart', id: 'ch9', vegaLiteSpec: '{}', editorHeightDp: 180, chartDocument: '{"chart":2}' } },
  { v: 1, block: { kind: 'file', id: 'f1', name: 'deck.pdf', mimeType: 'application/pdf', sizeBytes: 41002, uri: 'file://deck' } },
  { v: 1, block: { kind: 'embed', id: 'e1', url: 'https://example.test/v', metadata: { title: 'Vid', description: 'A video', faviconPath: null }, displayHeightDp: 112 } },
  { v: 1, block: { kind: 'mermaid', id: 'm1', code: 'graph TD; A-->B;', editorHeightDp: 180, diagramEditDocument: '{"geom":true}' } },
  { v: 1, block: { kind: 'shape', id: 's1', shapeKind: 'Ellipse', text: 'Hi', fillColor: 4284900966, strokeColor: 4291240959, strokeWidthDp: 2, displayHeightDp: 180, rotationDegrees: 15, fillPaint: { kind: 'solid', color: 4284900966 }, style: 'NONE', shapeEditDocument: '{"s":1}' } },
  { v: 1, block: { kind: 'unknown', id: 'u9', rawJson: '{"v":99,"block":{"kind":"hologram","id":"u9","depth":3}}' } },
]

assert.deepEqual(roundTripCanonical(doc, schema), doc)

console.log('PASS stage2b file/embed/mermaid/shape + additive payloads')
