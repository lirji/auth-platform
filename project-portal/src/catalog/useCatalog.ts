import { useCallback, useEffect, useState } from 'react'
import { parseCatalog } from './parseCatalog'
import type { ParsedCatalog } from './types'

type CatalogState =
  | { kind: 'loading' }
  | { kind: 'ready'; value: ParsedCatalog }
  | { kind: 'error'; message: string }

export function useCatalog() {
  const [state, setState] = useState<CatalogState>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)

  const retry = useCallback(() => {
    setState({ kind: 'loading' })
    setAttempt((value) => value + 1)
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void fetch('/config/catalog.json', { cache: 'no-store', signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`能力目录请求失败 (${response.status})`)
        return response.json() as Promise<unknown>
      })
      .then((value) => setState({ kind: 'ready', value: parseCatalog(value) }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setState({ kind: 'error', message: error instanceof Error ? error.message : '能力目录无法加载' })
      })
    return () => controller.abort()
  }, [attempt])

  return { state, retry }
}
