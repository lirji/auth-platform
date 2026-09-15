import { Result, Spin } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import AppLayout from '../components/layout/AppLayout'
import { listWorkspaces } from '../api/workspaces'
import { setActiveWorkspace } from '../workspace/session'

/** 校验工作区可见性，并在子页发请求前同步当前项目 id 到请求头。 */
export default function WorkspaceShell() {
  const { workspaceId = '' } = useParams()
  const q = useQuery({ queryKey: ['workspaces'], queryFn: listWorkspaces })
  const allowed = q.data?.some((item) => item.id === workspaceId)

  if (workspaceId) setActiveWorkspace(workspaceId)

  if (q.isPending) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '40vh' }}>
        <Spin size="large" />
      </div>
    )
  }
  if (!allowed) {
    return <Result status="403" title="无权进入该项目工作区" extra={<a href="/">返回选择</a>} />
  }
  return <AppLayout />
}
