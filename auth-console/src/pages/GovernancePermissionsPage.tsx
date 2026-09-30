import { GovernanceDrawer as Drawer, CapabilityList, PermissionStatus, GovernanceEmpty } from '../governance/presentation'
import { Alert, Button, Card, Descriptions, Modal, Space, Table, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { accessAudit, grantExplanation, myPermissions, revocationReceipt, revokeGrant, type AccessAudit, type PermissionExplanation } from '../api/governance'
import { useGovernanceContext } from './GovernancePage'
import { Failure } from '../governance/feedback'
import { ScopeSummary } from '../governance/ScopeFields'
import { useCommand } from '../governance/useCommand'
import { executionLabels, GrantState, ReceiptState, ScopeKind } from '../governance/codes'

const labels: Record<string, string> = { ...executionLabels, ACTIVE: '投影已确认，业务操作仍实时判权', GROUP_CHECK_REQUIRED: '组授权已投影，成员资格与时段仍实时检查' }
/** 来源不合并，避免把多个Grant的能力和范围拼接成不存在的授权。 */
export default function GovernancePermissionsPage() {
  const { partition, queryKey } = useGovernanceContext()
  const [params, setParams] = useSearchParams()
  const after = params.get('permission_after') ?? undefined
  const selected = params.get('grant') ?? undefined
  const list = useQuery({ queryKey: [...queryKey, 'permissions', after], queryFn: () => myPermissions(partition, after), retry: false, staleTime: 0 })
  const choose = (key: string, value?: string) => { const next = new URLSearchParams(params); if (value) next.set(key, value); else next.delete(key); setParams(next) }
  const detail = !list.error ? list.data?.items.find(g => g.grant_id === selected) : undefined
  return <>
    <Card title="授权来源" extra={<Button onClick={() => void list.refetch()}>刷新权限来源</Button>}>
      <Alert type="info" showIcon message="每行是一条完整授权来源。范围和能力只能在同一行内共同使用；其他来源不会因单条撤销而消失。" style={{ marginBottom: 16 }} />
      {list.error ? <Failure error={list.error} retry={() => void list.refetch()} /> : <Table<PermissionExplanation> rowKey="grant_id" locale={{ emptyText: <GovernanceEmpty title="暂无权限来源" description="获得业务授权后，角色、范围与来源将显示在这里。" /> }} dataSource={list.data?.items} loading={list.isPending} pagination={false} scroll={{ x: 850 }} columns={[
        { title: '固定角色', width: 180, render: (_, r) => <div className="g-role-cell"><strong>{r.role_code}</strong><small>版本 {r.role_version}</small></div> }, { title: '能力', width: 260, render: (_, r) => <CapabilityList values={r.capabilities} compact /> },
        { title: '来源', dataIndex: 'source_type' }, { title: '当前状态', render: (_, r) => <PermissionStatus state={r.effective_state} explanation={labels[r.effective_state]} /> },
        { title: '有效至', render: (_, r) => new Date(r.valid_to).toLocaleString('zh-CN', { hour12: false }) }, { title: '操作', render: (_, r) => <Button type="link" onClick={() => choose('grant', r.grant_id)}>查看来源详情</Button> },
      ]} />}
      <Space style={{ marginTop: 12 }}>{after && <Button onClick={() => { choose('permission_after'); }}>权限首页</Button>}{list.data?.next_cursor && <Button onClick={() => choose('permission_after', list.data!.next_cursor!)}>下一页权限</Button>}</Space>
    </Card>
    <Drawer title="同一授权来源详情" open={!!selected} onClose={() => choose('grant')} width={640}>{list.error ? <Failure error={list.error} /> : detail ? <PermissionDetails value={detail} /> : <Alert type="info" message="当前页未找到该来源，请返回列表重新选择。" />}</Drawer>
  </>
}

/** 仅呈现服务端当前固定事实，组受益人不虚构为单一成员。 */
function PermissionDetails({ value: g }: { value: PermissionExplanation }) {
  return <div className="g-permission-details">
    <div className="g-detail-heading"><span className="g-detail-eyebrow">授权来源</span><h2>{g.role_code} <small>v{g.role_version}</small></h2><PermissionStatus state={g.effective_state} explanation={labels[g.effective_state]} /></div>
    {['ACTIVE', 'GROUP_CHECK_REQUIRED'].includes(g.effective_state) && <Alert type="info" showIcon message={labels[g.effective_state]} />}
    <section className="g-detail-section"><h3>能力与资源范围</h3><p>以下能力与范围仅属于这一条授权。</p><CapabilityList values={g.capabilities} /><div className="g-scope-summary">{g.scope_rule ? <ScopeSummary rule={g.scope_rule} /> : g.scope === ScopeKind.TENANT_ALL ? '当前企业全部资源' : '范围不可用'}</div></section>
    <section className="g-detail-section"><h3>受益方与有效期</h3><Descriptions column={1} items={[
      { label: '受益方', children: g.group_id ? `目录组 ${g.group_id}` : `${g.member_id} · 第 ${g.generation} 代` },
      { label: '开始时间', children: new Date(g.valid_from).toLocaleString('zh-CN', { hour12: false }) },
      { label: '截止时间', children: new Date(g.valid_to).toLocaleString('zh-CN', { hour12: false }) },
    ]} /></section>
    <section className="g-detail-section"><h3>来源追溯</h3><Descriptions column={1} items={[
      { label: '授权编号', children: <Typography.Text copyable>{g.grant_id}</Typography.Text> },
      { label: '来源类型', children: g.source_type }, { label: '来源标识', children: g.source_id },
      { label: '投影回执', children: g.operation_id ?? '尚无回执' },
      { label: '策略 / 目录', children: `${g.policy_state} / ${g.directory_state}` },
    ]} /></section>
  </div>
}

/** 只有单独诊断权限可读取该页，撤权命令另由原管理用例再次验证。 */
export function GovernanceGrantDiagnostic() {
  const { partition, queryKey } = useGovernanceContext()
  const [params] = useSearchParams(), grant = params.get('grant') ?? ''
  const qc = useQueryClient()
  const detail = useQuery({ queryKey: [...queryKey, 'explanation', grant], queryFn: () => grantExplanation(partition, grant), enabled: !!grant, retry: false, staleTime: 0 })
  const receipt = useQuery({ queryKey: [...queryKey, 'revocation', grant], queryFn: () => revocationReceipt(partition, grant), enabled: !!detail.data && !detail.error && detail.data.grant_state === GrantState.REVOKED, retry: false, staleTime: 0 })
  const command = useCommand(revokeGrant)
  const refresh = () => { void qc.invalidateQueries({ queryKey }) }
  const revoke = async () => { const response = await command.send(id => ({ ...partition, command_id: id, grant_id: grant, expected_version: detail.data!.grant_version })); if (response) refresh() }
  if (!grant) return <Alert type="warning" message="请从授权管理选择一条来源。" />
  if (detail.error) return <Failure error={detail.error} retry={refresh} />
  const status = receipt.error ? undefined : receipt.data ?? command.result
  return <Card title="授权诊断与来源回收" loading={detail.isPending} extra={<Button onClick={refresh}>刷新解释与回执</Button>}>
    {detail.data && <>
      <PermissionDetails value={detail.data} />
      {!!command.error && <Failure error={command.error} />}{receipt.error && <Failure error={receipt.error} retry={() => void receipt.refetch()} />}
      {status && <Alert style={{ marginTop: 16 }} showIcon type={status.status === ReceiptState.COMPLETED ? 'success' : 'warning'} message={status.status === ReceiptState.COMPLETED ? '本来源回收已完成' : status.status === ReceiptState.BLOCKED ? '回收投影遇到故障，请保留命令并联系运维恢复' : '回收已受理，等待实际投影回执'} description={`其他合法来源继续保留。回执：${status.operation_id ?? '尚未生成'}`} />}
      {command.unknown ? <Button loading={command.busy} style={{ marginTop: 16 }} onClick={() => void revoke()}>重试原撤权命令</Button> : detail.data.grant_state !== GrantState.REVOKED && <Button danger style={{ marginTop: 16 }} loading={command.busy} onClick={() => Modal.confirm({ title: '回收这一条授权来源？', content: '仅撤销当前Grant。其他来源、其他申请和查询权限不被一并回收。', okText: '确认回收本来源', cancelText: '保留来源', onOk: revoke })}>回收本来源</Button>}
    </>}
  </Card>
}

/** 审计只读，不提供删除按钮；游标和页面缓存仍绑定完整主体/分区。 */
export function GovernanceAuditPage() {
  const { partition, queryKey } = useGovernanceContext()
  const [params, setParams] = useSearchParams(), after = params.get('audit_after') ?? undefined
  const list = useQuery({ queryKey: [...queryKey, 'audit', after], queryFn: () => accessAudit(partition, after), retry: false, staleTime: 0 })
  const cursor = (value?: string) => { const next = new URLSearchParams(params); if (value) next.set('audit_after', value); else next.delete('audit_after'); setParams(next) }
  return <Card title="操作记录" extra={<Button onClick={() => void list.refetch()}>刷新审计</Button>}>
    {list.error ? <Failure error={list.error} retry={() => void list.refetch()} /> : <Table<AccessAudit> rowKey="id" locale={{ emptyText: <GovernanceEmpty title="暂无授权操作记录" description="当前应用中的授权操作会在这里留下审计记录。" /> }} loading={list.isPending} dataSource={list.data?.items} pagination={false} scroll={{ x: 1150 }} columns={[
      { title: '时间', render: (_, r) => new Date(r.occurred_at).toLocaleString('zh-CN', { hour12: false }) }, { title: '操作者', dataIndex: 'operator_ref', width: 260 },
      { title: '操作', dataIndex: 'operation' }, { title: '目标', dataIndex: 'target_id', width: 260 }, { title: '目标版本', dataIndex: 'target_version' }, { title: '结果', dataIndex: 'outcome' },
    ]} />}
    <Space style={{ marginTop: 12 }}>{after && <Button onClick={() => cursor()}>审计首页</Button>}{list.data?.next_cursor && <Button onClick={() => cursor(list.data!.next_cursor!)}>下一页审计</Button>}</Space>
  </Card>
}
