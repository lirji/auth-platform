import { useMemo, useState } from 'react'
import type { ProjectEntry } from './types'
import { applySavedOrder, moveVisible, readStoredOrder, writeStoredOrder } from './projectOrder'

/** 把用户拖动后的顺序叠在 catalog 默认 order 上，并写入 localStorage。 */
export function useProjectOrder(projects: ProjectEntry[]) {
  const [savedIds, setSavedIds] = useState<string[]>(() => readStoredOrder())
  const ordered = useMemo(() => applySavedOrder(projects, savedIds), [projects, savedIds])
  const catalogIds = useMemo(() => projects.map((project) => project.id).join('\0'), [projects])
  const hasCustomOrder = ordered.map((project) => project.id).join('\0') !== catalogIds

  const persist = (ids: string[]) => {
    setSavedIds(ids)
    writeStoredOrder(ids)
  }

  const moveTo = (fromId: string, toId: string, visible: ProjectEntry[]) => {
    if (fromId === toId) return
    const visibleIds = visible.map((project) => project.id)
    const from = visibleIds.indexOf(fromId)
    const to = visibleIds.indexOf(toId)
    persist(moveVisible(ordered.map((project) => project.id), visibleIds, from, to))
  }

  const moveBy = (id: string, delta: number, visible: ProjectEntry[]) => {
    const visibleIds = visible.map((project) => project.id)
    const from = visibleIds.indexOf(id)
    moveTo(id, visibleIds[from + delta] ?? id, visible)
  }

  const reset = () => persist([])

  return { ordered, moveTo, moveBy, reset, hasCustomOrder }
}
