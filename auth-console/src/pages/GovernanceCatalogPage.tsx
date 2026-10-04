import { LifecycleState } from '../governance/codes.ts'
import { Alert, Button, Card, Descriptions, Empty, Input, Select, Skeleton, Space, Table, Tag, Tree, Typography } from 'antd'
import type { DataNode } from 'antd/es/tree'
import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { publishedCatalog, type PublishedCapability, type PublishedCatalog } from '../api/governance'
import { useGovernanceContext } from './GovernancePage'
import { applicationSearch } from '../governance/context'
import { Failure } from '../governance/feedback'
import { capabilityMenus, menuCapabilities, relatedRoles, menuTitle, orderedMenus } from '../governance/catalogModel'
import { useRoleDirectory } from '../governance/useRoleDirectory'
import { PublishedCatalogBrowser } from '../governance/PublishedCatalogSelector'
import { OwnerCatalogPanel } from '../governance/OwnerCatalogPanel'
import { CapabilityRetirementPanel } from '../governance/CapabilityRetirementPanel'
import { CapabilityLifecycleControl } from '../governance/CapabilityLifecycleControl'
import { GovernanceEmpty } from '../governance/presentation'

const CatalogFilter = { Retired: 'retired', Deprecated: 'deprecated', Disabled: 'disabled', Grantable: 'grantable', Restricted: 'restricted', Unlinked: 'unlinked' } as const

/** 菜单只是目录映射；显示any_of原语义，避免将目录阅读当作实际访问授权。 */
export default function GovernanceCatalogPage({ menus = false }: { menus?: boolean }) {
  const { partition, queryKey, application } = useGovernanceContext()
  const [params, setParams] = useSearchParams()
  const query = useQuery({ queryKey: [...queryKey, 'published-catalog'], queryFn: ({ signal }) => publishedCatalog(partition, signal), enabled: application.management, retry: false, staleTime: 0, gcTime: 0 })
  const roles = useRoleDirectory(application.management)
  const set = (key: string, value?: string) => { const next = new URLSearchParams(params); if (value) next.set(key, value); else next.delete(key); next.delete('catalog_page'); setParams(next, { replace: key === 'q' }) }
  const link = (path: string, key?: string, value?: string) => { const next = applicationSearch(params, partition.application_id, partition.environment); if (key && value) next.set(key, value); return `/governance/${path}?${next}` }
  if (application.catalog_owner && !application.management) return <OwnerCatalogPanel />
  if (query.error) return <Failure error={query.error} retry={() => void query.refetch()} />
  if (!query.data) return <Card><Skeleton active aria-label="正在加载已发布目录" /></Card>
  const catalog = query.data
  const selectedMenu = catalog.menus.find(menu => menu.code === params.get('menu')) ?? (menus && !params.get('menu') ? orderedMenus(catalog).find(menu => menu.route) : undefined)
  const selectedCapability = catalog.capabilities.find(cap => cap.code === params.get('capability'))
  const search = (params.get('q') ?? '').trim().toLowerCase()
  const resource = params.get('resource') ?? ''
  const status = params.get('status') ?? ''
  const risk = params.get('risk') ?? ''
  const menuCodes = selectedMenu ? new Set(menuCapabilities(catalog, selectedMenu.code, true).map(cap => cap.code)) : undefined
  const caps = catalog.capabilities.filter(cap => (!menuCodes || menuCodes.has(cap.code)) && (!resource || cap.resource_type === resource)
    && (!risk || cap.risk_level === risk) && (!status || status === CatalogFilter.Disabled && cap.disabled || status === CatalogFilter.Grantable && cap.grantable
      || status === CatalogFilter.Retired && cap.lifecycle_state === LifecycleState.RETIRED || status === CatalogFilter.Deprecated && cap.lifecycle_state === LifecycleState.DEPRECATED || status === CatalogFilter.Restricted && !cap.grantable && !cap.disabled && cap.lifecycle_state === LifecycleState.ACTIVE || status === CatalogFilter.Unlinked && !capabilityMenus(catalog, cap.code).length)
    && (!search || `${cap.code} ${cap.resource_type}`.toLowerCase().includes(search)))
  const reset = () => setParams(applicationSearch(params, partition.application_id, partition.environment))
  const tree = (parent: string | null): DataNode[] => orderedMenus(catalog).filter(menu => menu.parent === parent).map(menu => ({ key: menu.code, title: menuTitle(menu), children: tree(menu.code) }))
  const menuMatches = catalog.menus.filter(menu => `${menuTitle(menu)} ${menu.code} ${menu.route ?? ''}`.toLowerCase().includes(search))
  const direct = selectedMenu ? menuCapabilities(catalog, selectedMenu.code, false) : []
  return <>
    <Card title={menus ? '应用菜单与权限映射' : '已发布权限目录'} extra={<Button loading={query.isFetching} onClick={() => { void query.refetch(); void roles.refetch() }}>刷新目录</Button>}>
      <PublishedCatalogBrowser catalog={catalog} />
      <div className="g-directory-links"><Link to={link('menus')}>菜单权限</Link><Link to={link('catalog')}>权限目录</Link><Link to={link('roles')}>角色管理</Link><Link to={link('grants')}>成员授权</Link></div>
      {params.get('menu') && !selectedMenu && <Alert type="warning" message="当前版本未找到该菜单，请重新选择。" action={<Button onClick={() => set('menu')}>清除选择</Button>} />}
      {menus ? <div className="g-catalog-layout">
        <aside className="g-menu-browser" aria-label="已发布菜单树">
          <Input.Search aria-label="搜索菜单" placeholder="搜索菜单名称、编码或页面路由" value={params.get('q') ?? ''} onChange={event => set('q', event.target.value)} />
          {!catalog.menus.length ? <GovernanceEmpty title="当前版本没有菜单" description="目录尚未发布菜单映射，可继续浏览权限目录。" /> : search ? <div className="g-menu-results">{menuMatches.map(menu => <Button key={menu.code} type={selectedMenu?.code === menu.code ? 'primary' : 'text'} onClick={() => set('menu', menu.code)}>{menuTitle(menu)}</Button>)}{!menuMatches.length && <Empty description="没有匹配菜单" />}</div> : <Tree aria-label="菜单目录" blockNode defaultExpandAll selectedKeys={selectedMenu ? [selectedMenu.code] : []} treeData={tree(null)} onSelect={keys => { const next = new URLSearchParams(params); if (keys[0]) next.set('menu', keys[0].toString()); else next.delete('menu'); next.delete('capability'); setParams(next) }} />}
        </aside>
        <section className="g-menu-detail" aria-label="菜单权限详情">
          {!selectedMenu ? <GovernanceEmpty title="选择菜单查看权限" description="查看页面路由、直接可见条件和子菜单关联能力。" /> : <>
            <div className="g-detail-heading"><span className="g-detail-eyebrow">{selectedMenu.route ? '页面菜单' : '目录节点'}</span><h2>{menuTitle(selectedMenu)}</h2><Typography.Text type="secondary" copyable>{selectedMenu.code}</Typography.Text></div>
            <Descriptions column={1} items={[{ label: '页面路由', children: selectedMenu.route ? <Typography.Text copyable>{selectedMenu.route}</Typography.Text> : '目录节点，无页面路由' }, { label: '父菜单', children: selectedMenu.parent ? menuTitle(catalog.menus.find(menu => menu.code === selectedMenu.parent)!) : '根节点' }, { label: '可见条件', children: selectedMenu.any_of.length ? '任意一项直接关联能力满足时可见；业务请求另行判权' : '没有直接能力条件' }]} />
            <section className="g-detail-section"><h3>直接关联权限 · {direct.length} 项</h3><CapabilityTable capabilities={direct} onSelect={code => set('capability', code)} /></section>
            <section className="g-detail-section"><h3>包含子菜单的权限</h3><CapabilityTable capabilities={menuCapabilities(catalog, selectedMenu.code, true)} onSelect={code => set('capability', code)} /></section>
            <section className="g-detail-section"><h3>关联固定角色版本</h3><p>按此菜单及子菜单的能力关联，成员访问仍受实际授权和数据范围约束。</p>
              {roles.error ? <Failure error={roles.error} retry={() => void roles.refetch()} /> : roles.isPending ? <Skeleton active /> : <div className="g-directory-links">{relatedRoles(roles.data ?? [], menuCapabilities(catalog, selectedMenu.code, true).map(cap => cap.code)).map(role => <Link key={role.id} to={link('roles', 'role', role.id)}>{role.role_code} · v{role.version}</Link>)}{!relatedRoles(roles.data ?? [], menuCapabilities(catalog, selectedMenu.code, true).map(cap => cap.code)).length && <span>当前没有关联的固定角色版本</span>}</div>}
            </section>
            <Link to={link('catalog', 'menu', selectedMenu.code)}>在权限目录中筛选此菜单</Link>
          </>}
        </section>
      </div> : <>
        <div className="g-catalog-filters">
          <Input.Search aria-label="搜索权限" placeholder="搜索权限或资源编码" value={params.get('q') ?? ''} onChange={event => set('q', event.target.value)} />
          <Select aria-label="筛选菜单" placeholder="全部菜单" allowClear value={selectedMenu?.code} options={orderedMenus(catalog).map(menu => ({ value: menu.code, label: menuTitle(menu) }))} onChange={value => set('menu', value)} />
          <Select aria-label="筛选资源" placeholder="全部资源类型" allowClear value={resource || undefined} options={catalog.resource_types.map(type => ({ value: type.code, label: type.code }))} onChange={value => set('resource', value)} />
          <Select aria-label="筛选风险" placeholder="全部风险等级" allowClear value={risk || undefined} options={[{ value: 'NORMAL', label: '普通风险' }, { value: 'HIGH', label: '高风险' }]} onChange={value => set('risk', value)} />
          <Select aria-label="筛选权限状态" placeholder="全部权限状态" allowClear value={status || undefined} options={[{ value: CatalogFilter.Grantable, label: '当前可授予' }, { value: CatalogFilter.Restricted, label: '超当前委派' }, { value: CatalogFilter.Disabled, label: '已停用' }, { value: CatalogFilter.Deprecated, label: '已弃用 · 停止新增' }, { value: CatalogFilter.Unlinked, label: '未关联菜单' }]} onChange={value => set('status', value)} />
          <Button onClick={reset}>重置筛选</Button>
        </div>
        <p className="g-result-count">匹配 {caps.length} 项 / 当前目录 {catalog.capabilities.length} 项</p>
        <CapabilityTable capabilities={caps} catalog={catalog} onSelect={code => set('capability', code)} page={Number(params.get('catalog_page')) || 1} onPage={page => { const next = new URLSearchParams(params); next.set('catalog_page', String(page)); setParams(next) }} />
      </>}
    </Card>
    {params.get('capability') && <Card className="g-catalog-detail-card" title="权限详情" extra={<Button onClick={() => set('capability')}>关闭详情</Button>}>
      {!selectedCapability ? <Alert type="warning" message="当前目录未找到该能力，可能已变更，请刷新目录。" /> : <>
        <Typography.Title level={4} className="g-code-heading">{selectedCapability.code}</Typography.Title>
        <Descriptions column={1} items={[{ label: '资源类型', children: selectedCapability.resource_type }, { label: '风险等级', children: selectedCapability.risk_level === 'HIGH' ? '高风险' : '普通风险' }, { label: '当前状态', children: <CapabilityStatus capability={selectedCapability} /> }, { label: '支持范围', children: catalog.resource_types.find(type => type.code === selectedCapability.resource_type)?.allowed_scope_kinds.join(' / ') || '当前未绑定可执行范围' }]} />
        <CapabilityRetirementPanel key={`retire:${partition.application_id}:${selectedCapability.code}`} partition={partition} capability={selectedCapability.code} owner={catalog.lifecycle_owner === true} refresh={async () => { await query.refetch(); await roles.refetch() }} />
        <CapabilityLifecycleControl key={`${partition.application_id}:${selectedCapability.code}`} application={partition.application_id} capability={selectedCapability} owner={catalog.lifecycle_owner === true} refreshing={query.isFetching} refresh={async () => { await query.refetch(); await roles.refetch() }} />
        <section className="g-detail-section"><h3>直接关联菜单</h3><Space wrap>{capabilityMenus(catalog, selectedCapability.code).map(menu => <Link key={menu.code} to={link('menus', 'menu', menu.code)}>{menuTitle(menu)}</Link>)}{!capabilityMenus(catalog, selectedCapability.code).length && <span>{selectedCapability.lifecycle_state === LifecycleState.RETIRED ? '历史清单未关联菜单；此能力已最终退役，不能用于新增授权或当前访问。' : '未关联菜单；该权限仍可用于接口或其他业务能力。'}</span>}</Space></section>
        <section className="g-detail-section"><h3>关联固定角色版本</h3><p>以下关联表示角色包含此权限，成员的实际访问还受授权范围、有效期和实时判权约束。</p>
          {roles.error ? <Failure error={roles.error} retry={() => void roles.refetch()} /> : roles.isPending ? <Skeleton active /> : <div className="g-directory-links">{relatedRoles(roles.data ?? [], [selectedCapability.code]).map(role => <Link key={role.id} to={link('roles', 'role', role.id)}>{role.role_code} · v{role.version}</Link>)}{!relatedRoles(roles.data ?? [], [selectedCapability.code]).length && <span>当前没有包含此权限的固定角色版本</span>}</div>}
        </section>
      </>}
    </Card>}
  </>
}

export function CapabilityStatus({ capability: cap }: { capability: PublishedCapability }) {
  return <Space wrap>{cap.lifecycle_state === LifecycleState.RETIRED && <Tag>已最终退役</Tag>}{cap.lifecycle_state === LifecycleState.DEPRECATED && <Tag color="warning">已弃用 · 停止新增</Tag>}{cap.lifecycle_state == null && <Tag>生命周期信息缺失</Tag>}<Tag color={cap.disabled ? 'error' : cap.grantable ? 'success' : undefined}>{cap.disabled ? '已停用' : cap.grantable ? '当前可授予' : cap.lifecycle_state === LifecycleState.RETIRED ? '历史墓碑' : cap.lifecycle_state === LifecycleState.DEPRECATED ? '保留现有使用' : '超当前委派'}</Tag></Space>
}

function CapabilityTable({ capabilities, catalog, onSelect, page, onPage }: { capabilities: PublishedCapability[]; catalog?: PublishedCatalog; onSelect: (code: string) => void; page?: number; onPage?: (page: number) => void }) {
  return <Table<PublishedCapability> rowKey="code" dataSource={capabilities} size="middle" pagination={onPage ? { current: page, pageSize: 20, showSizeChanger: false, onChange: onPage } : false} scroll={{ x: 650 }} locale={{ emptyText: <GovernanceEmpty title="没有匹配的权限" description="调整筛选或选择其他菜单。" /> }} columns={[
    { title: '权限编码', dataIndex: 'code', width: 260, render: code => <Button type="link" className="g-code-link" onClick={() => onSelect(code)}>{code}</Button> },
    { title: '资源类型', dataIndex: 'resource_type', width: 160 }, { title: '风险', dataIndex: 'risk_level', render: risk => <Tag color={risk === 'HIGH' ? 'warning' : undefined}>{risk === 'HIGH' ? '高风险' : '普通'}</Tag> },
    { title: '状态', render: (_, cap) => <CapabilityStatus capability={cap} /> }, ...(catalog ? [{ title: '关联菜单', render: (_: unknown, cap: PublishedCapability) => capabilityMenus(catalog, cap.code).map(menu => menuTitle(menu)).join('、') || '未关联' }] : []),
  ]} />
}
