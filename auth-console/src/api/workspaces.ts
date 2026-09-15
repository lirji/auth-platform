import { apiClient } from './client'

export interface WorkspaceView {
  id: string
  name: string
  organization: string
  home: string
  features: string[]
  endpoint?: string
}

export const listWorkspaces = () =>
  apiClient.get<{ workspaces: WorkspaceView[] }>('/admin/workspaces').then((r) => r.data.workspaces)

export function workspaceHost(endpoint?: string): string {
  if (!endpoint) return ''
  try {
    return new URL(endpoint).host
  } catch {
    return endpoint
  }
}
