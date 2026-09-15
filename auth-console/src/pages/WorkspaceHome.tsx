import { useEffect } from 'react'
import { Button, Card, Col, Result, Row, Spin, Typography } from 'antd'
import { RightOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { PageHeader } from '../components/layout/PageHeader'
import { listWorkspaces, workspaceHost } from '../api/workspaces'
import { colors } from '../theme/colors'
import { useAuthStore } from '../store/authStore'

/** 登录后的项目工作区入口：仅一个则直进落地页，多个则选择。 */
export default function WorkspaceHome() {
  const navigate = useNavigate()
  const auth = useAuth()
  const owner = useAuthStore((s) => s.owner)
  const username = useAuthStore((s) => s.username)
  const q = useQuery({ queryKey: ['workspaces'], queryFn: listWorkspaces })

  useEffect(() => {
    if (q.data?.length === 1) {
      const ws = q.data[0]
      navigate(`/w/${ws.id}/${ws.home}`, { replace: true })
    }
  }, [q.data, navigate])

  if (q.isPending) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '40vh' }}>
        <Spin size="large" />
      </div>
    )
  }
  if (q.isError) {
    return <Result status="error" title="无法读取授权工作区" subTitle="确认 admin 已启动且当前账号属于 authz-viewer / authz-admin。" />
  }
  if (!q.data?.length) {
    return <Result status="403" title="没有可进入的项目工作区" subTitle={`当前组织 ${owner || '未知'} 未映射到任何授权实例。`} />
  }
  if (q.data.length === 1) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '40vh' }}>
        <Spin size="large" />
      </div>
    )
  }

  return (
    <div className="app-content">
      <PageHeader
        title="选择授权工作区"
        description={`${username ?? '已登录'} · 每个项目连自己的 SpiceDB，授权页互不串实例。`}
        extra={<Button onClick={() => void auth.signoutRedirect()}>退出登录</Button>}
      />
      <Row gutter={[16, 16]}>
        {q.data.map((ws) => (
          <Col xs={24} md={12} key={ws.id}>
            <Card
              hoverable
              role="button"
              tabIndex={0}
              onClick={() => navigate(`/w/${ws.id}/${ws.home}`)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  navigate(`/w/${ws.id}/${ws.home}`)
                }
              }}
              styles={{ body: { display: 'flex', alignItems: 'center', gap: 16 } }}
            >
              <div style={{ flex: 1 }}>
                <Typography.Title level={5} style={{ margin: 0 }}>
                  {ws.name}
                </Typography.Title>
                <Typography.Text type="secondary">
                  组织 {ws.organization} · SpiceDB {workspaceHost(ws.endpoint) || '默认'} · 进入 /{ws.home}
                </Typography.Text>
              </div>
              <RightOutlined style={{ color: colors.textTertiary }} />
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  )
}
