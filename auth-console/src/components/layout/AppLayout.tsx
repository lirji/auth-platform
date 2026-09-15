import { useState } from 'react'
import { Avatar, Breadcrumb, Button, Drawer, Dropdown, Grid, Layout, Menu, Select, Space } from 'antd'
import {
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MenuOutlined,
  SafetyOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate, useParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { NAV, NAV_GROUPS } from '../../nav'
import { listWorkspaces } from '../../api/workspaces'
import { useAuthStore } from '../../store/authStore'
import { colors } from '../../theme/colors'
import { setActiveWorkspace } from '../../workspace/session'

export default function AppLayout() {
  const location = useLocation()
  const navigate = useNavigate()
  const { workspaceId = '' } = useParams()
  const auth = useAuth()
  const username = useAuthStore((s) => s.username)
  const qc = useQueryClient()
  const workspaces = useQuery({ queryKey: ['workspaces'], queryFn: listWorkspaces })
  const currentWorkspace = workspaces.data?.find((item) => item.id === workspaceId)
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.lg
  const [collapsed, setCollapsed] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const pagePath = location.pathname.replace(/^\/w\/[^/]+/, '') || '/'
  const visibleNav = NAV.filter((item) => !item.feature || currentWorkspace?.features.includes(item.feature))
  const current = visibleNav.find((n) => n.path === pagePath)

  const menuItems = NAV_GROUPS.map((g) => ({
    type: 'group' as const,
    key: g,
    label: g,
    children: visibleNav.filter((n) => n.group === g).map((n) => ({ key: n.path, icon: n.icon, label: n.label })),
  })).filter((g) => (g.children?.length ?? 0) > 0)

  const go = (path: string) => {
    const suffix = path === '/' ? '' : path
    navigate(`/w/${workspaceId}${suffix}`)
  }

  const menu = (afterClick?: () => void) => (
    <Menu
      mode="inline"
      selectedKeys={[pagePath]}
      items={menuItems}
      onClick={(e) => {
        go(e.key)
        afterClick?.()
      }}
      style={{ borderInlineEnd: 0 }}
    />
  )

  const brand = (
    <div className="brand">
      <SafetyOutlined style={{ color: colors.primary }} />
      {!collapsed && '权限管控台'}
    </div>
  )

  const switchWorkspace = (id: string) => {
    const next = workspaces.data?.find((item) => item.id === id)
    setActiveWorkspace(id)
    void qc.removeQueries({ predicate: (q) => q.queryKey[0] === 'ws' })
    navigate(`/w/${id}/${next?.home ?? 'grants'}`)
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {!isMobile && (
        <Layout.Sider
          theme="light"
          width={224}
          collapsedWidth={72}
          collapsible
          collapsed={collapsed}
          trigger={null}
          style={{ borderInlineEnd: `1px solid ${colors.border}` }}
        >
          {brand}
          {menu()}
        </Layout.Sider>
      )}

      <Layout>
        <Layout.Header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: `1px solid ${colors.border}` }}>
          <Space>
            <Button
              type="text"
              aria-label={isMobile ? '打开菜单' : collapsed ? '展开菜单' : '收起菜单'}
              icon={isMobile ? <MenuOutlined /> : collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => (isMobile ? setDrawerOpen(true) : setCollapsed(!collapsed))}
            />
            <Breadcrumb items={[{ title: currentWorkspace?.name ?? '工作区' }, { title: current?.label ?? '' }]} />
          </Space>
          <Space>
            {(workspaces.data?.length ?? 0) > 1 && (
              <Select
                aria-label="切换授权工作区"
                value={workspaceId}
                style={{ minWidth: 180 }}
                options={workspaces.data?.map((item) => ({ value: item.id, label: item.name }))}
                onChange={switchWorkspace}
              />
            )}
            <Dropdown
              menu={{
                items: [
                  { key: 'home', label: '全部工作区', onClick: () => navigate('/') },
                  { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' },
                ],
                onClick: ({ key }) => {
                  if (key === 'logout') void auth.signoutRedirect()
                },
              }}
            >
              <Button type="text" style={{ height: 'auto', paddingBlock: 4 }}>
                <Space>
                  <Avatar size="small" icon={<UserOutlined />} />
                  {username ?? '未登录'}
                </Space>
              </Button>
            </Dropdown>
          </Space>
        </Layout.Header>
        <Layout.Content>
          <div className="app-content">
            <Outlet />
          </div>
        </Layout.Content>
      </Layout>

      <Drawer
        placement="left"
        width={224}
        open={isMobile && drawerOpen}
        onClose={() => setDrawerOpen(false)}
        styles={{ body: { padding: 0 } }}
        title={
          <Space>
            <SafetyOutlined style={{ color: colors.primary }} />
            权限管控台
          </Space>
        }
      >
        {menu(() => setDrawerOpen(false))}
      </Drawer>
    </Layout>
  )
}
