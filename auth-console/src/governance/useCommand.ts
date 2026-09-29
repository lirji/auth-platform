import { useRef, useState } from 'react'
import { isAxiosError, HttpStatusCode } from 'axios'

/** 超时/5xx保留已冻结命令和payload，按钮重试原命令；不能重新生成时间或幂等键。 */
export function useCommand<P, R>(execute: (payload: P) => Promise<R>) {
  const intent = useRef<P>()
  const [busy, setBusy] = useState(false)
  const [unknown, setUnknown] = useState(false)
  const [error, setError] = useState<unknown>()
  const [result, setResult] = useState<R>()
  const send = async (build: (commandId: string) => P): Promise<R | undefined> => {
    if (busy) return
    if (!intent.current) intent.current = build(crypto.randomUUID())
    setBusy(true); setError(undefined)
    try {
      const response = await execute(intent.current)
      setResult(response); intent.current = undefined; setUnknown(false)
      return response
    } catch (failure) {
      setError(failure)
      const status = isAxiosError(failure) ? failure.response?.status : undefined
      const definitive = status !== undefined && status >= HttpStatusCode.BadRequest && status < HttpStatusCode.InternalServerError
        && status !== HttpStatusCode.RequestTimeout && status !== HttpStatusCode.TooManyRequests
      if (definitive) intent.current = undefined
      setUnknown(!definitive)
    } finally { setBusy(false) }
  }
  const reset = () => { if (!busy && !unknown) { intent.current = undefined; setError(undefined); setResult(undefined) } }
  return { busy, unknown, error, result, send, reset }
}
