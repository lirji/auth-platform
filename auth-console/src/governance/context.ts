import type { Partition } from '../api/governance'

/** 缓存按发行方、主体、成员及完整应用分区隔离；URL选择没有授权效力。 */
export function contextKey(subject: string, member: string, generation: number, partition: Partition) {
  return ['governance', subject, member, generation, partition.tenant_id, partition.application_id, partition.environment] as const
}
/** 更换企业清空应用/游标/详情，不允许标签页共享的本地存储暗中覆盖上下文。 */
export function organizationSearch(tenant: string): URLSearchParams {
  return new URLSearchParams({ tenant })
}
/** 仅给服务端已登记入口增加浏览器防御，不允许脚本协议、凭据或Token参数。 */
export function safeEntry(href: string | null): string | undefined {
  if (!href) return undefined
  try {
    const url = new URL(href)
    if (url.username || url.password || url.hash || url.search) return undefined
    if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname))) return undefined
    return url.href
  } catch { return undefined }
}
