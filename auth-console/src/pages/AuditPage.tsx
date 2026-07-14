import { useQuery } from '@tanstack/react-query'
import { Button, Card, Table, Tag } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { PageHeader } from '../components/layout/PageHeader'
import { ErrorState } from '../components/common/AsyncState'
import { audit, type AuditRecord } from '../api/authz'

const actionColor = (a: string) => (a === 'grant' ? 'green' : a === 'revoke' ? 'red' : 'blue')

export default function AuditPage() {
  const q = useQuery({ queryKey: ['audit'], queryFn: () => audit(200) })

  return (
    <>
      <PageHeader
        title="审计日志"
        description="授予/撤销/同步操作记录(内存环形最近 500 条,重启清空;v2 可换 JDBC 持久化)"
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => q.refetch()} loading={q.isFetching}>
            刷新
          </Button>
        }
      />
      {q.isError ? (
        <ErrorState message="加载审计失败(需登录)" onRetry={() => q.refetch()} />
      ) : (
        <Card>
          <Table<AuditRecord>
            rowKey={(_, i) => String(i)}
            loading={q.isLoading}
            dataSource={q.data ?? []}
            size="small"
            scroll={{ x: 720 }}
            locale={{ emptyText: q.isLoading ? '加载中' : '暂无审计记录' }}
            pagination={{ pageSize: 20 }}
            columns={[
              { title: '时间', dataIndex: 'at', width: 230 },
              { title: '操作人', dataIndex: 'actor', width: 120 },
              { title: '动作', dataIndex: 'action', width: 90, render: (a: string) => <Tag color={actionColor(a)}>{a}</Tag> },
              { title: '关系元组', dataIndex: 'detail', render: (d: string) => <code className="mono" style={{ fontSize: 12 }}>{d}</code> },
            ]}
          />
        </Card>
      )}
    </>
  )
}
