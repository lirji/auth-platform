import { apiClient } from './client'

export interface Partition { tenant_id: string; application_id: string; environment: string }
export interface AccessMenu { code: string; parent: string | null; href: string | null }
export interface Presentation { menus: AccessMenu[]; capability_hints: string[] }
export interface Role { id: string; role_code: string; version: number; capabilities: string[] }
export interface Grant { id: string; member_id: string; member_generation: number; role_id: string; scope: string;
  source_type: string; source_id: string; valid_from: string; valid_to: string; state: 'PENDING' | 'ACTIVE' | 'REVOKED'; version: number }
export interface AccessState { roles: Role[]; grants: Grant[]; next_role_cursor: string | null; next_grant_cursor: string | null }

/** 只读当前权威快照，不把菜单或旧页面状态作为业务调用凭据。 */
export async function presentation(partition: Partition): Promise<Presentation> {
  return (await apiClient.get<Presentation>('/api/governance/v1/me/access', { params: partition })).data
}
/** 管理查询仍由后端校验当前成员与委派，普通业务授权不产生管理权。 */
export async function accessState(partition: Partition, role?: string, grant?: string): Promise<AccessState> {
  return (await apiClient.get<AccessState>('/api/governance/v1/access/state', {
    params: { ...partition, after_role: role, after_grant: grant },
  })).data
}

export interface Organization { membership_id: string; tenant_id: string; tenant_code: string; member_kind: string; generation: number }
export interface PortalApplication extends Presentation { application_id: string; environment: string; management: boolean; entry_state: 'AVAILABLE' | 'NO_ACCESS' | 'UNAVAILABLE' }
export interface Page<T> { items: T[]; next_cursor: string | null }
/** 本人组织来自当前权威成员关系，浏览器不能指定其他主体。 */
export async function organizations(signal?: AbortSignal): Promise<Organization[]> {
  return (await apiClient.get<Organization[]>('/api/governance/v1/me/organizations', { signal })).data
}
/** 应用候选关联与真实业务入口分开，管理权不会带来业务菜单。 */
export async function applications(tenant: string, after?: string, signal?: AbortSignal): Promise<Page<PortalApplication>> {
  return (await apiClient.get<Page<PortalApplication>>('/api/governance/v1/me/applications', { params: { tenant_id: tenant, after }, signal })).data
}

export interface Capability { code: string; resource_type: string; risk_level: string; disabled: boolean }
export interface Management { membership_id: string; generation: number; max_duration_seconds: number; capabilities: Capability[];
  catalog_owner: boolean; manifest_version: number; policy_state: string; directory_state: string; desired_epoch: number | null; applied_epoch: number | null }
export interface Member { membership_id: string; generation: number; member_kind: string; valid_to: string | null }
export interface RoleImpact { role_id: string; previous_role_id: string | null; added: string[]; removed: string[]; referencing_grant_count: number }
export interface ScopeRule { version: number; resource_type: string; clauses: { kind: string; values: string[]; include_root: boolean }[] }
export interface RoleCommand extends Partition { command_id: string; role_code: string; role_version: number; capabilities: string[] }
export interface GrantCommand extends Partition { command_id: string; member_id: string; member_generation: number; role_id: string;
  scope_rule: ScopeRule; source_id: string; valid_from: string; valid_to: string }
export const management = async (p: Partition): Promise<Management> => (await apiClient.get('/api/governance/v1/access/management', { params: p })).data
export const members = async (p: Partition, after?: string): Promise<Page<Member>> => (await apiClient.get('/api/governance/v1/access/members', { params: { ...p, after } })).data
export const roleImpact = async (p: Partition, role: string): Promise<RoleImpact> => (await apiClient.get('/api/governance/v1/access/role-impact', { params: { ...p, role_id: role } })).data
export const createRole = async (body: RoleCommand): Promise<Role> => (await apiClient.post('/api/governance/v1/access/roles', body)).data
export const grantScoped = async (body: GrantCommand): Promise<Grant> => (await apiClient.post('/api/governance/v1/access/scoped-grants', body)).data

export interface CatalogPreview { application: string; current_version: number; proposed_version: number; content_hash: string; added: string[]; retained: string[] }
export interface CatalogManifest { schema_version: string; application: string; manifest_version: number;
  capabilities: { code: string; resource_type: string; risk_level: string }[]; menus: { code: string; parent: string | null; route: string | null; any_of: string[] }[] }
export const previewCatalog = async (manifest: CatalogManifest): Promise<CatalogPreview> => (await apiClient.post('/api/governance/v1/catalog/preview', manifest)).data
export const publishCatalog = async (command: { manifest: CatalogManifest; commandId: string }): Promise<CatalogPreview> =>
  (await apiClient.post('/api/governance/v1/catalog/publish', command.manifest, { headers: { 'X-Command-Id': command.commandId } })).data
