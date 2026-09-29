import { Alert, Button, Card, Descriptions, Drawer, Modal, Space, Table, Tag, Typography } from 'antd'
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
    <Card title="我的权限来源" extra={<Button onClick={() => void list.refetch()}>刷新权限来源</Button>}>
      <Alert type="info" showIcon message="每行是一条完整授权来源。范围和能力只能在同一行内共同使用；其他来源不会因单条撤销而消失。" style={{ marginBottom: 16 }} />
      {list.error ? <Failure error={list.error} retry={() => void list.refetch()} /> : <Table<PermissionExplanation> rowKey="grant_id" dataSource={list.data?.items} loading={list.isPending} pagination={false} scroll={{ x: 850 }} columns={[
        { title: '固定角色', render: (_, r) => `${r.role_code} · v${r.role_version}` }, { title: '能力', render: (_, r) => r.capabilities.join('、') },
        { title: '来源', dataIndex: 'source_type' }, { title: '当前状态', render: (_, r) => <Tag>{labels[r.effective_state] ?? r.effective_state}</Tag> },
        { title: '有效至', render: (_, r) => new Date(r.valid_to).toLocaleString() }, { title: '操作', render: (_, r) => <Button type="link" onClick={() => choose('grant', r.grant_id)}>查看来源详情</Button> },
      ]} />}
      <Space style={{ marginTop: 12 }}>{after && <Button onClick={() => { choose('permission_after'); }}>权限首页</Button>}{list.data?.next_cursor && <Button onClick={() => choose('permission_after', list.data!.next_cursor!)}>下一页权限</Button>}</Space>
    </Card>
    <Drawer title="同一授权来源详情" open={!!selected} onClose={() => choose('grant')} width={640}>{list.error ? <Failure error={list.error} /> : detail ? <PermissionDetails value={detail} /> : <Alert type="info" message="当前页未找到该来源，请返回列表重新选择。" />}</Drawer>
  </>
}

/** 仅呈现服务端当前固定事实，组受益人不虚构为单一成员。 */
function PermissionDetails({ value: g }: { value: PermissionExplanation }) {
  return <><Alert type="info" showIcon message={labels[g.effective_state] ?? g.effective_state} style={{ marginBottom: 16 }} />
    <Descriptions bordered column={1} items={[
      { label: '授权编号', children: <Typography.Text copyable>{g.grant_id}</Typography.Text> },
      { label: '受益方', children: g.group_id ? `目录组 ${g.group_id}` : `${g.member_id} · 第 ${g.generation} 代` },
      { label: '固定角色版本', children: `${g.role_code} · v${g.role_version}` }, { label: '能力', children: g.capabilities.join('、') },
      { label: '同来源范围', children: g.scope_rule ? <ScopeSummary rule={g.scope_rule} /> : g.scope === ScopeKind.TENANT_ALL ? '当前企业全部资源' : '范围不可用' },
      { label: '来源类型 / 标识', children: <span style={{ overflowWrap: 'anywhere' }}>{g.source_type} / {g.source_id}</span> },
      { label: '固定有效期', children: `${new Date(g.valid_from).toLocaleString()} — ${new Date(g.valid_to).toLocaleString()}` },
      { label: '当前版本投影回执', children: g.operation_id ?? '尚无回执' },
      { label: '策略 / 目录', children: `${g.policy_state} / ${g.directory_state}` },
    ]} /></>
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
  return <Card title="当前应用授权审计" extra={<Button onClick={() => void list.refetch()}>刷新审计</Button>}>
    {list.error ? <Failure error={list.error} retry={() => void list.refetch()} /> : <Table<AccessAudit> rowKey="id" loading={list.isPending} dataSource={list.data?.items} pagination={false} scroll={{ x: 1150 }} columns={[
      { title: '时间', render: (_, r) => new Date(r.occurred_at).toLocaleString() }, { title: '操作者', dataIndex: 'operator_ref' },
      { title: '操作', dataIndex: 'operation' }, { title: '目标', dataIndex: 'target_id' }, { title: '目标版本', dataIndex: 'target_version' }, { title: '结果', dataIndex: 'outcome' },
    ]} />}
    <Space style={{ marginTop: 12 }}>{after && <Button onClick={() => cursor()}>审计首页</Button>}{list.data?.next_cursor && <Button onClick={() => cursor(list.data!.next_cursor!)}>下一页审计</Button>}</Space>
  </Card>
}
