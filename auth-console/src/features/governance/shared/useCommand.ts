import { useRef, useState } from 'react';
import { isAxiosError, HttpStatusCode } from 'axios';

/** 超时/5xx保留已冻结命令和payload，按钮重试原命令；不能重新生成时间或幂等键。 */
export function useCommand<P, R>(execute: (payload: P) => Promise<R>) {
  const intent = useRef<P>();
  // React状态在下一次渲染才可见；引用负责同一事件内的互斥与未知结果围栏。
  const inFlight = useRef(false);
  const unresolved = useRef(false);
  const [busy, setBusy] = useState(false);
  const [unknown, setUnknown] = useState(false);
  const [error, setError] = useState<unknown>();
  const [result, setResult] = useState<R>();
  const send = async (build: (commandId: string) => P): Promise<R | undefined> => {
    if (inFlight.current) return;
    if (!intent.current) intent.current = build(crypto.randomUUID());
    inFlight.current = true;
    setBusy(true);
    setError(undefined);
    try {
      const response = await execute(intent.current);
      setResult(response);
      intent.current = undefined;
      unresolved.current = false;
      setUnknown(false);
      return response;
    } catch (failure) {
      setError(failure);
      const status = isAxiosError(failure) ? failure.response?.status : undefined;
      const definitive =
        status !== undefined &&
        status >= HttpStatusCode.BadRequest &&
        status < HttpStatusCode.InternalServerError &&
        status !== HttpStatusCode.RequestTimeout &&
        status !== HttpStatusCode.TooManyRequests;
      if (definitive) intent.current = undefined;
      unresolved.current = !definitive;
      setUnknown(!definitive);
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  };
  const reset = () => {
    if (!inFlight.current && !unresolved.current) {
      intent.current = undefined;
      setError(undefined);
      setResult(undefined);
    }
  };
  return { busy, unknown, error, result, send, reset };
}
