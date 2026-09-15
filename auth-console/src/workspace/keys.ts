/** React Query 键带上工作区，切换项目时不会串到另一套 SpiceDB 的缓存。 */
export function wsQueryKey(workspaceId: string, ...parts: unknown[]) {
  return ['ws', workspaceId, ...parts] as const
}
