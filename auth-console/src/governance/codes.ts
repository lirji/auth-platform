/** 与既有范围契约使用相同稳定code；未知服务端值只读展示，不自动变成全范围。 */
export const ScopeKind = { TENANT_ALL: 'TENANT_ALL', SPECIFIED_STORES: 'SPECIFIED_STORES', SPECIFIED_RESOURCES: 'SPECIFIED_RESOURCES' } as const
export const ProjectionState = { READY: 'READY', UPDATING: 'UPDATING', BLOCKED: 'BLOCKED', LEGACY: 'LEGACY' } as const

/** 应用目录显式区分无权限和暂不可判定。 */
export const EntryState = { AVAILABLE: 'AVAILABLE', NO_ACCESS: 'NO_ACCESS', UNAVAILABLE: 'UNAVAILABLE' } as const
/** 审批事实和实际执行分别显示，批准不会自动映射为生效。 */
export const RequestState = { SUBMITTED: 'SUBMITTED', IN_REVIEW: 'IN_REVIEW', APPROVED: 'APPROVED', REJECTED: 'REJECTED', CANCELLED: 'CANCELLED' } as const
export const InvitationState = { PENDING: 'PENDING', ACCEPTED: 'ACCEPTED', REVOKED: 'REVOKED' } as const
export const MemberKind = { EMPLOYEE: 'EMPLOYEE', PARTNER: 'PARTNER', GUEST: 'GUEST' } as const
export const requestLabels: Record<string, string> = { SUBMITTED: '已提交，等待流程启动', IN_REVIEW: '审批中', APPROVED: '已批准', REJECTED: '已驳回', CANCELLED: '已取消' }
export const executionLabels: Record<string, string> = { ...requestLabels, MIGRATION_WAITING: '已新批准，等待迁移切换', ACTIVE: '已实际生效', PENDING_APPLY: '等待权限生效', APPLY_FAILED: '权限同步失败', EXPIRED: '已到期', UNAVAILABLE: '当前不可用', REVOKING: '正在回收', REVOKED: '本来源已回收' }
export const ReceiptState = { COMPLETED: 'COMPLETED', PROCESSING: 'PROCESSING', BLOCKED: 'BLOCKED' } as const
export const GrantState = { PENDING: 'PENDING', ACTIVE: 'ACTIVE', REVOKED: 'REVOKED' } as const
/** 来源类型决定受益人形状，未知或缺失组元数据不能猜成个人来源。 */
export const GrantSourceType = { DIRECT: 'DIRECT', GROUP: 'GROUP', OA_REQUEST: 'OA_REQUEST' } as const

/** 持久迁移阶段与任务动作使用固定协议值，避免界面分支出现不同拼写。 */
export const MigrationStage = {
  RUNNING: 'RUNNING', COMPLETED: 'COMPLETED', FINISHED_WITH_FAILURES: 'FINISHED_WITH_FAILURES', CANCELLED: 'CANCELLED',
  READY_TO_REVOKE: 'READY_TO_REVOKE', WAIT_REVOKE_CONFIRM: 'WAIT_REVOKE_CONFIRM', READY_TO_GRANT: 'READY_TO_GRANT',
  WAIT_NEW_CONFIRM: 'WAIT_NEW_CONFIRM', FAILED: 'FAILED',
} as const
export const MigrationAction = { ADVANCE: 'advance', CANCEL: 'cancel', RETRY: 'retry' } as const

/** 能力生命周期控制新增使用，与紧急停用和授权状态分别建模。 */
export const LifecycleState = { ACTIVE: 'ACTIVE', DEPRECATED: 'DEPRECATED', RETIRED: 'RETIRED' } as const

export const RetirementProofState = { PROVEN: 'PROVEN', UNPROVEN: 'UNPROVEN' } as const
