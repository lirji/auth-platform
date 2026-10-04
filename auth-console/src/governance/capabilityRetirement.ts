import type { CapabilityLifecycleMutation, RetirementCommand, RetirementReport, RetirementReferences } from '../api/governance'
import { LifecycleState, RetirementProofState } from './codes.ts'

const digest = (value: unknown) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const integer = (value: unknown, minimum: number) => typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum
const timestamp = (value: unknown) => typeof value === 'string' && Number.isFinite(Date.parse(value))
const text = (value: unknown): value is string => typeof value === 'string'
const flag = (value: unknown) => typeof value === 'boolean'
const fail = () => { throw new Error('响应缺少有效退役信息') }

/** 缺目录／证明或自相矛盾的eligible不能生成确认控件，失败不是零引用。 */
export function validateRetirementReport(r: RetirementReport, application: string, capability: string): RetirementReport {
  if (!r || r.application_id !== application || r.capability !== capability || !Object.values(LifecycleState).includes(r.lifecycle_state)
    || !integer(r.lifecycle_version, 0) || !integer(r.manifest_version, 1) || !digest(r.content_hash) || !digest(r.presentation_hash)
    || !digest(r.basis_hash) || !timestamp(r.checked_at) || !Array.isArray(r.counts)
    || new Set(r.counts.map(c => c?.kind)).size !== r.counts.length
    || r.counts.some(c => !c || !text(c.kind) || !/^[A-Z_]{1,32}$/.test(c.kind) || !integer(c.blocking, 0) || !integer(c.historical, 0))
    || typeof r.complete !== 'boolean' || typeof r.eligible !== 'boolean' || !Object.values(RetirementProofState).includes(r.proof_state)
    || r.lifecycle_state !== LifecycleState.ACTIVE && !integer(r.lifecycle_version, 1)) fail()
  if (r.proof_state === RetirementProofState.PROVEN) {
    if (!digest(r.proof_hash) || !timestamp(r.proof_valid_until) || r.proof_reason !== null) fail()
  } else if (typeof r.proof_reason !== 'string' || !r.proof_reason || r.proof_hash !== null || r.proof_valid_until !== null) fail()
  if (r.eligible && (!r.complete || r.lifecycle_state !== LifecycleState.DEPRECATED || r.counts.some(c => c.blocking > 0)
    || r.proof_state !== RetirementProofState.PROVEN || Date.parse(r.proof_valid_until!) <= Date.now())) fail()
  return r
}

/** 逐项检查明细，缺blocking不能被界面翻译成历史保留。 */
export function validateRetirementReferences(r: RetirementReferences): RetirementReferences {
  if (!r || !Array.isArray(r.items) || r.items.length > 100 || !digest(r.basis_hash)
    || r.next_cursor !== null && (typeof r.next_cursor !== 'string' || !r.next_cursor)
    || r.items.some(i => !i || !text(i.kind) || !/^[A-Z_]{1,32}$/.test(i.kind)
      || !text(i.id) || !i.id || i.id.length > 100 || !flag(i.blocking) || !text(i.state) || !i.state)) fail()
  return r
}

/** 原成功回执与当前墓碑都须对应固定命令，损坏响应保留原键为未知结果。 */
export function validateRetirementReceipt(r: CapabilityLifecycleMutation, body: RetirementCommand): CapabilityLifecycleMutation {
  if (!r?.current || !r.receipt || r.current.application_id !== body.application_id || r.current.capability !== body.capability
    || r.current.state !== LifecycleState.RETIRED || !integer(r.current.version, body.expected_version + 1)
    || r.receipt.application_id !== body.application_id || r.receipt.capability !== body.capability
    || r.receipt.command_id !== body.command_id || r.receipt.before_state !== LifecycleState.DEPRECATED || r.receipt.after_state !== LifecycleState.RETIRED
    || r.receipt.before_version !== body.expected_version || r.receipt.after_version !== body.expected_version + 1 || r.receipt.reason !== body.reason) fail()
  return r
}
