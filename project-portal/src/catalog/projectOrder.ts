/** 能力门户项目卡片顺序：本机偏好，不写回 catalog.json。 */

export const PROJECT_ORDER_STORAGE_KEY = 'capability-portal.project-order.v1'

export function readStoredOrder(): string[] {
  if (typeof localStorage === 'undefined') return []
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(PROJECT_ORDER_STORAGE_KEY) ?? '')
    return Array.isArray(parsed) && parsed.every((id) => typeof id === 'string') ? parsed : []
  } catch {
    return []
  }
}

export function writeStoredOrder(ids: string[]): void {
  if (typeof localStorage === 'undefined') return
  if (ids.length === 0) {
    localStorage.removeItem(PROJECT_ORDER_STORAGE_KEY)
    return
  }
  localStorage.setItem(PROJECT_ORDER_STORAGE_KEY, JSON.stringify(ids))
}

export function applySavedOrder<T extends { id: string }>(items: T[], savedIds: string[]): T[] {
  const byId = new Map(items.map((item) => [item.id, item]))
  const seen = new Set<string>()
  const ordered: T[] = []
  for (const id of savedIds) {
    const item = byId.get(id)
    if (item && !seen.has(id)) {
      ordered.push(item)
      seen.add(id)
    }
  }
  for (const item of items) {
    if (!seen.has(item.id)) ordered.push(item)
  }
  return ordered
}

export function moveVisible(fullIds: string[], visibleIds: string[], from: number, to: number): string[] {
  if (
    from === to
    || from < 0
    || to < 0
    || from >= visibleIds.length
    || to >= visibleIds.length
  ) {
    return fullIds
  }
  const nextVisible = visibleIds.slice()
  const [moved] = nextVisible.splice(from, 1)
  nextVisible.splice(to, 0, moved)
  const visible = new Set(visibleIds)
  let index = 0
  return fullIds.map((id) => (visible.has(id) ? nextVisible[index++] : id))
}
