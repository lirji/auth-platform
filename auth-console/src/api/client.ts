import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import type { User } from 'oidc-client-ts'
import { config } from '../config'
import { userManager } from '../auth/oidcConfig'

/** 空 baseURL → 相对路径 → dev vite proxy / prod nginx 同源反代到 admin:8201。 */
export const apiClient = axios.create({
  baseURL: config.adminBaseUrl,
  timeout: 15000,
})

// 请求拦截:附当前 Casdoor access_token(从 oidc userStore 取,保证不过期)。
apiClient.interceptors.request.use(async (cfg) => {
  const user = await userManager.getUser()
  if (user && !user.expired && user.access_token) {
    cfg.headers.Authorization = `Bearer ${user.access_token}`
  }
  return cfg
})

// 响应拦截:401 → 单飞静默续期(共享 in-flight promise 防惊群)重试一次;仍失败 → 交互式登录。
let refreshing: Promise<User | null> | null = null

apiClient.interceptors.response.use(
  (r) => r,
  async (error: AxiosError) => {
    const original = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined
    if (error.response?.status === 401 && original && !original._retried) {
      original._retried = true
      try {
        if (!refreshing) {
          refreshing = userManager.signinSilent().finally(() => {
            refreshing = null
          })
        }
        const user = await refreshing
        if (user?.access_token) {
          original.headers.Authorization = `Bearer ${user.access_token}`
          return apiClient(original)
        }
      } catch {
        // 续期失败,落到交互式登录
      }
      await userManager.signinRedirect({ state: { returnTo: window.location.pathname } })
    }
    return Promise.reject(error)
  },
)
