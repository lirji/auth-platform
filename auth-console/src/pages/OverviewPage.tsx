import type { KeyboardEvent, ReactNode } from 'react'
import { Alert, Card, Col, Descriptions, Row, Tag, Typography } from 'antd'
import { BugOutlined, RightOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { PageHeader } from '../components/layout/PageHeader'
import { isAdmin, useAuthStore } from '../store/authStore'
import { colors } from '../theme/colors'

function TaskCard({ icon, title, desc, to }: { icon: ReactNode; title: string; desc: string; to: string }) {
  const nav = useNavigate()
  const go = () => nav(to)
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      go()
    }
  }
  return (
    <Card
      hoverable
      role="button"
      tabIndex={0}
      onClick={go}
      onKeyDown={onKey}
      styles={{ body: { display: 'flex', alignItems: 'center', gap: 16 } }}
    >
      <div style={{ fontSize: 26, color: colors.primary, lineHeight: 1 }}>{icon}</div>
      <div style={{ flex: 1 }}>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {title}
        </Typography.Title>
        <Typography.Text type="secondary">{desc}</Typography.Text>
      </div>
      <RightOutlined style={{ color: colors.textTertiary }} />
    </Card>
  )
}

export default function OverviewPage() {
  const { userId, username, authorities } = useAuthStore()

  return (
    <>
      <PageHeader title="概览" description="授权侧管控台 · 身份/组织/用户由 Casdoor 自带控制台管理" />
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <TaskCard icon={<SafetyCertificateOutlined />} title="授予管理" desc="授予/撤销关系元组,查看资源现存授予" to="/grants" />
        </Col>
        <Col xs={24} md={12}>
          <TaskCard icon={<BugOutlined />} title="权限调试器" desc="实时判定、反查、判定路径展开" to="/playground" />
        </Col>
      </Row>

      <Card title="当前身份" style={{ marginTop: 16 }}>
        <Descriptions
          column={{ xs: 1, md: 3 }}
          items={[
            { key: 'u', label: '用户', children: username ?? '-' },
            { key: 'sub', label: '用户 id (sub)', children: <code className="mono">{userId ?? '-'}</code> },
            {
              key: 'g',
              label: '授权组',
              children: authorities.length
                ? authorities.map((a) => (
                    <Tag key={a} color={a === 'authz-admin' ? 'volcano' : 'blue'}>
                      {a}
                    </Tag>
                  ))
                : '-',
            },
          ]}
        />
        {!isAdmin(authorities) && (
          <Alert style={{ marginTop: 12 }} type="info" showIcon message="你是 authz-viewer(只读):可判权/反查/看模型,授予/撤销/同步需 authz-admin。" />
        )}
      </Card>

      <Alert style={{ marginTop: 16 }} type="warning" showIcon message="规模统计(对象/元组数)暂无 —— SpiceDB 无 count-all,后端待补聚合端点。" />
    </>
  )
}
