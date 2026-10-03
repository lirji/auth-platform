import { apiClient } from './client'
import { validatePublishedCatalog } from '../governance/publishedCatalog'

export interface Partition { tenant_id: string; application_id: string; environment: string }
export interface AccessMenu { code: string; parent: string | null; href: string | null; label?: string | null }
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
export async function accessState(partition: Partition, role?: string, grant?: string, signal?: AbortSignal): Promise<AccessState> {
  return (await apiClient.get<AccessState>('/api/governance/v1/access/state', {
    params: { ...partition, after_role: role, after_grant: grant }, signal,
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
export interface PublishedCapability extends Capability { grantable: boolean }
export interface PublishedMenu { code: string; parent: string | null; route: string | null; any_of: string[]; label?: string | null; position?: number | null }
export interface PublishedResourceType { code: string; scope_supported: boolean; allowed_scope_kinds: string[] }
export interface PublishedCatalog extends Partition { membership_id: string; generation: number; max_duration_seconds: number;
  manifest_version: number; content_hash: string; view_hash: string; menus: PublishedMenu[]; capabilities: PublishedCapability[]; resource_types: PublishedResourceType[] }
/** 当前实际清单与委派选项有限返回；写入仍独立回源判权。 */
export const publishedCatalog = async (p: Partition, signal?: AbortSignal): Promise<PublishedCatalog> =>
  validatePublishedCatalog((await apiClient.get<PublishedCatalog>('/api/governance/v1/access/published-catalog', { params: p, signal })).data, p)
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

export interface MenuChange { code: string; kind: 'ADDED' | 'REMOVED' | 'CHANGED'; before: PublishedMenu | null; after: PublishedMenu | null; fields: string[] }
export interface CatalogViolation { code: 'CAPABILITY_REMOVED' | 'CAPABILITY_CHANGED' | 'VERSION_REGRESSION' | 'SAME_VERSION_CHANGED'; capability: string | null;
  before: CatalogManifest['capabilities'][number] | null; after: CatalogManifest['capabilities'][number] | null }
export interface CatalogPreview { application: string; current_version: number; proposed_version: number; content_hash: string; added: string[]; retained: string[];
  presentation_hash: string; menu_changes: MenuChange[]; violations: CatalogViolation[]; publishable: boolean; affected_capabilities: string[] }
export interface CatalogManifest { schema_version: string; application: string; manifest_version: number;
  capabilities: { code: string; resource_type: string; risk_level: string }[]; menus: { code: string; parent: string | null; route: string | null; any_of: string[]; label?: string | null; position?: number | null }[] }
export const previewCatalog = async (manifest: CatalogManifest): Promise<CatalogPreview> => (await apiClient.post('/api/governance/v1/catalog/preview', manifest)).data
export const publishCatalog = async (command: { manifest: CatalogManifest; commandId: string }): Promise<CatalogPreview> =>
  (await apiClient.post('/api/governance/v1/catalog/publish', command.manifest, { headers: { 'X-Command-Id': command.commandId } })).data

export interface RequestPolicy { id: string; role_id: string; scope_rule: ScopeRule; max_duration_seconds: number; policy_version: number; role_code: string; role_version: number; capabilities: string[] }
export interface PolicyConfiguration { policy: RequestPolicy; approver_membership_id: string; approver_generation: number; enabled: boolean }
export interface PolicyCommand extends Partition { command_id: string; role_id: string; scope_rule: ScopeRule; max_duration_seconds: number; approver_membership_id: string; approver_generation: number; policy_version: number }
export interface AccessRequest { id: string; policy_id: string; role_id: string; capabilities: string[]; scope_rule: ScopeRule; valid_from: string; valid_to: string; reason: string; request_version: number; snapshot_hash: string; state: string; state_version: number; approval_instance_id: string | null; grant_id: string | null }
export interface RequestExecution { request_id: string; grant_id: string | null; grant_state: string | null; display_state: string; operation_id: string | null; start_state: string; start_attempts: number; start_error: string | null; callback_status: string | null; callback_result: string | null; other_active_grant_count: number }
export interface RequestNotice { id: string; request_id: string; state_version: number; message_key: string; delivered_at: string }
export interface SubmitRequest extends Partition { command_id: string; policy_id: string; valid_from: string; valid_to: string; reason: string }
export interface CancelRequest extends Partition { command_id: string; id: string; state_version: number }
export const requestPolicies = async (p: Partition, after?: string): Promise<Page<RequestPolicy>> => (await apiClient.get('/api/governance/v1/requests/policies', { params: { ...p, after } })).data
export const managedPolicies = async (p: Partition, after?: string): Promise<Page<PolicyConfiguration>> => (await apiClient.get('/api/governance/v1/access/request-policies', { params: { ...p, after } })).data
export const registerPolicy = async (command: PolicyCommand): Promise<RequestPolicy> => (await apiClient.post('/api/governance/v1/requests/policies', command)).data
export const myRequests = async (p: Partition, after?: string): Promise<Page<AccessRequest>> => (await apiClient.get('/api/governance/v1/requests', { params: { ...p, after } })).data
export const requestDetail = async (p: Partition, id: string): Promise<AccessRequest> => (await apiClient.get(`/api/governance/v1/requests/${id}`, { params: p })).data
export const requestExecution = async (p: Partition, id: string): Promise<RequestExecution> => (await apiClient.get(`/api/governance/v1/requests/${id}/execution`, { params: p })).data
export const requestNotices = async (p: Partition, after?: string): Promise<Page<RequestNotice>> => (await apiClient.get('/api/governance/v1/requests/notifications', { params: { ...p, after } })).data
export const submitRequest = async (command: SubmitRequest): Promise<AccessRequest> => (await apiClient.post('/api/governance/v1/requests', command)).data
export const cancelRequest = async ({ id, ...command }: CancelRequest): Promise<AccessRequest> => (await apiClient.post(`/api/governance/v1/requests/${id}/cancel`, command)).data

export interface InvitationAuthority { issuer: string; max_invitation_seconds: number; max_membership_seconds: number }
export interface Invitation { id: string; target_issuer: string; target_subject: string; member_kind: string; expires_at: string; membership_valid_to: string; state: string; version: number }
export interface IssueInvitation extends Partition { command_id: string; invitation_id: string; target_subject: string; member_kind: string; token: string; expires_at: string; membership_valid_to: string; reason: string }
export interface RevokeInvitation extends Partition { id: string; command_id: string; expected_version: number; reason: string }
export const invitationAuthority = async (p: Partition): Promise<InvitationAuthority> => (await apiClient.get('/api/governance/v1/portal-invitations/authority', { params: p })).data
export const invitations = async (p: Partition, after?: string): Promise<Page<Invitation>> => (await apiClient.get('/api/governance/v1/portal-invitations', { params: { ...p, after } })).data
export const issueInvitation = async (command: IssueInvitation): Promise<Invitation> => (await apiClient.post('/api/governance/v1/portal-invitations', command)).data
export const revokeInvitation = async ({ id, ...command }: RevokeInvitation): Promise<Invitation> => (await apiClient.post(`/api/governance/v1/portal-invitations/${id}/revoke`, command)).data
export const acceptInvitation = async (command: { invitation_id: string; token: string }): Promise<{ membership_id: string; membership_generation: number; membership_status: string }> => (await apiClient.post('/api/governance/v1/invitations/accept', command)).data

export interface PermissionExplanation { grant_id: string; member_id: string | null; generation: number | null; group_id: string | null; role_id: string; role_code: string; role_version: number; capabilities: string[]; scope: string; scope_rule: ScopeRule | null; source_type: string; source_id: string; valid_from: string; valid_to: string; grant_state: string; effective_state: string; grant_version: number; operation_id: string | null; policy_state: string; directory_state: string }
export interface AccessAudit { id: string; operator_ref: string; operation: string; target_id: string | null; target_version: number; occurred_at: string; outcome: string }
export interface RevocationReceipt { grant_id: string; version: number; status: string; operation_id: string | null; desired_epoch: number; applied_epoch: number }
export interface RevokeGrant extends Partition { command_id: string; grant_id: string; expected_version: number }
export const myPermissions = async (p: Partition, after?: string): Promise<Page<PermissionExplanation>> => (await apiClient.get('/api/governance/v1/me/permissions', { params: { ...p, after } })).data
export const grantExplanation = async (p: Partition, grant: string): Promise<PermissionExplanation> => (await apiClient.get('/api/governance/v1/access/explanations', { params: { ...p, grant_id: grant } })).data
export const accessAudit = async (p: Partition, after?: string): Promise<Page<AccessAudit>> => (await apiClient.get('/api/governance/v1/access/audit', { params: { ...p, after } })).data
export const revokeGrant = async (command: RevokeGrant): Promise<RevocationReceipt> => (await apiClient.post('/api/governance/v1/access/strict-revoke', command)).data
export const revocationReceipt = async (p: Partition, grant: string): Promise<RevocationReceipt> => (await apiClient.get('/api/governance/v1/access/revocation-receipt', { params: { ...p, grant_id: grant } })).data
