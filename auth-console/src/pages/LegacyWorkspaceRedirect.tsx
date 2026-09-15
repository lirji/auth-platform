import { Navigate, useLocation } from 'react-router-dom'
import { Spin } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { listWorkspaces } from '../api/workspaces'

/** 兼容旧书签 /grants 等，转到当前身份默认工作区下的同名页。 */
export default function LegacyWorkspaceRedirect() {
  const location = useLocation()
  const q = useQuery({ queryKey: ['workspaces'], queryFn: listWorkspaces })
  if (q.isPending) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '40vh' }}>
        <Spin size="large" />
      </div>
    )
  }
  const ws = q.data?.[0]
  if (!ws) return <Navigate to="/" replace />
  const page = location.pathname === '/' ? ws.home : location.pathname.replace(/^\//, '')
  return <Navigate to={`/w/${ws.id}/${page}`} replace />
}
