import { Plugin } from 'prosemirror-state'

function uuidv4() {
  const bytes = new Uint8Array(16)
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) crypto.getRandomValues(bytes)
  else for (let i = 0; i < 16; i += 1) bytes[i] = Math.floor(Math.random() * 256)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function generateBlockId() {
  return uuidv4()
}

// AppendTransaction plugin: after any doc-changing transaction, the second and
// later occurrences of a duplicated non-null block id are reassigned, so split
// and same-document paste preserve stable unique identity (B1.6 / A6.1).
export function uniqueBlockIdPlugin(generateId = generateBlockId) {
  return new Plugin({
    appendTransaction(transactions, _oldState, newState) {
      if (!transactions.some((tr) => tr.docChanged)) return null
      const seen = new Set()
      const dups = []
      newState.doc.descendants((node, pos) => {
        if (node.isText) return
        const id = node.attrs?.id
        if (id == null) return
        if (seen.has(id)) dups.push(pos)
        else seen.add(id)
      })
      if (!dups.length) return null
      let tr = newState.tr
      for (const pos of dups) {
        const node = tr.doc.nodeAt(pos)
        if (!node) continue
        tr = tr.setNodeMarkup(pos, undefined, { ...node.attrs, id: generateId() })
      }
      return tr
    },
  })
}
