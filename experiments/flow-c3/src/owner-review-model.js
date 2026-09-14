export const MIN_OBJECT_HEIGHT = 120
export const MAX_OBJECT_HEIGHT = 420

export function resizedHeight(startHeight, deltaY) {
  return Math.round(Math.max(MIN_OBJECT_HEIGHT, Math.min(MAX_OBJECT_HEIGHT, startHeight + deltaY)))
}

export function chartValues(specJson) {
  try {
    const values = JSON.parse(specJson)?.data?.values
    if (!Array.isArray(values)) return []
    return values
      .map((entry) => ({
        label: String(entry.month ?? entry.label ?? entry.category ?? ''),
        value: Number(entry.value ?? entry.amount ?? 0),
      }))
      .filter((entry) => entry.label && Number.isFinite(entry.value))
  } catch (_error) {
    return []
  }
}
