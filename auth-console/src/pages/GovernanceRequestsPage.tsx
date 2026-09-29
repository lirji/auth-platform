import { useState } from 'react'
import { Alert, Button, Card, Descriptions, Drawer, Empty, Form, Input, InputNumber, List, Modal, Select, Space, Table, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { cancelRequest, myRequests, requestDetail, requestExecution, requestNotices, requestPolicies, submitRequest, type AccessRequest, type RequestPolicy } from '../api/governance'
import { useGovernanceContext } from './GovernancePage'
import { Failure } from '../governance/feedback'
import { ScopeSummary } from '../governance/ScopeFields'
import { useCommand } from '../governance/useCommand'
import { RequestState, executionLabels, requestLabels } from '../governance/codes'

/** URL只定位本人申请，详情和执行接口仍分别验证当前成员代际。 */
export default function GovernanceRequestsPage() {
  const { partition, queryKey } = useGovernanceContext()
  const [params, setParams] = useSearchParams()
  const qc = useQueryClient()
  const [creating, setCreating] = useState(false)
  const selected = params.get('request') ?? undefined
  const after = params.get('request_after') ?? undefined
  const noticeAfter = params.get('notice_after') ?? undefined
  const list = useQuery({ queryKey: [...queryKey, 'requests', after], queryFn: () => myRequests(partition, after), retry: false })
  const notices = useQuery({ queryKey: [...queryKey, 'notices', noticeAfter], queryFn: () => requestNotices(partition, noticeAfter), retry: false })
  const change = (key: string, value?: string) => { const next = new URLSearchParams(params); if (value) next.set(key, value); else next.delete(key); setParams(next) }
  const refresh = () => { void qc.invalidateQueries({ queryKey }) }
  return <>
    <Card title="我的权限申请" extra={<Space><Button onClick={refresh}>刷新进度</Button><Button type="primary" onClick={() => setCreating(true)}>申请权限</Button></Space>}>
      <Alert type="info" showIcon message="审批通过后仍需等待权限实际生效。通知失败时，可在这里查询当前进度。" style={{ marginBottom: 16 }} />
      {list.error ? <Failure error={list.error} retry={() => void list.refetch()} /> : <Table<AccessRequest> rowKey="id" loading={list.isPending} dataSource={list.data?.items} pagination={false} scroll={{ x: 850 }} columns={[
        { title: '申请原因', dataIndex: 'reason', width: 240 }, { title: '固定能力', render: (_, r) => r.capabilities.join('、') },
        { title: '审批事实', render: (_, r) => requestLabels[r.state] ?? r.state },
        { title: '截止时间', render: (_, r) => new Date(r.valid_to).toLocaleString() },
        { title: '操作', render: (_, r) => <Button type="link" onClick={() => change('request', r.id)}>查看进度</Button> },
      ]} />}
      <Space style={{ marginTop: 12 }}>{after && <Button onClick={() => change('request_after')}>申请首页</Button>}{list.data?.next_cursor && <Button onClick={() => change('request_after', list.data!.next_cursor!)}>下一页申请</Button>}</Space>
    </Card>
    <Card title="我的通知" style={{ marginTop: 16 }}>
      {notices.error ? <Failure error={notices.error} retry={() => void notices.refetch()} /> : <List loading={notices.isPending} dataSource={notices.data?.items} locale={{ emptyText: '暂无申请进度通知' }} renderItem={n =>
        <List.Item actions={[<Button key="detail" type="link" onClick={() => change('request', n.request_id)}>查看申请</Button>]}><span>申请进度已更新 · {new Date(n.delivered_at).toLocaleString()}</span></List.Item>} />}
      <Space>{noticeAfter && <Button onClick={() => change('notice_after')}>通知首页</Button>}{notices.data?.next_cursor && <Button onClick={() => change('notice_after', notices.data!.next_cursor!)}>下一页通知</Button>}</Space>
    </Card>
    {creating && <RequestForm close={() => setCreating(false)} saved={id => { refresh(); setCreating(false); change('request', id) }} />}
    {selected && <RequestDetail key={selected} id={selected} close={() => change('request')} saved={refresh} />}
  </>
}

function RequestForm({ close, saved }: { close: () => void; saved: (id: string) => void }) {
  const { partition, queryKey, organization } = useGovernanceContext()
  const [after, setAfter] = useState<string>()
  const [form] = Form.useForm()
  const [dirty, setDirty] = useState(false)
  const policies = useQuery({ queryKey: [...queryKey, 'request-policies', after], queryFn: () => requestPolicies(partition, after), retry: false })
  const id = Form.useWatch('policy', form)
  const policy = policies.data?.items.find(p => p.id === id)
  const command = useCommand(submitRequest)
  const cancel = () => {
    if (command.busy || command.unknown) return
    if (dirty) Modal.confirm({ title: '放弃未提交的权限申请？', okText: '放弃编辑', cancelText: '继续编辑', onOk: close }); else close()
  }
  const finish = async (values: { policy: string; minutes: number; reason: string }) => {
    const response = await command.send(commandId => { const from = new Date(); return { ...partition, command_id: commandId, policy_id: values.policy, valid_from: from.toISOString(), valid_to: new Date(from.getTime() + values.minutes * 60000).toISOString(), reason: values.reason } })
    if (response) saved(response.id)
  }
  return <Drawer title="申请固定范围权限" open onClose={cancel} width={600} maskClosable={false} extra={<Button disabled={command.busy || command.unknown} onClick={cancel}>关闭</Button>}>
    {policies.error ? <Failure error={policies.error} retry={() => void policies.refetch()} /> : <>
      {!policies.isPending && !policies.data?.items.length && <Empty description="当前没有可申请的策略" />}
      {!!command.error && <Failure error={command.error} />}
      {command.unknown && <Alert type="warning" message="申请结果尚未确认，重试将保留原申请范围和时间。" />}
      <Form form={form} layout="vertical" onValuesChange={() => setDirty(true)} onFinish={finish} initialValues={{ minutes: 5 }} disabled={command.busy || command.unknown}>
        <Form.Item name="policy" label="可申请策略" rules={[{ required: true, message: '请选择可申请策略' }]}><Select aria-label="可申请策略" loading={policies.isFetching} options={policies.data?.items.map(p => ({ value: p.id, label: `${p.role_code} · v${p.role_version} · 策略${p.policy_version}` }))} /></Form.Item>
        {policy && <PolicySummary policy={policy} />}
        <Form.Item name="minutes" label="申请有效分钟数（从提交时开始）" dependencies={['policy']} rules={[{ required: true }, { type: 'number', min: 1, max: Math.floor((policy?.max_duration_seconds ?? 60) / 60), message: '期限须在策略允许范围内' }]}><InputNumber style={{ width: '100%' }} min={1} precision={0} /></Form.Item>
        <Typography.Paragraph type="secondary">当前成员：{organization.member_kind}。成员有效期和审批资格会在服务端再次检查。</Typography.Paragraph>
        <Form.Item name="reason" label="申请原因" rules={[{ required: true, whitespace: true, message: '请说明用途' }, { max: 1000 }]}><Input.TextArea rows={3} maxLength={1000} /></Form.Item>
        <Button type="primary" htmlType="submit" loading={command.busy}>提交申请</Button>
      </Form>
      {command.unknown && <Button type="primary" loading={command.busy} onClick={() => void finish(form.getFieldsValue())}>重试原申请</Button>}
      <Space style={{ marginTop: 16 }}>{after && <Button disabled={command.busy || command.unknown} onClick={() => { setAfter(undefined); form.resetFields(['policy']) }}>策略首页</Button>}{policies.data?.next_cursor && <Button disabled={command.busy || command.unknown} onClick={() => { setAfter(policies.data!.next_cursor!); form.resetFields(['policy']) }}>下一页策略</Button>}</Space>
    </>}
  </Drawer>
}

export function PolicySummary({ policy }: { policy: RequestPolicy }) {
  return <Descriptions bordered column={1} size="small" style={{ marginBottom: 20 }} items={[
    { label: '固定能力', children: policy.capabilities.join('、') }, { label: '数据范围', children: <ScopeSummary rule={policy.scope_rule} /> },
    { label: '最长有效时间', children: `${Math.floor(policy.max_duration_seconds / 60)} 分钟` },
  ]} />
}

function RequestDetail({ id, close, saved }: { id: string; close: () => void; saved: () => void }) {
  const { partition, queryKey } = useGovernanceContext()
  const detail = useQuery({ queryKey: [...queryKey, 'request', id], queryFn: () => requestDetail(partition, id), retry: false, staleTime: 0 })
  const execution = useQuery({ queryKey: [...queryKey, 'execution', id], queryFn: () => requestExecution(partition, id), retry: false, staleTime: 0 })
  const command = useCommand(cancelRequest)
  const r = detail.data, e = execution.data
  const refresh = () => { void detail.refetch(); void execution.refetch() }
  const cancel = async () => { const result = await command.send(commandId => ({ ...partition, id, command_id: commandId, state_version: r!.state_version })); if (result) { refresh(); saved() } }
  return <Drawer title="申请详情与实际进度" open onClose={() => { if (!command.busy && !command.unknown) close() }} width={660} extra={<Button onClick={refresh}>刷新详情</Button>}>
    {detail.error || execution.error ? <Failure error={detail.error ?? execution.error} retry={refresh} /> : <Card loading={detail.isPending || execution.isPending}>
      {r && e && <>
        <Alert type="info" showIcon message={`实际状态：${executionLabels[e.display_state] ?? '待确认'}`} description={`审批事实：${requestLabels[r.state] ?? r.state}；审批通过不代表业务已可访问。`} style={{ marginBottom: 16 }} />
        <Descriptions column={1} bordered items={[
          { label: '申请编号', children: <Typography.Text copyable>{r.id}</Typography.Text> }, { label: '原始申请原因', children: r.reason },
          { label: '固定能力', children: r.capabilities.join('、') }, { label: '固定范围', children: <ScopeSummary rule={r.scope_rule} /> },
          { label: '固定有效期', children: `${new Date(r.valid_from).toLocaleString()} — ${new Date(r.valid_to).toLocaleString()}` },
          { label: '流程启动', children: `${e.start_state} · 已尝试 ${e.start_attempts} 次${e.start_error ? ' · 暂未完成，请稍后刷新' : ''}` },
          { label: '投影回执', children: e.operation_id ?? '尚无当前版本回执' },
          { label: '其他来源', children: `${e.other_active_grant_count} 条当前有效记录；具体资源仍需实时判权。回收本申请不撤销这些来源。` },
        ]} />
        {!!command.error && <Failure error={command.error} />}
        {command.result && <Alert type="warning" message="取消或回收已受理，请刷新查看实际结果。" />}
        {command.unknown ? <Button loading={command.busy} onClick={() => void cancel()}>重试原取消命令</Button> : !command.result && ![RequestState.CANCELLED, RequestState.REJECTED].some(s => s === r.state) && <Button danger style={{ marginTop: 16 }} loading={command.busy} onClick={() => Modal.confirm({ title: '取消申请并回收本来源权限？', content: '已批准时将回收本申请生成的授权，其他合法来源保留。', okText: '确认取消或回收', cancelText: '返回详情', onOk: cancel })}>取消申请 / 回收本来源</Button>}
      </>}
    </Card>}
  </Drawer>
}
