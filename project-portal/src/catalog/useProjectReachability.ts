import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { probeProjectReachability } from './availability'
import type { ProjectEntry, ProjectReachability } from './types'

const REFRESH_INTERVAL_MS = 30_000

export function useProjectReachability(projects: ProjectEntry[]) {
  const [reachability, setReachability] = useState<Record<string, ProjectReachability>>({})
  const runId = useRef(0)
  const checkableProjects = useMemo(
    () => projects.filter((project) => project.status === 'available' && project.healthUrl),
    [projects],
  )

  const refresh = useCallback(async () => {
    const currentRun = ++runId.current
    setReachability(Object.fromEntries(checkableProjects.map((project) => [project.id, 'checking'])))
    const results = await Promise.all(
      checkableProjects.map(async (project) => [project.id, await probeProjectReachability(project)] as const),
    )
    if (currentRun === runId.current) setReachability(Object.fromEntries(results))
  }, [checkableProjects])

  useEffect(() => {
    void refresh()
    const interval = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refresh()
    }, REFRESH_INTERVAL_MS)
    const refreshWhenOnline = () => void refresh()
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') void refresh()
    }
    window.addEventListener('online', refreshWhenOnline)
    document.addEventListener('visibilitychange', refreshWhenVisible)
    return () => {
      ++runId.current
      window.clearInterval(interval)
      window.removeEventListener('online', refreshWhenOnline)
      document.removeEventListener('visibilitychange', refreshWhenVisible)
    }
  }, [refresh])

  const checking = checkableProjects.some(
    (project) => (reachability[project.id] ?? 'checking') === 'checking',
  )

  return { reachability, refresh, checking }
}
