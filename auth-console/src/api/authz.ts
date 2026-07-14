import { apiClient } from './client'

export interface GrantBody {
  resourceType: string
  resourceId: string
  relation: string
  subjectType: string
  subjectId: string
  subjectRelation?: string
}

export interface CheckBody {
  subjectType: string
  subjectId: string
  permission: string
  resourceType: string
  resourceId: string
}

export interface SubjectView {
  type: string
  id: string
}

export const grant = (b: GrantBody) => apiClient.post<{ token: string }>('/admin/grants', b).then((r) => r.data)
export const revoke = (b: GrantBody) => apiClient.post<{ token: string }>('/admin/grants/revoke', b).then((r) => r.data)
export const check = (b: CheckBody) => apiClient.post<{ allowed: boolean }>('/admin/check', b).then((r) => r.data)

export const lookupSubjects = (resourceType: string, resourceId: string, permission: string, subjectType = 'user') =>
  apiClient
    .get<{ subjects: SubjectView[] }>(`/admin/resources/${resourceType}/${resourceId}/subjects`, {
      params: { permission, subjectType },
    })
    .then((r) => r.data)

export const lookupResources = (subjectType: string, subjectId: string, permission: string, resourceType: string) =>
  apiClient
    .get<{ resourceIds: string[] }>(`/admin/subjects/${subjectType}/${subjectId}/resources`, {
      params: { permission, resourceType },
    })
    .then((r) => r.data)

export const casdoorSync = () =>
  apiClient.post<{ groups: number; added: number; removed: number }>('/admin/casdoor/sync').then((r) => r.data)

export interface Relationship {
  resource: { type: string; id: string }
  relation: string
  subject: { type: string; id: string; relation?: string | null }
}

export const listRelationships = (resourceType: string, resourceId: string) =>
  apiClient
    .get<Relationship[]>('/admin/relationships', { params: { resourceType, resourceId } })
    .then((r) => r.data)

// 展开判定树(原始 SpiceDB PermissionRelationshipTree)
export const expand = (resourceType: string, resourceId: string, permission: string) =>
  apiClient
    .post<unknown>('/admin/expand', { resourceType, resourceId, permission, subjectType: 'user', subjectId: '' })
    .then((r) => r.data)

export interface AuditRecord {
  at: string
  actor: string
  action: string
  detail: string
}

export const audit = (limit = 100) =>
  apiClient.get<AuditRecord[]>('/admin/audit', { params: { limit } }).then((r) => r.data)
