import { useState } from 'react'
import { Alert, Button, Card, Space, Table, Tag, Typography } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { accessState, type Grant } from '../api/governance'
import { useGovernanceContext } from './GovernancePage'
import { Failure } from '../governance/feedback'

/** 过渡只读管理视图绑定已选择分区，写操作在P5-02按真实委派接入。 */
export default function GovernanceAccessPage() {
  const { partition, queryKey } = useGovernanceContext()
  const [cursors, setCursors] = useState<{ role?: string; grant?: string }>({})
  const result = useQuery({ queryKey: [...queryKey, 'access', cursors], queryFn: () => accessState(partition, cursors.role, cursors.grant), retry: false, staleTime: 0, gcTime: 0 })
  if (result.error) return <Failure error={result.error} retry={() => void result.refetch()} />
  return <Card title="授权管理" loading={result.isPending} extra={<Button onClick={() => void result.refetch()}>刷新状态</Button>}>
    <Alert type="info" showIcon message="图已确认不代表永久有效；实际业务操作仍检查成员、期限和数据范围。" style={{ marginBottom: 16 }} />
    <Typography.Title level={5}>固定角色版本</Typography.Title>
    <Table rowKey="id" dataSource={result.data?.roles} pagination={false} scroll={{ x: 600 }} columns={[
      { title: '角色', dataIndex: 'role_code' }, { title: '版本', dataIndex: 'version' },
      { title: '能力', dataIndex: 'capabilities', render: (caps: string[]) => caps.join('、') },
    ]} />
    {result.data?.next_role_cursor && <Button onClick={() => setCursors({ ...cursors, role: result.data!.next_role_cursor! })}>下一页角色</Button>}
    <Typography.Title level={5}>成员授权</Typography.Title>
    <Table<Grant> rowKey="id" dataSource={result.data?.grants} pagination={false} scroll={{ x: 900 }} columns={[
      { title: '成员', dataIndex: 'member_id', render: value => <Typography.Text className="mono">{value}</Typography.Text> },
      { title: '来源', dataIndex: 'source_type' }, { title: '范围', dataIndex: 'scope', render: value => value === 'TENANT_ALL' ? '当前企业全部资源' : '指定资源范围' },
      { title: '有效期', render: (_, row) => <Space direction="vertical" size={0}><span>{new Date(row.valid_from).toLocaleString()}</span><span>至 {new Date(row.valid_to).toLocaleString()}</span></Space> },
      { title: '投影状态', dataIndex: 'state', render: value => <Tag>{({ PENDING: '待生效', ACTIVE: '图已确认', REVOKED: '已撤销' } as Record<string, string>)[value] ?? '未知状态'}</Tag> },
    ]} />
    {result.data?.next_grant_cursor && <Button onClick={() => setCursors({ ...cursors, grant: result.data!.next_grant_cursor! })}>下一页授权</Button>}
  </Card>
}
