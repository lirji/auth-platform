import { Card, Col, Result, Row, Spin, Typography } from 'antd'
import { BugOutlined, DatabaseOutlined, RightOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { PageHeader } from '../components/layout/PageHeader'
import { listWorkspaces, workspaceHost } from '../api/workspaces'
import { colors } from '../theme/colors'
import { useAuthStore } from '../store/authStore'

/** 某个项目工作区内的概览：只展示该项目开放的授权入口。 */
export default function WorkspaceOverview() {
  const { workspaceId = '' } = useParams()
  const navigate = useNavigate()
  const username = useAuthStore((s) => s.username)
  const q = useQuery({ queryKey: ['workspaces'], queryFn: listWorkspaces })
  const ws = q.data?.find((item) => item.id === workspaceId)

  if (q.isPending) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '30vh' }}>
        <Spin />
      </div>
    )
  }
  if (!ws) {
    return <Result status="403" title="无权进入该项目工作区" />
  }

  const cards = [
    { feature: 'spaces', title: '空间/知识库', desc: '管理本项目空间成员与公开链接', to: 'spaces', icon: <DatabaseOutlined /> },
    { feature: 'grants', title: '授予管理', desc: '授予/撤销本项目关系元组', to: 'grants', icon: <SafetyCertificateOutlined /> },
    { feature: 'playground', title: '权限调试器', desc: '对本项目 SpiceDB 做 check / 反查', to: 'playground', icon: <BugOutlined /> },
  ].filter((card) => ws.features.includes(card.feature))

  return (
    <>
      <PageHeader
        title={ws.name}
        description={`组织 ${ws.organization} · SpiceDB ${workspaceHost(ws.endpoint) || '默认'} · 登录身份 ${username ?? '-'}`}
      />
      <Row gutter={[16, 16]}>
        {cards.map((card) => (
          <Col xs={24} md={12} key={card.feature}>
            <Card
              hoverable
              role="button"
              tabIndex={0}
              onClick={() => navigate(`/w/${ws.id}/${card.to}`)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  navigate(`/w/${ws.id}/${card.to}`)
                }
              }}
              styles={{ body: { display: 'flex', alignItems: 'center', gap: 16 } }}
            >
              <div style={{ fontSize: 26, color: colors.primary, lineHeight: 1 }}>{card.icon}</div>
              <div style={{ flex: 1 }}>
                <Typography.Title level={5} style={{ margin: 0 }}>
                  {card.title}
                </Typography.Title>
                <Typography.Text type="secondary">{card.desc}</Typography.Text>
              </div>
              <RightOutlined style={{ color: colors.textTertiary }} />
            </Card>
          </Col>
        ))}
      </Row>
    </>
  )
}
