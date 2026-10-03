import type { Partition, PublishedCatalog, PublishedResourceType, Role } from '../api/governance'

const kinds = new Set(['TENANT_ALL', 'SPECIFIED_STORES', 'SPECIFIED_RESOURCES'])
const code = /^[a-z][a-z0-9._-]{0,99}$/
const positive = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const validCode = (value: unknown): value is string => typeof value === 'string' && code.test(value)

/** 目录有界且引用完整；服务端异常响应不能生成扩大范围的表单选项。 */
export function validatePublishedCatalog(value: PublishedCatalog, partition: Partition): PublishedCatalog {
  const fail = () => { throw new Error('已发布目录响应不完整，请重新读取当前应用目录') }
  if (!value || value.tenant_id !== partition.tenant_id || value.application_id !== partition.application_id || value.environment !== partition.environment
    || typeof value.membership_id !== 'string' || !positive(value.generation) || !positive(value.max_duration_seconds) || !positive(value.manifest_version)
    || !/^[a-f0-9]{64}$/.test(value.content_hash) || !/^[a-f0-9]{64}$/.test(value.view_hash)
    || !Array.isArray(value.capabilities) || !value.capabilities.length || value.capabilities.length > 200
    || !Array.isArray(value.menus) || value.menus.length > 100 || !Array.isArray(value.resource_types) || value.resource_types.length > 200) fail()
  const caps = new Set<string>()
  const types = new Set<string>()
  for (const cap of value.capabilities) {
    if (!cap || !validCode(cap.code) || !cap.code.startsWith(`${partition.application_id}.`) || caps.has(cap.code) || !validCode(cap.resource_type)
      || !['NORMAL', 'HIGH'].includes(cap.risk_level) || typeof cap.disabled !== 'boolean' || typeof cap.grantable !== 'boolean' || cap.disabled && cap.grantable) fail()
    caps.add(cap.code); types.add(cap.resource_type)
  }
  const resources = new Set<string>()
  for (const resource of value.resource_types) {
    if (!resource || !validCode(resource.code) || resources.has(resource.code) || !types.has(resource.code) || typeof resource.scope_supported !== 'boolean'
      || !Array.isArray(resource.allowed_scope_kinds) || resource.allowed_scope_kinds.length > kinds.size
      || new Set(resource.allowed_scope_kinds).size !== resource.allowed_scope_kinds.length
      || resource.allowed_scope_kinds.some(kind => !kinds.has(kind)) || resource.scope_supported !== (resource.allowed_scope_kinds.length > 0)) fail()
    resources.add(resource.code)
  }
  if (resources.size !== types.size) fail()
  const menus = new Map(value.menus.map(menu => [menu.code, menu]))
  if (menus.size !== value.menus.length) fail()
  const positions = new Set<number>()
  for (const menu of value.menus) {
    if ((menu.label == null) !== (menu.position == null)) fail()
    if (menu.label != null) {
      if (typeof menu.label !== 'string' || !menu.label.trim() || menu.label.trim() !== menu.label || [...menu.label].length > 80 || /[\u0000-\u001f\u007f-\u009f<>]/.test(menu.label)
        || !Number.isSafeInteger(menu.position) || menu.position! < 0 || menu.position! >= 100 || positions.has(menu.position!)) fail()
      positions.add(menu.position!)
    }
    if (!menu || !validCode(menu.code) || menu.parent !== null && !validCode(menu.parent)
      || menu.route !== null && (typeof menu.route !== 'string' || !/^\/[a-zA-Z0-9/_-]*$/.test(menu.route) || menu.route.includes('//'))
      || !Array.isArray(menu.any_of) || menu.any_of.length > 200 || new Set(menu.any_of).size !== menu.any_of.length
      || menu.any_of.some(cap => !caps.has(cap)) || menu.route !== null && !menu.any_of.length) fail()
    const seen = new Set<string>()
    let current: typeof menu | undefined = menu
    while (current) {
      if (seen.has(current.code)) fail()
      seen.add(current.code)
      if (current.parent !== null && !menus.has(current.parent)) fail()
      current = current.parent === null ? undefined : menus.get(current.parent)
    }
  }
  return value
}

/** 固定角色的全部能力须同一资源且均可新选，不能以部分交集掩盖混资源/越权。 */
export function roleScopeEligibility(role: Role | undefined, catalog: PublishedCatalog): { resourceTypes: PublishedResourceType[]; reason?: string } {
  if (!role || !role.capabilities.length) return { resourceTypes: [], reason: '请选择当前页的固定角色版本' }
  const caps = role.capabilities.map(code => catalog.capabilities.find(cap => cap.code === code))
  if (caps.some(cap => !cap)) return { resourceTypes: [], reason: '角色含当前清单未登记的能力' }
  if (caps.some(cap => !cap!.grantable)) return { resourceTypes: [], reason: '角色含已停用或超出当前委派的能力' }
  const types = new Set(caps.map(cap => cap!.resource_type))
  if (types.size !== 1) return { resourceTypes: [], reason: '此固定角色包含多种资源，请创建单资源新版本后授予' }
  const resource = catalog.resource_types.find(resource => types.has(resource.code))
  if (!resource?.scope_supported || !resource.allowed_scope_kinds.length) return { resourceTypes: [], reason: '此资源尚未登记可执行的数据范围绑定' }
  return { resourceTypes: [resource] }
}

/** 复制的旧能力保留可见，只有显式纠正后才能创建新单资源版本。 */
export function capabilitySelectionError(catalog: PublishedCatalog, resource: string | undefined, codes: string[]): string | undefined {
  if (!resource) return '请选择真实资源类型'
  if (!codes.length) return '至少选择一项当前可授予能力'
  if (codes.some(code => !catalog.capabilities.some(cap => cap.code === code && cap.resource_type === resource && cap.grantable))) return '请明确移除未登记、停用、超委派或其他资源的能力'
  return undefined
}

/** 菜单只决定浏览集合；父菜单递归上下文不改变任何选中状态。 */
export function menuCapabilityCodes(catalog: PublishedCatalog, selected: string | undefined): Set<string> | undefined {
  if (!selected) return undefined
  const menus = new Set([selected])
  for (let pass = 0; pass < catalog.menus.length; pass++) for (const menu of catalog.menus) if (menu.parent && menus.has(menu.parent)) menus.add(menu.code)
  return new Set(catalog.menus.filter(menu => menus.has(menu.code)).flatMap(menu => menu.any_of))
}
