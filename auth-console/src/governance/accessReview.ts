import type { Page, Partition } from '../api/governance'
import type { PersonnelGrantSource, PersonnelReport } from './personnelImpact'
import { validatePersonnelReport } from './personnelImpact.ts'

export const ReviewDecision = { KEEP: 'KEEP', REVOKE: 'REVOKE', INVESTIGATE: 'INVESTIGATE' } as const
export const ReviewItemState = { PENDING: 'PENDING', INVESTIGATE: 'INVESTIGATE', KEPT: 'KEPT', WAIT_REVOKE: 'WAIT_REVOKE', REVOKED: 'REVOKED', CANCELLED: 'CANCELLED' } as const
export const ReviewTaskState = { RUNNING: 'RUNNING', NEEDS_INVESTIGATION: 'NEEDS_INVESTIGATION', COMPLETED: 'COMPLETED', CANCELLED: 'CANCELLED' } as const
export type ReviewDecisionCode = typeof ReviewDecision[keyof typeof ReviewDecision]
export interface ReviewResponsible { membership_id: string; generation: number; version: number }
export interface ReviewItem { id: string; grant_id: string; original: PersonnelGrantSource; original_hash: string; state: typeof ReviewItemState[keyof typeof ReviewItemState]; version: number; reason: string | null; revoke_command: string; confirmed_operation_id: string | null }
export interface ReviewSummary { id: string; membership_id: string; responsible_membership_id: string; state: typeof ReviewTaskState[keyof typeof ReviewTaskState]; version: number; created_at: string; updated_at: string }
export interface ReviewDetail extends ReviewSummary { target_generation: number; original_basis_hash: string; responsible_generation: number; reason: string; created_by: string; creator_generation: number; items: ReviewItem[]; current_report: PersonnelReport; responsible_qualified: boolean; can_decide: boolean }
export interface ReviewCreate extends Partition { command_id: string; membership_id: string; responsible_membership_id: string; responsible_generation: number; basis_hash: string; source_cursor: string | null; grant_ids: string[]; reason: string }
export interface ReviewAction extends Partition { command_id: string; expected_task_version: number; reason: string; item_id?: string; expected_item_version?: number; current_basis_hash?: string; decision?: ReviewDecisionCode; whole_group_ack?: boolean; responsible_membership_id?: string; responsible_generation?: number }
export const ReviewActionType = { DECISION: 'decision', CONFIRM: 'confirm', CANCEL: 'cancel', RESPONSIBLE: 'responsible' } as const
export type ReviewActionKind = typeof ReviewActionType[keyof typeof ReviewActionType]
const uuid = (s: unknown): s is string => typeof s === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(s)
const integer = (n: unknown) => typeof n === 'number' && Number.isSafeInteger(n) && n > 0
const digest = (s: unknown) => typeof s === 'string' && /^[a-f0-9]{64}$/.test(s)
const time = (s: unknown) => typeof s === 'string' && Number.isFinite(Date.parse(s))
const reason = (s: unknown) => typeof s === 'string' && !!s.trim() && s.length <= 500
const fail = () => { throw new Error('响应缺少有效权限复核信息') }
const summary = (r: ReviewSummary) => !!r && uuid(r.id) && uuid(r.membership_id) && uuid(r.responsible_membership_id) && Object.values(ReviewTaskState).includes(r.state) && integer(r.version) && time(r.created_at) && time(r.updated_at)
/** 有界候选不能把不完整／未知成员信息变成可用负责人。 */
export function validateReviewResponsibles(r: ReviewResponsible[]): ReviewResponsible[] {
  if (!Array.isArray(r) || r.length > 100 || r.some(x => !x || !uuid(x.membership_id) || !integer(x.generation) || !integer(x.version)) || new Set(r.map(x => x.membership_id)).size !== r.length) fail()
  return r
}
export function validateReviewList(r: Page<ReviewSummary>): Page<ReviewSummary> {
  if (!r || !Array.isArray(r.items) || r.items.length > 20 || !r.items.every(summary) || r.next_cursor !== null && !uuid(r.next_cursor)) fail()
  return r
}
/** 原输入与当前依据分别验证；REVOKED必须有真实确认编号，取消不能伪装完成。 */
export function validateReviewDetail(r: ReviewDetail, id?: string): ReviewDetail {
  if (!summary(r) || id !== undefined && r.id !== id || !integer(r.target_generation) || !digest(r.original_basis_hash) || !integer(r.responsible_generation)
    || !uuid(r.created_by) || !integer(r.creator_generation) || !reason(r.reason) || typeof r.responsible_qualified !== 'boolean' || typeof r.can_decide !== 'boolean'
    || r.can_decide && !r.responsible_qualified || !Array.isArray(r.items) || !r.items.length || r.items.length > 100) fail()
  validatePersonnelReport(r.current_report, r.membership_id)
  for (const i of r.items) {
    if (!i || !uuid(i.id) || !uuid(i.grant_id) || !digest(i.original_hash) || !integer(i.version) || !uuid(i.revoke_command)
      || !Object.values(ReviewItemState).includes(i.state) || i.reason !== null && !reason(i.reason) || i.state !== ReviewItemState.PENDING && !reason(i.reason)
      || i.original?.grant?.grant_id !== i.grant_id || (i.state === ReviewItemState.REVOKED ? !uuid(i.confirmed_operation_id) : i.confirmed_operation_id !== null)) fail()
    validatePersonnelReport({ ...r.current_report, sources: [i.original] }, r.membership_id)
  }
  if (new Set(r.items.map(i => i.id)).size !== r.items.length || new Set(r.items.map(i => i.grant_id)).size !== r.items.length
    || r.state === ReviewTaskState.COMPLETED && r.items.some(i => i.state !== ReviewItemState.KEPT && i.state !== ReviewItemState.REVOKED)) fail()
  return r
}

/** 只对当前固定代际、完整依据和未终结条目允许新决定；原撤权确认另行处理。 */
export function canReviewDecide(r: ReviewDetail, i: ReviewItem): boolean {
  return r.can_decide && r.current_report.complete && r.target_generation === r.current_report.target.generation
    && r.state !== ReviewTaskState.COMPLETED && r.state !== ReviewTaskState.CANCELLED
    && (i.state === ReviewItemState.PENDING || i.state === ReviewItemState.INVESTIGATE)
}
