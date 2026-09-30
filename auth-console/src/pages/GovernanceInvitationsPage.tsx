import { GovernanceDrawer as Drawer, GovernanceEmpty } from '../governance/presentation'
import { useState } from 'react'
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { isAxiosError, HttpStatusCode } from 'axios'
import { invitationAuthority, invitations, issueInvitation, revokeInvitation, type Invitation, type InvitationAuthority } from '../api/governance'
import { useGovernanceContext } from './GovernancePage'
import { Failure } from '../governance/feedback'
import { useCommand } from '../governance/useCommand'
import { InvitationState, MemberKind } from '../governance/codes'

const states: Record<string, string> = { PENDING: '待接受（期限内可用）', ACCEPTED: '已接受', REVOKED: '已撤销' }
/** 邀请记录只取服务端显式委派范围；没有配置不推断默认邀请权。 */
export default function GovernanceInvitationsPage() {
  const { partition, queryKey } = useGovernanceContext()
  const qc = useQueryClient()
  const [after, setAfter] = useState<string>()
  const [open, setOpen] = useState(false)
  const [selected, setSelected] = useState<Invitation>()
  const authority = useQuery({ queryKey: [...queryKey, 'invitation-authority'], queryFn: () => invitationAuthority(partition), retry: false })
  const result = useQuery({ queryKey: [...queryKey, 'invitations', after], queryFn: () => invitations(partition, after), enabled: !!authority.data, retry: false })
  const refresh = () => { void qc.invalidateQueries({ queryKey }) }
  if (authority.error) return isAxiosError(authority.error) && authority.error.response?.status === HttpStatusCode.Forbidden
    ? <Alert type="warning" showIcon message="当前没有外部邀请管理授权" description="邀请管理需要单独的受控委派，应用管理员不会自动取得此权限。" /> : <Failure error={authority.error} retry={refresh} />
  return <>
    <Card title="邀请记录" loading={authority.isPending} extra={<Space><Button onClick={refresh}>刷新邀请</Button><Button type="primary" disabled={!authority.data} onClick={() => setOpen(true)}>创建邀请</Button></Space>}>
      <Alert type="info" showIcon message="邀请只建立外部成员关系，不授予业务权限。已接受的邀请不能通过撤销邀请停用成员。" style={{ marginBottom: 16 }} />
      {result.error ? <Failure error={result.error} retry={refresh} /> : <Table<Invitation> loading={result.isPending} locale={{ emptyText: <GovernanceEmpty title="暂无外部邀请" description="为指定合作成员或访客创建有期限的邀请。" /> }} dataSource={result.data?.items} rowKey="id" pagination={false} scroll={{ x: 850 }} columns={[
        { title: '目标登录标识', dataIndex: 'target_subject' }, { title: '成员类型', dataIndex: 'member_kind' },
        { title: '邀请截止', render: (_, r) => new Date(r.expires_at).toLocaleString('zh-CN', { hour12: false }) }, { title: '成员截止', render: (_, r) => new Date(r.membership_valid_to).toLocaleString('zh-CN', { hour12: false }) },
        { title: '状态', render: (_, r) => r.state === InvitationState.PENDING && new Date(r.expires_at).getTime() <= Date.now() ? '已过期' : states[r.state] ?? r.state },
        { title: '操作', render: (_, r) => r.state === InvitationState.PENDING && <Button danger type="link" onClick={() => setSelected(r)}>撤销邀请</Button> },
      ]} />}
      <Space style={{ marginTop: 12 }}>{after && <Button onClick={() => setAfter(undefined)}>邀请首页</Button>}{result.data?.next_cursor && <Button onClick={() => setAfter(result.data!.next_cursor!)}>下一页邀请</Button>}</Space>
    </Card>
    {open && authority.data && <InvitationForm authority={authority.data} close={() => setOpen(false)} saved={refresh} />}
    {selected && <RevokeForm invitation={selected} close={() => setSelected(undefined)} saved={refresh} />}
  </>
}

function InvitationForm({ authority, close, saved }: { authority: InvitationAuthority; close: () => void; saved: () => void }) {
  const { partition } = useGovernanceContext()
  const [form] = Form.useForm()
  const [dirty, setDirty] = useState(false)
  const [proof, setProof] = useState<string>()
  const command = useCommand(issueInvitation)
  const cancel = () => {
    if (command.busy || command.unknown) return
    if ((dirty || command.result) && !command.result) Modal.confirm({ title: '放弃未提交的邀请？', okText: '放弃编辑', cancelText: '继续编辑', onOk: close })
    else if (command.result) Modal.confirm({ title: '已安全保存邀请证明？', content: '关闭后无法从列表重新取得证明。', okText: '已保存，关闭', cancelText: '返回复制', onOk: close })
    else close()
  }
  const finish = async (values: { target_subject: string; kind: string; invite_minutes: number; member_minutes: number; reason: string }) => {
    const response = await command.send(commandId => {
      const bytes = crypto.getRandomValues(new Uint8Array(32)); const token = btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
      setProof(token); const now = Date.now()
      return { ...partition, command_id: commandId, invitation_id: crypto.randomUUID(), target_subject: values.target_subject, member_kind: values.kind, token,
        expires_at: new Date(now + values.invite_minutes * 60000).toISOString(), membership_valid_to: new Date(now + values.member_minutes * 60000).toISOString(), reason: values.reason }
    })
    if (response) saved()
  }
  return <Drawer title="创建外部成员邀请" footer={!command.result && <Button type="primary" loading={command.busy} onClick={() => command.unknown ? void finish(form.getFieldsValue()) : form.submit()}>{command.unknown ? '重试原邀请' : '提交邀请'}</Button>} open onClose={cancel} maskClosable={false} width={600} extra={<Button disabled={command.busy || command.unknown} onClick={cancel}>关闭</Button>}>
    {!!command.error && <Failure error={command.error} />}
    {command.result ? <>
      <Alert type="success" message="邀请已创建，尚未建立业务授权" description="通过受控渠道把邀请编号与证明交给指定登录者。页面不会自动发送，也不会持久保存证明。" />
      <Typography.Paragraph style={{ marginTop: 20 }}>邀请编号：<Typography.Text copyable>{command.result.id}</Typography.Text></Typography.Paragraph>
      <Typography.Paragraph>一次性证明：<Typography.Text copyable style={{ overflowWrap: 'anywhere' }}>{proof}</Typography.Text></Typography.Paragraph>
      <Typography.Paragraph>接受入口：/invitations/accept（登录后输入编号与证明）</Typography.Paragraph>
    </> : <>
      <Typography.Paragraph>固定身份发行方：{authority.issuer}</Typography.Paragraph>
      {command.unknown && <Alert type="warning" message="结果尚未确认，原邀请编号和证明已保留，请原样重试。" />}
      <Form form={form} layout="vertical" initialValues={{ kind: MemberKind.PARTNER, invite_minutes: Math.min(30, Math.floor(authority.max_invitation_seconds / 60)), member_minutes: Math.min(1440, Math.floor(authority.max_membership_seconds / 60)) }} onValuesChange={() => setDirty(true)} onFinish={finish} disabled={command.busy || command.unknown}>
        <Form.Item name="target_subject" label="目标登录标识（精确 subject）" rules={[{ required: true, whitespace: true }, { max: 500 }]} extra="使用身份系统确认的稳定标识；不按姓名或邮箱猜测合并账号。"><Input /></Form.Item>
        <Form.Item name="kind" label="外部成员类型" rules={[{ required: true }]}><Select aria-label="外部成员类型" options={[{ value: MemberKind.PARTNER, label: '合作成员' }, { value: MemberKind.GUEST, label: '访客' }]} /></Form.Item>
        <Form.Item name="invite_minutes" label="邀请有效分钟数" rules={[{ required: true }, { type: 'number', min: 1, max: Math.floor(authority.max_invitation_seconds / 60) }]}><InputNumber precision={0} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="member_minutes" label="成员有效分钟数" dependencies={['invite_minutes']} rules={[{ required: true }, { type: 'number', max: Math.floor(authority.max_membership_seconds / 60) }, { validator: (_, value: number) => value > form.getFieldValue('invite_minutes') ? Promise.resolve() : Promise.reject(new Error('成员期限须晚于邀请截止时间')) }]}><InputNumber precision={0} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="reason" label="邀请原因" rules={[{ required: true, whitespace: true }, { max: 1000 }]}><Input.TextArea rows={3} /></Form.Item>

      </Form>

    </>}
  </Drawer>
}

function RevokeForm({ invitation, close, saved }: { invitation: Invitation; close: () => void; saved: () => void }) {
  const { partition } = useGovernanceContext()
  const [form] = Form.useForm()
  const command = useCommand(revokeInvitation)
  const finish = async (values: { reason: string }) => { const result = await command.send(id => ({ ...partition, id: invitation.id, command_id: id, expected_version: invitation.version, reason: values.reason })); if (result) { saved(); close() } }
  return <Modal title="撤销待接受邀请" open footer={null} onCancel={() => { if (!command.busy && !command.unknown) close() }} maskClosable={false}>
    {!!command.error && <Failure error={command.error} />}
    <Form form={form} layout="vertical" onFinish={finish} disabled={command.busy || command.unknown}>
      <Form.Item name="reason" label="撤销原因" rules={[{ required: true, whitespace: true }, { max: 1000 }]}><Input.TextArea /></Form.Item>
      <Button danger type="primary" htmlType="submit" loading={command.busy}>确认撤销邀请</Button>
    </Form>
    {command.unknown && <Button loading={command.busy} onClick={() => void finish(form.getFieldsValue())}>重试原撤销</Button>}
  </Modal>
}
