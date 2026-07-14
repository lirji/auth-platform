import { create } from 'zustand'

/** OIDC 会话在应用侧的镜像(供 UI/守卫同步读取;权威源是 oidc userStore)。 */
interface AuthState {
  status: 'loading' | 'authed' | 'anon'
  userId?: string
  username?: string
  authorities: string[]
  set: (p: Partial<AuthState>) => void
  clear: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  status: 'loading',
  authorities: [],
  set: (p) => set(p),
  clear: () => set({ status: 'anon', userId: undefined, username: undefined, authorities: [] }),
}))

export const isAdmin = (a: string[]): boolean => a.includes('authz-admin')
export const canRead = (a: string[]): boolean => a.includes('authz-admin') || a.includes('authz-viewer')
