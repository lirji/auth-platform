import { createContext, useContext, useEffect } from 'react'
import { Alert, Button, Card, Empty, Select, Space, Spin, Tag, Typography } from 'antd'
import { useAuth } from 'react-oidc-context'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { applications, organizations, type Organization, type Partition, type PortalApplication } from '../api/governance'
import { PageHeader } from '../components/layout/PageHeader'
import { contextKey, organizationSearch, safeEntry } from '../governance/context'
import { EntryState } from '../governance/codes'
import { Failure } from '../governance/feedback'

export interface GovernanceContext { organization: Organization; application: PortalApplication; partition: Partition; queryKey: readonly unknown[] }
const Context = createContext<GovernanceContext | undefined>(undefined)
export function useGovernanceContext() {
  const context = useContext(Context)
  if (!context) throw new Error('当前应用上下文尚未加载')
  return context
}
const kindLabels: Record<string, string> = { EMPLOYEE: '内部成员', PARTNER: '合作成员', GUEST: '访客' }

/** 以本人组织为入口；每个标签页独立URL，切换后旧查询/表单整个卸载。 */
export default function GovernancePage() {
  const auth = useAuth()
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const location = useLocation()
  const qc = useQueryClient()
  const subject = `${auth.user?.profile.iss ?? ''}:${auth.user?.profile.sub ?? ''}`
  const tenant = params.get('tenant') ?? ''
  const orgs = useQuery({ queryKey: ['governance', subject, 'organizations'], queryFn: ({ signal }) => organizations(signal), staleTime: 0, gcTime: 0, retry: false })
  const org = !orgs.error && orgs.data?.find(item => item.tenant_id === tenant)
  const directoryKey = contextKey(subject, org ? org.membership_id : '', org ? org.generation : 0, { tenant_id: tenant, application_id: '', environment: '' })
  const after = params.get('after') ?? undefined
  const apps = useQuery({ queryKey: [...directoryKey, 'applications', after], queryFn: ({ signal }) => applications(tenant, after, signal), enabled: !!org, staleTime: 0, gcTime: 0, retry: false })
  const entries = !apps.error ? apps.data?.items ?? [] : []
  const app = entries.find(item => item.application_id === params.get('application') && item.environment === params.get('environment'))
  const partition = { tenant_id: tenant, application_id: app?.application_id ?? '', environment: app?.environment ?? '' }
  const scopedKey = contextKey(subject, org ? org.membership_id : '', org ? org.generation : 0, partition)
  const scope = org && app ? { organization: org, application: app, partition, queryKey: scopedKey } : undefined
  const home = location.pathname === '/governance' || location.pathname === '/governance/'

  useEffect(() => {
    if (!tenant && orgs.data?.length) setParams(organizationSearch(orgs.data[0].tenant_id), { replace: true })
  }, [tenant, orgs.data, setParams])
  const changeOrganization = (id: string) => {
    void qc.cancelQueries({ queryKey: ['governance', subject] })
    qc.removeQueries({ queryKey: ['governance', subject], predicate: query => query.queryKey[2] !== 'organizations' })
    navigate(`/governance?${organizationSearch(id)}`)
  }
  const choose = (item: PortalApplication, path: string) => {
    const next = new URLSearchParams(params)
    next.set('application', item.application_id); next.set('environment', item.environment)
    navigate(`${path}?${next}`)
  }
  return <main className="app-content" style={{ width: '100%', boxSizing: 'border-box' }}>
    <PageHeader title="我的工作台" description="在当前组织内查看应用、权限和申请进度。"
      extra={<Space wrap><Link to="/invitations/accept">接受邀请</Link><Button onClick={() => void auth.signoutRedirect()}>退出登录</Button></Space>} />
    <Card style={{ marginBottom: 20 }}>
      <Space wrap size="middle">
        <Typography.Text strong>当前组织</Typography.Text>
        <Select aria-label="当前组织" placeholder="选择组织" value={org ? tenant : undefined} loading={orgs.isFetching}
          style={{ width: 260, maxWidth: '100%' }} onChange={changeOrganization}
          options={orgs.data?.map(item => ({ value: item.tenant_id, label: item.tenant_code }))} />
        {org && <Tag>{kindLabels[org.member_kind] ?? org.member_kind}</Tag>}
        {!home && <Link to={`/governance?${params}`}>返回我的应用</Link>}
      </Space>
    </Card>
    {orgs.error ? <Failure error={orgs.error} retry={() => void orgs.refetch()} /> : orgs.isPending ? <Spin aria-label="正在加载组织" />
      : !org ? <Empty description={tenant ? '当前组织不可用，请重新选择有效组织' : '当前没有有效组织'} />
      : apps.error ? <Failure error={apps.error} retry={() => void apps.refetch()} /> : apps.isPending ? <Spin aria-label="正在加载应用" />
      : home ? <>
        <PageHeader title="我的应用" description="业务入口按当前权限展示；权限管理与业务访问分别授权。" extra={<Button loading={apps.isFetching} onClick={() => void apps.refetch()}>刷新应用</Button>} />
        {!entries.length && <Empty description="当前没有关联应用" />}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 300px), 1fr))', gap: 16 }}>
          {entries.map(item => <Card key={`${item.application_id}/${item.environment}`} title={<span style={{ overflowWrap: 'anywhere' }}>{item.application_id}</span>} extra={<Tag>{item.environment}</Tag>}>
            {item.menus.some(menu => safeEntry(menu.href)) ? <Space direction="vertical" style={{ width: '100%' }}>
              {item.menus.filter(menu => safeEntry(menu.href)).map(menu => <Button key={menu.code} href={safeEntry(menu.href)} target="_blank" rel="noopener noreferrer">进入 {menu.code}</Button>)}
            </Space> : <Typography.Paragraph type="secondary">{item.entry_state === EntryState.UNAVAILABLE ? '暂时无法确认业务权限，业务入口已关闭。可继续查看申请或管理进度。' : '当前暂无可用业务入口'}</Typography.Paragraph>}
            <Space wrap style={{ marginTop: 16 }}><Button onClick={() => choose(item, '/governance/permissions')}>我的权限来源</Button><Button onClick={() => choose(item, '/governance/requests')}>我的申请与通知</Button></Space>
            {item.management && <Button style={{ marginTop: 16 }} onClick={() => choose(item, '/governance/access')}>查看授权管理</Button>}
            {item.management && <Space wrap style={{ marginTop: 12 }}><Button onClick={() => choose(item, '/governance/policies')}>申请策略</Button><Button onClick={() => choose(item, '/governance/invitations')}>外部邀请</Button><Button onClick={() => choose(item, '/governance/audit')}>授权审计</Button></Space>}
          </Card>)}
        </div>
        <Space style={{ marginTop: 20 }}>
          {after && <Button onClick={() => setParams(organizationSearch(tenant))}>返回首页</Button>}
          {apps.data?.next_cursor && <Button onClick={() => { const next = organizationSearch(tenant); next.set('after', apps.data!.next_cursor!); setParams(next) }}>下一页应用</Button>}
        </Space>
      </> : scope ? <Context.Provider key={JSON.stringify(scopedKey)} value={scope}><Outlet /></Context.Provider>
        : <Alert type="warning" showIcon message="当前应用不在本组织的关联目录中，请返回我的应用重新选择。" />}
  </main>
}
