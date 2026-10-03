import type { CatalogCandidate, CatalogEnableCommand, CatalogGuardPolicy, CatalogImpactBasis, CatalogPreview, CatalogRelease, CatalogReleaseTicket } from '../api/governance'

const HASH = /^[a-f0-9]{64}$/
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i
export const CatalogDecision = { KEEP_CURRENT_GRANTS: 'KEEP_CURRENT_GRANTS', SEPARATE_AUTHORIZATION_REVIEW: 'SEPARATE_AUTHORIZATION_REVIEW' } as const
export const CatalogGuardMode = { LEGACY: 'LEGACY', GUARDED: 'GUARDED' } as const
const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const keys = (value: Record<string, unknown>, allowed: string[]) => Object.keys(value).every(key => allowed.includes(key))

/** 导出信封只拆出正式候选，不把自动授权标记或未知主体发送给发布API。 */
export function readCatalogCandidate(text: string, application: string): CatalogCandidate {
  const value: unknown = JSON.parse(text)
  if (!object(value)) throw new Error('请输入有效的清单或导出信封')
  let candidate: CatalogCandidate
  if (Object.prototype.hasOwnProperty.call(value, 'manifest')) {
    if (!keys(value, ['manifest', 'source', 'reason', 'decision', 'auto_grants', 'auto_roles'])
      || Object.prototype.hasOwnProperty.call(value, 'auto_grants') && value.auto_grants !== false
      || Object.prototype.hasOwnProperty.call(value, 'auto_roles') && value.auto_roles !== false) throw new Error('导出信封含未知字段或自动授权声明')
    candidate = { manifest: value.manifest as CatalogCandidate['manifest'], source: (value.source ?? null) as CatalogCandidate['source'],
      reason: (value.reason ?? null) as string | null, decision: (value.decision ?? null) as CatalogCandidate['decision'] }
  } else candidate = { manifest: value as unknown as CatalogCandidate['manifest'], source: null, reason: null, decision: null }
  if (candidate.manifest?.application !== application) throw new Error('清单必须属于当前应用')
  if (candidate.source !== null && (!object(candidate.source) || !keys(candidate.source, ['commit', 'artifact_hash'])
      || typeof candidate.source.commit !== 'string' || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(candidate.source.commit)
      || typeof candidate.source.artifact_hash !== 'string' || !HASH.test(candidate.source.artifact_hash))) throw new Error('来源必须同时提供固定源码提交和制品摘要')
  if (candidate.reason !== null && (typeof candidate.reason !== 'string' || !candidate.reason.trim() || candidate.reason.length > 500)
      || candidate.decision !== null && !Object.values(CatalogDecision).includes(candidate.decision)) throw new Error('变更原因或授权处理决定无效')
  return candidate
}
const sameSource = (a: CatalogCandidate['source'], b: CatalogCandidate['source']) => a === null ? b === null : b !== null && a.commit === b.commit && a.artifact_hash === b.artifact_hash
const sameImpact = (a: CatalogImpactBasis | null, b: CatalogImpactBasis | null) => a === null ? b === null : b !== null && a.tenant_id === b.tenant_id && a.application_id === b.application_id && a.environment === b.environment && a.basis_hash === b.basis_hash

/** 缺字段、错应用、错双摘要或过期响应不能成为可发布票据。 */
export function validateCatalogTicket(value: CatalogReleaseTicket, candidate: CatalogCandidate, preview: CatalogPreview, impact: CatalogImpactBasis | null): CatalogReleaseTicket {
  if (!value || typeof value.preview_id !== 'string' || !UUID.test(value.preview_id) || !Number.isFinite(Date.parse(value.expires_at)) || Date.parse(value.expires_at) <= Date.now()
    || value.base_version !== preview.current_version || value.base_version === 0 && (value.base_content_hash !== null || value.base_presentation_hash !== null)
    || value.base_version > 0 && (!HASH.test(value.base_content_hash ?? '') || !HASH.test(value.base_presentation_hash ?? ''))
    || value.candidate?.manifest?.application !== candidate.manifest.application || value.candidate.manifest.manifest_version !== candidate.manifest.manifest_version
    || !sameSource(candidate.source, value.candidate.source) || candidate.reason !== value.candidate.reason || candidate.decision !== value.candidate.decision
    || !value.preview || value.preview.application !== preview.application || value.preview.current_version !== preview.current_version || value.preview.proposed_version !== preview.proposed_version
    || value.preview.content_hash !== preview.content_hash || value.preview.presentation_hash !== preview.presentation_hash || value.preview.publishable !== true
    || !Array.isArray(value.preview.menu_changes) || !Array.isArray(value.preview.violations) || value.preview.violations.length || !Array.isArray(value.preview.affected_capabilities)
    || !sameImpact(impact, value.impact)) throw new Error('固定预览与当前候选或基础依据不一致，请重新预览')
  return value
}

/** 发布回执必须绑定被冻结候选；网络未知时由原command/preview继续读取。 */
export function validateCatalogReceipt(value: CatalogRelease, ticket: CatalogReleaseTicket, commandId: string): CatalogRelease {
  if (!value || value.application !== ticket.candidate.manifest.application || value.version !== ticket.candidate.manifest.manifest_version || value.command_id !== commandId
    || value.content_hash !== ticket.preview.content_hash || value.presentation_hash !== ticket.preview.presentation_hash || value.base_version !== ticket.base_version
    || value.base_content_hash !== ticket.base_content_hash || value.base_presentation_hash !== ticket.base_presentation_hash
    || !sameSource(ticket.candidate.source, value.source) || value.reason !== ticket.candidate.reason || value.decision !== ticket.candidate.decision
    || value.preview?.content_hash !== ticket.preview.content_hash || value.preview.presentation_hash !== ticket.preview.presentation_hash) throw new Error('发布回执与固定预览不一致，保留原命令重试')
  return value
}
/** 默认兼容模式与单向启用事实分开，不为缺字段服务推测策略。 */
export function validateCatalogPolicy(value: CatalogGuardPolicy, application: string): CatalogGuardPolicy {
  if (!value || value.application_id !== application || value.mode === CatalogGuardMode.LEGACY && (value.version !== 0 || value.enabled_by !== null || value.enabled_at !== null || value.reason !== null || value.command_id !== null)
    || value.mode === CatalogGuardMode.GUARDED && (value.version !== 1 || !UUID.test(value.enabled_by ?? '') || !Number.isFinite(Date.parse(value.enabled_at ?? '')) || !value.reason || !UUID.test(value.command_id ?? ''))
    || !Object.values(CatalogGuardMode).includes(value.mode)) throw new Error('发布模式响应不完整或目标不一致')
  return value
}
/** 启用回执必须属于原命令，兼容模式响应不能冒充启用成功。 */
export function validateCatalogEnableReceipt(value: CatalogGuardPolicy, command: CatalogEnableCommand): CatalogGuardPolicy {
  validateCatalogPolicy(value, command.application_id)
  if (value.mode !== CatalogGuardMode.GUARDED || value.command_id !== command.command_id || value.reason !== command.reason) throw new Error('启用回执与原命令不一致，请用原命令重试')
  return value
}
