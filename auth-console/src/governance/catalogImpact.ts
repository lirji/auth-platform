import type { CatalogImpactReport, CatalogPreview, Partition } from '../api/governance'

export const ImpactCompleteness = { COMPLETE: 'COMPLETE', BASIS_LIMIT_EXCEEDED: 'BASIS_LIMIT_EXCEEDED' } as const
const MAX_PAGE = 100
const counters = ['role_count', 'active_grant_count', 'pending_grant_count', 'active_people_count', 'pending_people_count', 'people_count', 'group_grant_count', 'request_policy_count'] as const
const digest = (value: unknown) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)

/** 完整性缺失、目标或候选不一致必须拒绝显示，不能把混合上下文结果当作零影响。 */
export function validateCatalogImpact(report: CatalogImpactReport, partition: Partition, preview: CatalogPreview): CatalogImpactReport {
  if (!report || report.tenant_id !== partition.tenant_id || report.application_id !== partition.application_id || report.environment !== partition.environment
    || report.content_hash !== preview.content_hash || report.presentation_hash !== preview.presentation_hash || report.current_version !== preview.current_version
    || !Number.isFinite(Date.parse(report.observed_at)) || !report.stats || Object.keys(report.stats).length !== counters.length || counters.some(key => !(key in report.stats))
    || Object.values(report.stats).some(value => !Number.isSafeInteger(value) || value < 0)
    || !report.fences || !Array.isArray(report.fences.disabled_capabilities)
    || [report.roles, report.sources, report.policies].some(page => !Array.isArray(page) || page.length > MAX_PAGE)
    || !Object.prototype.hasOwnProperty.call(ImpactCompleteness, report.completeness)) throw new Error('目录影响响应不完整或与当前候选不一致')
  if (report.completeness === ImpactCompleteness.COMPLETE ? !digest(report.basis_hash)
    : report.basis_hash !== null || report.next_cursor !== null) throw new Error('目录影响依据不可用')
  if (report.next_cursor && report.next_cursor.basis_hash !== report.basis_hash) throw new Error('目录影响游标与依据不一致')
  return report
}
