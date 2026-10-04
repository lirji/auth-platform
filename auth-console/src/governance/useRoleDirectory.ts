import { useQuery } from '@tanstack/react-query'
import { accessState } from '../api/governance'
import { useGovernanceContext } from '../pages/GovernancePage'
import { collectRoles } from './catalogModel'

/** 关联查询和目录查询采用同一主体/代际/分区，不将第一页冒充完整角色集合。 */
export function useRoleDirectory(enabled = true) {
  const { partition, queryKey } = useGovernanceContext()
  return useQuery({ queryKey: [...queryKey, 'role-directory'],
    queryFn: ({ signal }) => collectRoles(cursor => accessState(partition, cursor, undefined, signal), signal),
    enabled, retry: false, staleTime: 0, gcTime: 0 })
}
