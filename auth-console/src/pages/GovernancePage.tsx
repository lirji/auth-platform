import { useRef, useState } from 'react'
import { Alert, Button, Card, Empty, Form, Input, Space, Table, Tag, Typography } from 'antd'
import type { MenuProps } from 'antd'
import { Menu } from 'antd'
import { useAuth } from 'react-oidc-context'
import { isAxiosError, HttpStatusCode } from 'axios'
import { accessState, presentation, type AccessState, type Partition, type Presentation, type Grant } from '../api/governance'
import { PageHeader } from '../components/layout/PageHeader'

const stateLabels = { PENDING: '待生效', ACTIVE: '图已确认', REVOKED: '已撤销' }
const stateColors = { PENDING: 'warning', ACTIVE: 'success', REVOKED: 'default' }
function failure(error: unknown) {
  const status = isAxiosError(error) ? error.response?.status : undefined
  if (status === HttpStatusCode.Forbidden) return '无权查看此范围，请核对当前企业成员关系或管理委派。'
  if (status === HttpStatusCode.Conflict) return '当前状态已变化，请重新查询。'
  if (status === HttpStatusCode.NotFound) return '当前环境尚未开启此功能。'
  if (status === HttpStatusCode.BadRequest) return '查询条件无效，请检查企业、应用和环境。'
  return '暂时无法确认授权状态，请稍后重试。'
}

/** P2最小只读工作台；未授权菜单隐藏，失败或切换范围时立即清除旧结果。 */
export default function GovernancePage() {
  const auth = useAuth()
  const [form] = Form.useForm<Partition>()
  const [partition, setPartition] = useState<Partition>()
  const [view, setView] = useState<Presentation>()
  const [state, setState] = useState<AccessState>()
  const [error, setError] = useState<string>()
  const [managementError, setManagementError] = useState<string>()
  const [loading, setLoading] = useState(false)
  const [managementLoading, setManagementLoading] = useState(false)
  const [cursors, setCursors] = useState<{ role?: string; grant?: string }>({})
  // 请求代次阻止旧范围的慢响应覆盖新选择；不把过期允许内容留在屏幕上。
  const revision = useRef(0)
  const invalidate = () => {
    revision.current++; setPartition(undefined); setView(undefined); setState(undefined)
    setError(undefined); setManagementError(undefined); setLoading(false); setManagementLoading(false); setCursors({})
  }
  const load = async (values: Partition) => {
    invalidate(); const current = revision.current; setLoading(true); setPartition(values)
    try { const result = await presentation(values); if (current === revision.current) setView(result) }
    catch (err) { if (current === revision.current) setError(failure(err)) }
    finally { if (current === revision.current) setLoading(false) }
  }
  const management = async (next: typeof cursors = {}) => {
    if (!partition) return
    const current = revision.current; setState(undefined); setManagementError(undefined); setManagementLoading(true)
    try { const result = await accessState(partition, next.role, next.grant)
      if (current === revision.current) { setState(result); setCursors(next) }
    } catch (err) { if (current === revision.current) setManagementError(failure(err)) }
    finally { if (current === revision.current) setManagementLoading(false) }
  }
  const menus = (parent: string | null): MenuProps['items'] => view?.menus.filter(item => item.parent === parent).map(item => {
    const children = menus(item.code)
    return { key: item.code, label: item.href ? <a href={item.href} target="_blank" rel="noopener noreferrer">{item.code}</a> : item.code,
      children: children?.length ? children : undefined, disabled: !item.href && !children?.length }
  })
  return <main className="app-content" style={{ maxWidth: 1320, margin: '0 auto' }}>
    <PageHeader title="企业应用权限" description="查询当前企业和应用下的可见入口与授权状态。"
      extra={<Button onClick={() => void auth.signoutRedirect()}>退出登录</Button>} />
    <Card title="查询范围" style={{ marginBottom: 20 }}>
      <Form form={form} layout="vertical" onFinish={values => void load(values)} onValuesChange={invalidate}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16 }}>
          <Form.Item name="tenant_id" label="企业标识" rules={[{ required: true, message: '请输入企业标识' }, { pattern: /^[0-9a-f-]{36}$/, message: '请输入有效的企业标识' }]}><Input placeholder="企业 UUID" /></Form.Item>
          <Form.Item name="application_id" label="应用编码" rules={[{ required: true, message: '请输入应用编码' }]}><Input placeholder="已登记的应用编码" /></Form.Item>
          <Form.Item name="environment" label="环境" rules={[{ required: true, message: '请输入环境' }]}><Input placeholder="已启用的环境" /></Form.Item>
        </div>
        <Space wrap><Button type="primary" htmlType="submit" loading={loading}>查询我的应用</Button>
          <Button disabled={!partition || loading} loading={managementLoading} onClick={() => void management()}>查看管理授权状态</Button></Space>
      </Form>
    </Card>
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
    <Card title="我的可见入口" loading={loading} style={{ marginBottom: 20 }}>
      {!view ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择范围并查询" /> : <>
        {view.menus.length ? <Menu mode="inline" items={menus(null)} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有可见入口" />}
        <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>入口仅用于导航，实际操作会重新校验权限。</Typography.Paragraph>
        <Space wrap>{view.capability_hints.map(code => <Tag key={code}>{code}</Tag>)}</Space>
      </>}
    </Card>
    {managementError && <Alert type="error" showIcon message={managementError} style={{ marginBottom: 16 }} />}
    {(state || managementLoading) && <Card title="管理授权状态" loading={managementLoading}>
      <Alert type="info" showIcon message="图已确认表示授权已完成投影；当前有效期、成员状态和业务检查仍决定是否允许访问。" style={{ marginBottom: 16 }} />
      <Typography.Title level={5}>固定角色版本</Typography.Title>
      <Table rowKey="id" dataSource={state?.roles} pagination={false} scroll={{ x: 660 }} columns={[
        { title: '角色', dataIndex: 'role_code' }, { title: '版本', dataIndex: 'version' },
        { title: '能力', dataIndex: 'capabilities', render: (caps: string[]) => caps.join('、') },
      ]} />
      <Button style={{ marginTop: 12 }} disabled={!state?.next_role_cursor} onClick={() => void management({ ...cursors, role: state?.next_role_cursor ?? undefined })}>下一页角色</Button>
      <Typography.Title level={5}>成员授权</Typography.Title>
      <Table<Grant> rowKey="id" dataSource={state?.grants} pagination={false} scroll={{ x: 1100 }} columns={[
        { title: '成员 / 代际', render: (_, grant) => <span>{grant.member_id}<br />第 {grant.member_generation} 代</span> },
        { title: '来源', dataIndex: 'source_id' }, { title: '范围', render: () => '当前企业全部资源' },
        { title: '有效期', render: (_, grant) => <span>{grant.valid_from}<br />至 {grant.valid_to}</span> },
        { title: '状态', dataIndex: 'state', render: (value: Grant['state']) => <Tag color={stateColors[value]}>{stateLabels[value] ?? '未知状态'}</Tag> },
      ]} />
      <Button style={{ marginTop: 12 }} disabled={!state?.next_grant_cursor} onClick={() => void management({ ...cursors, grant: state?.next_grant_cursor ?? undefined })}>下一页授权</Button>
    </Card>}
  </main>
}
