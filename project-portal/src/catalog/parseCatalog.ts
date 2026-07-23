import type { Catalog, OpenMode, ParsedCatalog, ProjectEntry, ProjectStatus } from './types'

const ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/
const STATUS = new Set<ProjectStatus>(['available', 'maintenance', 'coming-soon'])
const OPEN_MODE = new Set<OpenMode>(['same-tab', 'new-tab'])

function recordOf(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
}

function text(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null
  const normalized = value.trim()
  return normalized && normalized.length <= max ? normalized : null
}

function texts(value: unknown, maxItems: number, maxLength: number): string[] | null {
  if (!Array.isArray(value) || value.length > maxItems) return null
  const result: string[] = []
  for (const item of value) {
    const normalized = text(item, maxLength)
    if (!normalized) return null
    if (!result.includes(normalized)) result.push(normalized)
  }
  return result
}

function isLoopback(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '')
  return host === 'localhost' || host === '::1' || /^127(?:\.\d{1,3}){3}$/.test(host)
}

function safeProjectUrl(value: unknown, allowHttpLocalhost: boolean): URL | null {
  const raw = text(value, 2048)
  if (!raw) return null
  try {
    const parsed = new URL(raw)
    if (parsed.username || parsed.password) return null
    if (parsed.protocol === 'https:') return parsed
    if (parsed.protocol === 'http:' && allowHttpLocalhost && isLoopback(parsed.hostname)) return parsed
    return null
  } catch {
    return null
  }
}

function projectOf(
  value: unknown,
  index: number,
  allowHttpLocalhost: boolean,
  seenIds: Set<string>,
  issues: string[],
): ProjectEntry | null {
  const raw = recordOf(value)
  if (!raw) {
    issues.push(`projects[${index}] 不是对象`)
    return null
  }

  if (raw.enabled === false) return null
  const id = text(raw.id, 64)
  if (!id || !ID_PATTERN.test(id)) {
    issues.push(`projects[${index}] id 非法`)
    return null
  }
  if (seenIds.has(id)) {
    issues.push(`项目 id 重复: ${id}`)
    return null
  }

  const name = text(raw.name, 80)
  const summary = text(raw.summary, 240)
  const category = text(raw.category, 40)
  const capabilities = texts(raw.capabilities, 10, 40)
  const tags = raw.tags === undefined ? [] : texts(raw.tags, 10, 30)
  const icon = raw.icon === undefined ? 'default' : text(raw.icon, 30)
  const status = raw.status as ProjectStatus
  const openMode = raw.openMode === undefined ? 'new-tab' : (raw.openMode as OpenMode)
  const order = raw.order === undefined ? 100 : raw.order

  if (!name || !summary || !category || !capabilities || !tags || !icon) {
    issues.push(`项目 ${id} 的展示字段非法`)
    return null
  }
  if (!STATUS.has(status)) {
    issues.push(`项目 ${id} status 非法`)
    return null
  }
  if (!OPEN_MODE.has(openMode)) {
    issues.push(`项目 ${id} openMode 非法`)
    return null
  }
  if (!Number.isSafeInteger(order) || (order as number) < -10000 || (order as number) > 10000) {
    issues.push(`项目 ${id} order 非法`)
    return null
  }

  const launch = raw.launchUrl === undefined ? null : safeProjectUrl(raw.launchUrl, allowHttpLocalhost)
  if (status === 'available' && !launch) {
    issues.push(`项目 ${id} 缺少安全的 launchUrl`)
    return null
  }
  if (raw.launchUrl !== undefined && !launch) {
    issues.push(`项目 ${id} launchUrl 非法`)
    return null
  }

  const health = raw.healthUrl === undefined ? null : safeProjectUrl(raw.healthUrl, allowHttpLocalhost)
  if (raw.healthUrl !== undefined && !health) {
    issues.push(`项目 ${id} healthUrl 非法`)
    return null
  }
  if (health && (!launch || health.origin !== launch.origin)) {
    issues.push(`项目 ${id} healthUrl 必须与 launchUrl 同源`)
    return null
  }

  seenIds.add(id)
  return {
    id,
    name,
    summary,
    category,
    capabilities,
    tags,
    icon,
    status,
    launchUrl: launch?.toString(),
    healthUrl: health?.toString(),
    displayHost: launch?.host,
    openMode,
    order: order as number,
  }
}

export function parseCatalog(value: unknown): ParsedCatalog {
  const root = recordOf(value)
  if (!root || root.schemaVersion !== 1) {
    throw new Error('不支持的能力目录版本')
  }
  if (!Array.isArray(root.projects) || root.projects.length > 100) {
    throw new Error('能力目录 projects 必须是最多 100 项的数组')
  }
  const allowHttpLocalhost = root.allowHttpLocalhost === true
  const issues: string[] = []
  const seenIds = new Set<string>()
  const projects = root.projects
    .map((item, index) => projectOf(item, index, allowHttpLocalhost, seenIds, issues))
    .filter((item): item is ProjectEntry => item !== null)
    .sort((a, b) => a.order - b.order || a.name.localeCompare(b.name, 'zh-CN') || a.id.localeCompare(b.id))

  const updatedAt = root.updatedAt === undefined ? undefined : text(root.updatedAt, 40) ?? undefined
  const catalog: Catalog = { schemaVersion: 1, updatedAt, projects }
  return { catalog, issues }
}
