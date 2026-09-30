import { GovernanceDrawer as Drawer, CapabilityList, GovernanceEmpty } from '../governance/presentation'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Modal, Select, Space, Table, Tabs, Tag, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { accessState, createRole, grantScoped, management, members, roleImpact, type Grant, type Management, type Role } from '../api/governance'
import { useGovernanceContext } from './GovernancePage'
import { Failure } from '../governance/feedback'
import { useCommand } from '../governance/useCommand'
import { ProjectionState } from '../governance/codes'
import { CatalogEditor } from '../governance/CatalogEditor'
import { ScopeFields, scopeRule, type ScopeInput } from '../governance/ScopeFields'

const projectionLabels: Record<string, string> = { READY: '已同步', UPDATING: '同步中', BLOCKED: '同步失败，等待恢复', LEGACY: '旧版投影' }
const grantLabels: Record<string, string> = { PENDING: '待生效', ACTIVE: '图已确认', REVOKED: '已撤销' }

/** 管理页面使用真实委派与固定版本，表单提示不代替后端写入检查。 */
export default function GovernanceAccessPage() {
  const { partition, queryKey } = useGovernanceContext()
  const qc = useQueryClient()
  const [params, setParams] = useSearchParams()
  const cursors = { role: params.get('role_after') ?? undefined, grant: params.get('grant_after') ?? undefined }
  const setCursors = (value: { role?: string; grant?: string }) => {
    const next = new URLSearchParams(params)
    for (const key of ['role', 'grant'] as const) { if (value[key]) next.set(`${key}_after`, value[key]!); else next.delete(`${key}_after`) }
    setParams(next)
  }
  const [roleOpen, setRoleOpen] = useState(false)
  const [grantOpen, setGrantOpen] = useState(false)
  const [catalogOpen, setCatalogOpen] = useState(false)
  const [copy, setCopy] = useState<Role>()
  const [selected, setSelected] = useState<Role>()
  const authority = useQuery({ queryKey: [...queryKey, 'management'], queryFn: () => management(partition), retry: false, staleTime: 0, gcTime: 0 })
  const result = useQuery({ queryKey: [...queryKey, 'access', cursors], queryFn: () => accessState(partition, cursors.role, cursors.grant), retry: false, staleTime: 0, gcTime: 0 })
  const impact = useQuery({ queryKey: [...queryKey, 'impact', selected?.id], queryFn: () => roleImpact(partition, selected!.id), enabled: !!selected, retry: false, gcTime: 0 })
  const refresh = () => { void qc.invalidateQueries({ queryKey }) }
  if (authority.error || result.error) return <Failure error={authority.error ?? result.error} retry={refresh} />
  return <>
    <Card title="角色与成员授权" loading={result.isPending || authority.isPending} extra={<Button onClick={refresh}>刷新状态</Button>}>
      {authority.data && <Alert type={authority.data.policy_state === ProjectionState.READY && authority.data.directory_state === ProjectionState.READY ? 'info' : 'warning'} showIcon
        message={`权限投影：${projectionLabels[authority.data.policy_state] ?? '未知'} · 成员同步：${projectionLabels[authority.data.directory_state] ?? '未知'}`}
        description="受理不等于生效。角色新版本不会自动迁移旧授权；扩权必须重新授予或审批。" style={{ marginBottom: 20 }} />}
      <Space wrap style={{ marginBottom: 20 }}>
        <Button type="primary" onClick={() => { setCopy(undefined); setRoleOpen(true) }}>创建角色版本</Button>
        <Button onClick={() => setGrantOpen(true)}>授予成员</Button>
        {authority.data?.catalog_owner && <Button onClick={() => setCatalogOpen(true)}>应用清单</Button>}
      </Space>
      <Tabs activeKey={params.get('access_tab') === 'grants' ? 'grants' : 'roles'} onChange={key => { const next = new URLSearchParams(params); next.set('access_tab', key); setParams(next, { replace: true }) }} items={[
        { key: 'roles', label: '固定角色版本', children: <>
      <div className="g-data-heading"><p>版本创建后保持不变，扩权通过新版本重新授予。</p></div>
      <Table<Role> rowKey="id" locale={{ emptyText: <GovernanceEmpty title="暂无角色版本" description="创建固定角色版本后，再按范围授予成员。" /> }} dataSource={result.data?.roles} pagination={false} scroll={{ x: 700 }} columns={[
        { title: '角色', dataIndex: 'role_code', width: 220 }, { title: '版本', dataIndex: 'version', width: 75 },
        { title: '能力', dataIndex: 'capabilities', render: (caps: string[]) => <CapabilityList values={caps} compact /> },
        { title: '操作', width: 230, render: (_, row) => <Space><Button type="link" onClick={() => setSelected(row)}>查看差异</Button><Button type="link" onClick={() => { setCopy(row); setRoleOpen(true) }}>复制新版本</Button></Space> },
      ]} />
      <Space style={{ marginTop: 12 }}>{cursors.role && <Button onClick={() => setCursors({ ...cursors, role: undefined })}>角色首页</Button>}{result.data?.next_role_cursor && <Button onClick={() => setCursors({ ...cursors, role: result.data!.next_role_cursor! })}>下一页角色</Button>}</Space>
        </> },
        { key: 'grants', label: '成员授权', children: <>
      <div className="g-data-heading"><p>每条记录独立保留受益成员、资源范围与有效期。</p></div>
      <Table<Grant> rowKey="id" locale={{ emptyText: <GovernanceEmpty title="暂无成员授权" description="授予成员后，可在这里跟进授权记录与来源。" /> }} dataSource={result.data?.grants} pagination={false} scroll={{ x: 1000 }} columns={[
        { title: '成员 / 代际', width: 250, render: (_, row) => <><Typography.Text className="mono" copyable>{row.member_id}</Typography.Text><div>第 {row.member_generation} 代</div></> },
        { title: '操作', width: 130, render: (_, row) => { const next = new URLSearchParams(params); next.set('grant', row.id); return <Link to={`/governance/diagnostic?${next}`}>解释 / 撤权</Link> } },
        { title: '来源', dataIndex: 'source_type', width: 150 }, { title: '范围', dataIndex: 'scope', render: value => value === 'TENANT_ALL' ? '当前企业全部资源' : '查看来源详情' },
        { title: '有效期（本地时间）', width: 200, render: (_, row) => <Space direction="vertical" size={0}><span>{new Date(row.valid_from).toLocaleString('zh-CN', { hour12: false })}</span><span>至 {new Date(row.valid_to).toLocaleString('zh-CN', { hour12: false })}</span></Space> },
        { title: '授权记录', dataIndex: 'state', render: value => <Tag color={value === 'PENDING' ? 'warning' : 'default'}>{grantLabels[value] ?? '未知状态'}</Tag> },
      ]} />
      <Space style={{ marginTop: 12 }}>{cursors.grant && <Button onClick={() => setCursors({ ...cursors, grant: undefined })}>授权首页</Button>}{result.data?.next_grant_cursor && <Button onClick={() => setCursors({ ...cursors, grant: result.data!.next_grant_cursor! })}>下一页授权</Button>}</Space>        </> },
      ]} />
    </Card>
    <Drawer title={`${selected?.role_code ?? ''} · 版本 ${selected?.version ?? ''}`} open={!!selected} onClose={() => setSelected(undefined)} width={560}>
      {impact.error ? <Failure error={impact.error} retry={() => void impact.refetch()} /> : <Card loading={impact.isPending}>
        <Descriptions column={1} items={[
          { label: '引用此版本的授权数', children: impact.data?.referencing_grant_count },
          { label: '新增能力', children: <CapabilityList values={impact.data?.added ?? []} /> }, { label: '移除能力', children: <CapabilityList values={impact.data?.removed ?? []} /> },
          { label: '版本处理', children: '旧授权保持固定版本；如需扩权，请重新授予或申请审批。' },
        ]} />
      </Card>}
    </Drawer>
    {catalogOpen && <CatalogEditor application={partition.application_id} close={() => setCatalogOpen(false)} saved={refresh} />}
    {roleOpen && authority.data && <RoleEditor authority={authority.data} copy={copy} close={() => setRoleOpen(false)} saved={refresh} />}
    {grantOpen && authority.data && <GrantEditor authority={authority.data} roles={result.data?.roles ?? []} close={() => setGrantOpen(false)} saved={refresh} />}
  </>
}

function RoleEditor({ authority, copy, close, saved }: { authority: Management; copy?: Role; close: () => void; saved: () => void }) {
  const { partition } = useGovernanceContext()
  const [form] = Form.useForm()
  const command = useCommand(createRole)
  const [dirty, setDirty] = useState(false)
  const finish = async (values: { role_code: string; role_version: number; capabilities: string[] }) => {
    const response = await command.send(id => ({ ...partition, command_id: id, ...values }))
    if (response) saved()
  }
  const cancel = () => {
    if (command.busy || command.unknown) return
    if (dirty && !command.result) Modal.confirm({ title: '放弃尚未提交的角色编辑？', okText: '放弃编辑', cancelText: '继续编辑', onOk: close })
    else close()
  }
  return <Drawer title={copy ? '创建角色新版本' : '创建角色版本'} open onClose={cancel} width={560} maskClosable={false} footer={!command.result && (<Button type="primary" loading={command.busy} onClick={() => command.unknown ? void finish(form.getFieldsValue()) : form.submit()}>{command.unknown ? '重试原命令' : '创建固定版本'}</Button>)}
    extra={<Button disabled={command.busy || command.unknown} onClick={cancel}>关闭</Button>}>
    {!!command.error && <Failure error={command.error} />}
    {command.unknown && <Alert type="warning" message="提交结果尚未确认。字段已冻结，请原样重试。" />}
    {command.result ? <Alert type="success" showIcon message={`已创建 ${command.result.role_code} · 版本 ${command.result.version}`} description="创建角色不自动授予权限，也不修改既有授权。" /> : <>
      <Form form={form} layout="vertical" onValuesChange={() => setDirty(true)} onFinish={finish} disabled={command.busy || command.unknown}
        initialValues={{ role_code: copy?.role_code, role_version: copy ? copy.version + 1 : 1, capabilities: copy?.capabilities ?? [] }}>
        <Form.Item name="role_code" label="角色编码" rules={[{ required: true, message: '请输入稳定角色编码' }, { pattern: /^[a-z][a-z0-9_.:-]{0,99}$/, message: '使用小写字母开头的稳定编码' }]}><Input disabled={!!copy} /></Form.Item>
        <Form.Item name="role_version" label="新版本号" rules={[{ required: true }]}><InputNumber min={1} precision={0} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="capabilities" label="允许的能力" rules={[{ required: true, message: '至少选择一项当前可授予能力' }]}><Select aria-label="允许的能力" mode="multiple" options={authority.capabilities.map(c => ({ value: c.code, label: `${c.code}${c.disabled ? '（已停用）' : ''}`, disabled: c.disabled }))} /></Form.Item>
      </Form>

    </>}
  </Drawer>
}

interface GrantInput extends ScopeInput { member_id: string; role_id: string; duration_minutes: number; source_id: string }
function GrantEditor({ authority, roles, close, saved }: { authority: Management; roles: Role[]; close: () => void; saved: () => void }) {
  const { partition, queryKey } = useGovernanceContext()
  const [form] = Form.useForm<GrantInput>()
  const [after, setAfter] = useState<string>()
  const [dirty, setDirty] = useState(false)
  const candidates = useQuery({ queryKey: [...queryKey, 'members', after], queryFn: () => members(partition, after), retry: false, gcTime: 0 })
  const command = useCommand(grantScoped)
  const roleId = Form.useWatch('role_id', form)
  const role = roles.find(r => r.id === roleId)
  const resources = [...new Set(authority.capabilities.filter(c => role?.capabilities.includes(c.code)).map(c => c.resource_type))]
  const cancel = () => {
    if (command.busy || command.unknown) return
    if (dirty && !command.result) Modal.confirm({ title: '放弃尚未提交的成员授予？', okText: '放弃编辑', cancelText: '继续编辑', onOk: close })
    else close()
  }
  const finish = async (values: GrantInput) => {
    const response = await command.send(id => {
      const target = candidates.data!.items.find(m => m.membership_id === values.member_id)!
      const from = new Date()
      return { ...partition, command_id: id, member_id: target.membership_id, member_generation: target.generation, role_id: values.role_id,
        scope_rule: scopeRule(values), source_id: values.source_id, valid_from: from.toISOString(), valid_to: new Date(from.getTime() + values.duration_minutes * 60000).toISOString() }
    })
    if (response) saved()
  }
  return <Drawer title="授予成员权限" footer={!command.result && (<Button type="primary" loading={command.busy} disabled={!command.unknown && (candidates.isPending || !!candidates.error)} onClick={() => command.unknown ? void finish(form.getFieldsValue()) : form.submit()}>{command.unknown ? '重试原命令' : '提交授予'}</Button>)} open onClose={cancel} width={600} maskClosable={false} extra={<Button disabled={command.busy || command.unknown} onClick={cancel}>关闭</Button>}>
    {candidates.error ? <Failure error={candidates.error} retry={() => void candidates.refetch()} /> : <>
      {!!command.error && <Failure error={command.error} />}
      {command.unknown && <Alert type="warning" message="结果尚未确认。重试会使用相同成员、范围、时间和命令。" />}
      {command.result ? <Alert type="info" showIcon message="授权已受理，等待实际投影生效" description={`授权标识：${command.result.id}。请关闭并刷新执行状态。`} /> : <>
        <Form form={form} layout="vertical" initialValues={{ duration_minutes: Math.min(30, Math.floor(authority.max_duration_seconds / 60)), scope_kind: 'SPECIFIED_STORES' }}
          onValuesChange={() => setDirty(true)} onFinish={finish} disabled={command.busy || command.unknown || candidates.isPending}>
          <Form.Item name="member_id" label="受益成员" rules={[{ required: true, message: '请选择当前有效成员' }]}><Select aria-label="受益成员" showSearch optionFilterProp="label" options={candidates.data?.items.filter(m => m.membership_id !== authority.membership_id).map(m => ({ value: m.membership_id, label: `${m.member_kind} · ${m.membership_id} · 第${m.generation}代` }))} /></Form.Item>
          {candidates.data?.next_cursor && <Button onClick={() => { form.setFieldValue('member_id', undefined); setAfter(candidates.data!.next_cursor!) }}>下一页成员</Button>}
          <Form.Item name="role_id" label="固定角色版本" rules={[{ required: true, message: '请选择角色版本' }]}><Select aria-label="固定角色版本" options={roles.map(r => ({ value: r.id, label: `${r.role_code} · v${r.version}`, disabled: r.capabilities.some(c => !authority.capabilities.some(a => a.code === c && !a.disabled)) }))} onChange={() => form.setFieldValue('resource_type', undefined)} /></Form.Item>
          <ScopeFields resources={resources} />
          <Form.Item name="duration_minutes" label="有效分钟数（从提交时开始）" rules={[{ required: true }]}><InputNumber min={1} max={Math.floor(authority.max_duration_seconds / 60)} precision={0} style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="source_id" label="授权来源说明" rules={[{ required: true, message: '填写便于追溯的来源说明' }, { max: 100 }]}><Input placeholder="例如：门店协作接入批次" /></Form.Item>
        </Form>

      </>}
    </>}
  </Drawer>
}
