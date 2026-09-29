/** 与既有范围契约使用相同稳定code；未知服务端值只读展示，不自动变成全范围。 */
export const ScopeKind = { TENANT_ALL: 'TENANT_ALL', SPECIFIED_STORES: 'SPECIFIED_STORES', SPECIFIED_RESOURCES: 'SPECIFIED_RESOURCES' } as const
export const ProjectionState = { READY: 'READY', UPDATING: 'UPDATING', BLOCKED: 'BLOCKED', LEGACY: 'LEGACY' } as const

/** 应用目录显式区分无权限和暂不可判定。 */
export const EntryState = { AVAILABLE: 'AVAILABLE', NO_ACCESS: 'NO_ACCESS', UNAVAILABLE: 'UNAVAILABLE' } as const
