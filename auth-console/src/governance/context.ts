import type { Partition } from '../api/governance';

/** 缓存按发行方、主体、成员及完整应用分区隔离；URL选择没有授权效力。 */
export function contextKey(
  subject: string,
  member: string,
  generation: number,
  partition: Partition,
) {
  return [
    'governance',
    subject,
    member,
    generation,
    partition.tenant_id,
    partition.application_id,
    partition.environment,
  ] as const;
}
/** 更换企业清空应用/游标/详情，不允许标签页共享的本地存储暗中覆盖上下文。 */
export function organizationSearch(tenant: string): URLSearchParams {
  return new URLSearchParams({ tenant });
}
/** 仅给服务端已登记入口增加浏览器防御，不允许脚本协议、凭据或Token参数。 */
export function safeEntry(href: string | null): string | undefined {
  if (!href) return undefined;
  try {
    const url = new URL(href);
    if (url.username || url.password || url.hash || url.search) return undefined;
    if (
      url.protocol !== 'https:' &&
      !(url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname))
    )
      return undefined;
    return url.href;
  } catch {
    return undefined;
  }
}

/** 跳转只附当前租户与环境选择，业务应用仍以独立OIDC身份重新验证。 */
export function contextualEntry(
  href: string | null,
  tenant: string,
  environment: string,
): string | undefined {
  const safe = safeEntry(href);
  if (!safe) return undefined;
  const url = new URL(safe);
  url.searchParams.set('tenant_id', tenant);
  url.searchParams.set('environment', environment);
  return url.href;
}

/** 清除粘贴链接中的HTML转义残留；显式的规范字段优先，不从URL推断授权。 */
export function normalizedSearch(params: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(params);
  for (const key of ['application', 'environment']) {
    const alias = `amp;${key}`;
    if (!next.has(key) && next.has(alias)) next.set(key, next.get(alias)!);
    next.delete(alias);
  }
  return next;
}

/** 页面导航只保留目录位置和应用分区，避免把前一页详情/游标带入新任务。 */
export function applicationSearch(
  params: URLSearchParams,
  application: string,
  environment: string,
): URLSearchParams {
  const next = organizationSearch(params.get('tenant') ?? '');
  if (params.get('after')) next.set('after', params.get('after')!);
  next.set('application', application);
  next.set('environment', environment);
  return next;
}
