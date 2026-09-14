export const HISTORY_DEBOUNCE_MS = 300
export const HISTORY_STACK_CAP = 200

function copy(value) {
  return JSON.parse(JSON.stringify(value))
}

export function createCanonicalHistory({
  getSnapshot,
  getSelection,
  restore,
  now = () => Date.now(),
  debounceMs = HISTORY_DEBOUNCE_MS,
  maxEntries = HISTORY_STACK_CAP,
}) {
  let base = { blockDocJson: getSnapshot(), selection: copy(getSelection()) }
  let entries = []
  let pointer = -1
  let timer = null
  let disposed = false

  function capture() {
    timer = null
    if (disposed) return
    const blockDocJson = getSnapshot()
    if (blockDocJson === (pointer < 0 ? base.blockDocJson : entries[pointer].blockDocJson)) return
    entries = entries.slice(0, pointer + 1)
    entries.push({ blockDocJson, selection: copy(getSelection()), timestamp: now() })
    if (entries.length > maxEntries) {
      const dropped = entries.shift()
      base = { blockDocJson: dropped.blockDocJson, selection: copy(dropped.selection) }
    }
    pointer = entries.length - 1
  }

  function flush() {
    clearTimeout(timer)
    if (timer) capture()
  }

  function updateCurrentSelection() {
    const selection = copy(getSelection())
    if (pointer < 0) base.selection = selection
    else entries[pointer].selection = selection
  }

  function reset() {
    clearTimeout(timer)
    timer = null
    base = { blockDocJson: getSnapshot(), selection: copy(getSelection()) }
    entries = []
    pointer = -1
  }

  return {
    onTransaction(transaction) {
      if (disposed) return
      if (transaction.getMeta('canonicalHistoryRestore')) return
      if (!transaction.docChanged) {
        if (transaction.selectionSet && !timer) updateCurrentSelection()
        return
      }
      clearTimeout(timer)
      timer = setTimeout(capture, debounceMs)
    },
    flush,
    reset,
    dispose() {
      clearTimeout(timer)
      timer = null
      disposed = true
    },
    undo() {
      flush()
      if (pointer < 0) return { ok: false, reason: 'stack-empty' }
      pointer -= 1
      const entry = pointer < 0 ? base : entries[pointer]
      restore(entry.blockDocJson, entry.selection)
      return { ok: true }
    },
    redo() {
      flush()
      if (pointer >= entries.length - 1) return { ok: false, reason: 'stack-empty' }
      pointer += 1
      const entry = entries[pointer]
      restore(entry.blockDocJson, entry.selection)
      return { ok: true }
    },
    info() {
      return {
        source: 'canonical-block-document',
        entries: entries.length,
        pointer,
        snapshots: copy(entries),
      }
    },
  }
}
