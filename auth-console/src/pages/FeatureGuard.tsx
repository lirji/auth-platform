import type { ReactNode } from 'react'
import { Navigate, useParams } from 'react-router-dom'
import { Spin } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { listWorkspaces } from '../api/workspaces'

/** 当前工作区未开放该功能时回到项目概览。 */
export default function FeatureGuard({ feature, children }: { feature: string; children: ReactNode }) {
  const { workspaceId = '' } = useParams()
  const q = useQuery({ queryKey: ['workspaces'], queryFn: listWorkspaces })
  const ws = q.data?.find((item) => item.id === workspaceId)

  if (q.isPending) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '30vh' }}>
        <Spin />
      </div>
    )
  }
  if (!ws?.features.includes(feature)) {
    return <Navigate to={`/w/${workspaceId}`} replace />
  }
  return children
}
