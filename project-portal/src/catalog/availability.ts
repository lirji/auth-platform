import type { ProjectEntry, ProjectReachability } from './types'

export type AvailabilityRequest = (url: string, init: RequestInit) => Promise<unknown>

const DEFAULT_TIMEOUT_MS = 3_500

/**
 * 只判断目标是否可达，不读取跨域响应，也不携带用户凭据。
 * no-cors 请求成功完成即视为在线；超时、CSP 拒绝或网络错误均视为离线。
 */
export async function probeProjectReachability(
  project: ProjectEntry,
  request: AvailabilityRequest = fetch,
  timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<ProjectReachability> {
  if (project.status !== 'available' || !project.healthUrl) return 'unchecked'

  const controller = new AbortController()
  const timeout = globalThis.setTimeout(() => controller.abort(), timeoutMs)
  try {
    await request(project.healthUrl, {
      method: 'GET',
      mode: 'no-cors',
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'follow',
      referrerPolicy: 'no-referrer',
      signal: controller.signal,
    })
    return 'online'
  } catch {
    return 'offline'
  } finally {
    globalThis.clearTimeout(timeout)
  }
}
