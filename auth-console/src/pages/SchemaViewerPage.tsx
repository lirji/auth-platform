import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button, Card, Col, Row, Segmented } from 'antd'
import { apiClient } from '../api/client'
import { parseZed } from '../domain/zedParser'
import { PageHeader } from '../components/layout/PageHeader'
import { EmptyState, ErrorState, PageSkeleton } from '../components/common/AsyncState'
import { SchemaTypeCard } from '../components/domain/SchemaTypeCard'

export default function SchemaViewerPage() {
  const [view, setView] = useState<'cards' | 'raw'>('cards')
  const q = useQuery({
    queryKey: ['schema'],
    queryFn: () => apiClient.get<{ schema: string }>('/admin/schema').then((r) => r.data.schema),
  })
  const defs = useMemo(() => (q.data ? parseZed(q.data) : []), [q.data])

  return (
    <>
      <PageHeader
        title="授权模型"
        description="SpiceDB .zed schema:类型 → 关系(可授予)/ 权限(可判定)"
        extra={
          <Segmented
            value={view}
            onChange={(v) => setView(v as 'cards' | 'raw')}
            options={[
              { label: '类型卡片', value: 'cards' },
              { label: '原始 .zed', value: 'raw' },
            ]}
          />
        }
      />
      {q.isLoading ? (
        <PageSkeleton />
      ) : q.isError ? (
        <ErrorState message="需登录且 admin 已提供 GET /admin/schema" onRetry={() => q.refetch()} />
      ) : view === 'raw' ? (
        <Card>
          <pre className="mono" style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 13 }}>
            {q.data}
          </pre>
        </Card>
      ) : defs.length === 0 ? (
        <Card>
          <EmptyState
            description="未解析出类型定义(schema 可能为空,或语法超出解析器支持)"
            extra={<Button type="link" onClick={() => setView('raw')}>查看原始 .zed</Button>}
          />
        </Card>
      ) : (
        <Row gutter={[16, 16]}>
          {defs.map((d) => (
            <Col key={d.name} xs={24} md={12} xl={8}>
              <SchemaTypeCard def={d} />
            </Col>
          ))}
        </Row>
      )}
    </>
  )
}
