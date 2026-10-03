import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Descriptions, Tag } from 'antd'
import { catalogImpact, type CatalogImpactCursor, type CatalogImpactReport, type CatalogManifest, type CatalogPreview, type Partition } from '../api/governance'
import { Failure } from './feedback'
import { CapabilityList } from './presentation'
import { ImpactCompleteness, validateCatalogImpact } from './catalogImpact'

/** 显式按需请求独立诊断；切换候选／分区时终止旧请求并移除旧依据。 */
export function CatalogImpactPanel({ partition, manifest, preview, locked = false, onBasis }: { partition: Partition; manifest: CatalogManifest; preview: CatalogPreview; locked?: boolean; onBasis?: (report?: CatalogImpactReport) => void }) {
  const [report, setReport] = useState<CatalogImpactReport>()
  const [error, setError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const [page, setPage] = useState(0)
  const pending = useRef<AbortController>()
  useEffect(() => () => pending.current?.abort(), [])
  useEffect(() => { if (locked) { pending.current?.abort(); setBusy(false) } }, [locked])
  const inspect = async (cursor?: CatalogImpactCursor) => {
    if (locked) return
    pending.current?.abort()
    const controller = new AbortController(); pending.current = controller
    setBusy(true); setError(undefined); setReport(undefined); onBasis?.(undefined)
    try {
      const result = validateCatalogImpact(await catalogImpact(partition, manifest, cursor, controller.signal), partition, preview)
      if (!controller.signal.aborted) { setReport(result); setPage(value => cursor ? value + 1 : 1); onBasis?.(result.completeness === ImpactCompleteness.COMPLETE ? result : undefined) }
    } catch (failure) { if (!controller.signal.aborted) { setError(failure); setPage(0) } }
    finally { if (!controller.signal.aborted) setBusy(false) }
  }
  return <section className="g-catalog-impact" aria-label="菜单授权影响">
    <h3>潜在授权影响</h3><p>需要当前分区的管理委派和独立诊断资格。统计来自当前有效来源；业务访问仍受范围、能力状态和投影栅栏检查。</p>
    <Button disabled={locked} loading={busy} onClick={() => void inspect()}>重新分析授权影响</Button>
    {!!error && <><Failure error={error} /><p>未取得可用的影响报告，不能据此判断为零影响。403 请核对当前分区的独立诊断资格；409 请重新预览和分析。</p></>}
    {report && <>
      <Descriptions column={1} bordered size="small" items={[
        { label: '统计范围', children: `${report.tenant_id} / ${report.application_id} / ${report.environment}` },
        { label: '统计时点', children: report.observed_at }, { label: '完整性', children: report.completeness === ImpactCompleteness.COMPLETE ? '依据完整，计数覆盖当前范围' : '依据超过运算边界，计数覆盖当前范围但不能用于发布确认' },
        { label: '固定依据', children: report.basis_hash || '无完整依据' },
        { label: '关联角色', children: report.stats.role_count },
        { label: 'ACTIVE / PENDING 来源', children: `${report.stats.active_grant_count} / ${report.stats.pending_grant_count}` },
        { label: 'ACTIVE / PENDING 人员', children: `${report.stats.active_people_count} / ${report.stats.pending_people_count}` },
        { label: '去重人员合计', children: report.stats.people_count },
        { label: '组来源 / 申请策略', children: `${report.stats.group_grant_count} / ${report.stats.request_policy_count}` },
        { label: '政策 / 目录栅栏', children: `${report.fences.policy_state} (${report.fences.policy_applied_epoch ?? '无'} / ${report.fences.policy_desired_epoch ?? '无'}) / ${report.fences.directory_state} (${report.fences.directory_applied_epoch ?? '无'} / ${report.fences.directory_desired_epoch ?? '无'})` },
        { label: '来源隔离', children: report.fences.source_quarantined ? '存在隔离来源' : '未记录隔离来源' },
        { label: '紧急停用能力', children: <CapabilityList values={report.fences.disabled_capabilities} /> },
      ]} />
      <Alert type="info" showIcon message="潜在关系统计不代表实际业务访问已允许" description="ACTIVE 与 PENDING 人数分别去重，二者可能重叠，合计再次按主体去重。明细按每类最多100条分页；当前页条数不代表完整计数。" />
      {report.completeness === ImpactCompleteness.BASIS_LIMIT_EXCEEDED && <Alert type="warning" showIcon message="依据超过运算边界" description="明细仅展示有界首页，不能继续翻页或用于完整影响确认。" />}
      {!report.stats.role_count && <p>关联能力尚无角色引用；新能力尚未授予。</p>}
      <h4>当前明细 · 第 {page} 页</h4>
      <h4>角色 · 当前页 {report.roles.length}</h4>
      {report.roles.map(role => <details className="g-catalog-change" key={role.id}><summary>{role.role_code} v{role.version}</summary>
        <p>角色ID：{role.id} · ACTIVE {role.active_grant_count} / PENDING {role.pending_grant_count}</p><CapabilityList values={role.capabilities} /></details>)}
      <h4>人员与来源关联 · 当前页 {report.sources.length}</h4>
      {report.sources.map(source => <details className="g-catalog-change" key={`${source.grant_id}:${source.member_id ?? ''}`}><summary><Tag>{source.grant_state}</Tag>{source.source_type} · {source.member_id || '组无当前有效人员'}</summary>
        <Descriptions column={1} size="small" items={[
          { label: '授权 / 角色', children: `${source.grant_id} / ${source.role_id}` }, { label: '成员代际 / 组', children: `${source.generation ?? '无'} / ${source.group_id ?? '无'}` },
          { label: '来源', children: `${source.source_type} / ${source.source_id}` }, { label: '有效期', children: `${source.valid_from} → ${source.valid_to}` },
          { label: '固定范围', children: source.scope_rule ? <pre>{JSON.stringify(source.scope_rule, null, 2)}</pre> : source.scope },
        ]} /></details>)}
      <h4>申请策略 · 当前页 {report.policies.length}</h4>
      {report.policies.map(policy => <p key={policy.id}>{policy.id} · 角色 {policy.role_id} · v{policy.policy_version} · {policy.enabled ? '启用' : '停用'}</p>)}
      <Button disabled={locked || busy || !report.next_cursor} onClick={() => void inspect(report.next_cursor ?? undefined)}>读取下一页影响明细</Button>
    </>}
  </section>
}
