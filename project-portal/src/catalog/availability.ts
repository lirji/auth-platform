import type { ProjectEntry, ProjectReachability } from './types'

export type AvailabilityResponse = Pick<Response, 'ok'>
export type AvailabilityRequest = (url: string, init: RequestInit) => Promise<AvailabilityResponse>

const DEFAULT_TIMEOUT_MS = 3_500

/**
 * 请求目标项目显式开放 CORS 的健康端点，不携带用户凭据。
 * 仅 2xx 响应视为在线；非 2xx、超时、CSP/CORS 拒绝或网络错误均视为离线。
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
    const response = await request(project.healthUrl, {
      method: 'GET',
      mode: 'cors',
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'follow',
      referrerPolicy: 'no-referrer',
      signal: controller.signal,
    })
    return response.ok ? 'online' : 'offline'
  } catch {
    return 'offline'
  } finally {
    globalThis.clearTimeout(timeout)
  }
}
