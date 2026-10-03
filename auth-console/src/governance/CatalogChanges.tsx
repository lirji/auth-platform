import { useState } from 'react'
import { Alert, Descriptions, Input, Select, Tag } from 'antd'
import type { CatalogPreview, MenuChange, PublishedMenu } from '../api/governance'
import { CapabilityList } from './presentation'

const kindLabels = { ADDED: '新增', REMOVED: '删除', CHANGED: '修改' }
const fieldLabels: Record<string, string> = { label: '名称', position: '顺序', parent: '父菜单', route: '页面路由', any_of: '权限映射' }
const violationLabels = { CAPABILITY_REMOVED: '已发布能力不能删除', CAPABILITY_CHANGED: '已发布能力的资源或风险语义不能改变',
  VERSION_REGRESSION: '候选版本早于当前发布版本', SAME_VERSION_CHANGED: '同一版本必须保持原目录和展示内容' }

/** 新UI遇到旧服务响应时拒绝发布，不把缺失差异解释为没有变化。 */
export function completeCatalogPreview(result: CatalogPreview) {
  return typeof result.publishable === 'boolean' && Array.isArray(result.menu_changes) && Array.isArray(result.violations)
    && Array.isArray(result.affected_capabilities) && typeof result.presentation_hash === 'string'
}

/** 修改前后同时可见，空项明确为无菜单，不以空列表冒充合法发布。 */
function MenuFacts({ menu, label }: { menu: PublishedMenu | null; label: string }) {
  return <section className="g-catalog-change-side" aria-label={label}><h4>{label}</h4>{menu ? <Descriptions column={1} size="small" items={[
    { label: '名称', children: menu.label || '未提供名称' }, { label: '顺序', children: menu.position ?? '未提供顺序' },
    { label: '父菜单', children: menu.parent || '顶层' }, { label: '页面路由', children: menu.route || '分组，无页面' },
    { label: '权限映射', children: <CapabilityList values={menu.any_of} /> },
  ]} /> : <p>无此菜单</p>}</section>
}

/** 全量有界差异支持检索；折叠仅影响展示，不改变已固定预览或发布内容。 */
export function CatalogChanges({ result }: { result: CatalogPreview }) {
  const [query, setQuery] = useState('')
  const [kind, setKind] = useState<MenuChange['kind']>()
  if (!completeCatalogPreview(result)) return <Alert type="warning" showIcon message="当前服务未返回完整菜单差异" description="请升级服务并重新预览，取得完整差异后才能发布。" />
  const changes = result.menu_changes ?? []
  const needle = query.trim().toLocaleLowerCase()
  const shown = changes.filter(change => (!kind || change.kind === kind) &&
    [change.code, change.before?.label, change.after?.label, change.before?.route, change.after?.route].some(value => value?.toLocaleLowerCase().includes(needle)))
  return <div className="g-catalog-changes">
    <h3>菜单变更 · {changes.length} 项</h3>
    <p>新增 {changes.filter(c => c.kind === 'ADDED').length} · 删除 {changes.filter(c => c.kind === 'REMOVED').length} · 修改 {changes.filter(c => c.kind === 'CHANGED').length}</p>
    {result.publishable !== true && <Alert type="error" showIcon message="此候选不能发布" description={<ul>{(result.violations ?? []).map((violation, i) =>
      <li key={`${violation.code}-${violation.capability ?? i}`}>{violationLabels[violation.code]}{violation.capability && <>：<code>{violation.capability}</code>
        {violation.before && <div>原语义：{violation.before.resource_type} / {violation.before.risk_level}</div>}
        {violation.after && <div>候选语义：{violation.after.resource_type} / {violation.after.risk_level}</div>}</>}</li>)}
      {!result.violations?.length && <li>未取得可发布资格，请重新预览。</li>}</ul>} />}
    {!!changes.length && <div className="g-catalog-change-filters"><Input aria-label="搜索菜单变更" placeholder="菜单名称、编码或路由" value={query} onChange={e => setQuery(e.target.value)} allowClear />
      <Select aria-label="筛选变更类型" placeholder="全部变更" allowClear value={kind} onChange={setKind}
        options={Object.entries(kindLabels).map(([value, label]) => ({ value, label }))} /></div>}
    {!changes.length && <p>菜单内容与当前发布版本一致。</p>}
    {!!changes.length && !shown.length && <p>没有匹配的菜单变更，请调整筛选。</p>}
    {shown.map(change => <details key={change.code} className="g-catalog-change"><summary>
      <Tag color={change.kind === 'REMOVED' ? 'error' : change.kind === 'ADDED' ? 'success' : 'processing'}>{kindLabels[change.kind]}</Tag>
      <strong>{change.after?.label || change.before?.label || change.code}</strong><code>{change.code}</code>
      <span>{change.fields.map(field => fieldLabels[field] ?? field).join('、')}</span>
    </summary><div className="g-catalog-change-facts"><MenuFacts menu={change.before} label="修改前" /><MenuFacts menu={change.after} label="修改后" /></div></details>)}
    <h4>潜在关联权限 · {result.affected_capabilities?.length ?? 0} 项</h4><CapabilityList values={result.affected_capabilities ?? []} />
    <p>关联权限包含变更分组的两版子菜单。这是入口和展示的关联范围，人员影响需要独立诊断资格，业务请求仍逐次判权。</p>
  </div>
}
