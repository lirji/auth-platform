/** 当前授权工作区，供 axios 与 React Query 使用。 */
let activeWorkspaceId = 'knowledge'

export function setActiveWorkspace(id: string) {
  activeWorkspaceId = id || 'knowledge'
}

export function activeWorkspace(): string {
  return activeWorkspaceId
}
