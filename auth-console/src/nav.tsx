import type { ReactNode } from 'react'
import {
  ApartmentOutlined,
  AuditOutlined,
  BugOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  SafetyCertificateOutlined,
  SyncOutlined,
} from '@ant-design/icons'

export interface NavItem {
  path: string
  label: string
  icon: ReactNode
  group: string
  feature?: string
}

/** 侧边菜单 + 路由的单一配置源(按职责分组;核心两页优先)。path 为工作区内相对路径。 */
export const NAV: NavItem[] = [
  { path: '/', label: '概览', icon: <DashboardOutlined />, group: '工作台' },
  { path: '/grants', label: '授予管理', icon: <SafetyCertificateOutlined />, group: '核心操作', feature: 'grants' },
  { path: '/playground', label: '权限调试器', icon: <BugOutlined />, group: '核心操作', feature: 'playground' },
  { path: '/schema', label: '授权模型', icon: <ApartmentOutlined />, group: '资源与模型', feature: 'schema' },
  { path: '/spaces', label: '空间/知识库', icon: <DatabaseOutlined />, group: '资源与模型', feature: 'spaces' },
  { path: '/sync', label: '身份同步', icon: <SyncOutlined />, group: '系统治理', feature: 'sync' },
  { path: '/audit', label: '审计日志', icon: <AuditOutlined />, group: '系统治理', feature: 'audit' },
]

export const NAV_GROUPS = ['工作台', '核心操作', '资源与模型', '系统治理']
