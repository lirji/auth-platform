import type { ReactNode } from 'react'
import { useEffect } from 'react'
import { useAuth } from 'react-oidc-context'
import { useLocation } from 'react-router-dom'
import { Button, Result, Spin } from 'antd'
import { canRead, useAuthStore } from '../store/authStore'

const centered: React.CSSProperties = {
  minHeight: '60vh',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
}

/** 路由守卫:未登录→跳 Casdoor;已登录但非 authz-viewer/admin→403。 */
export default function ProtectedRoute({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const location = useLocation()
  const authorities = useAuthStore((s) => s.authorities)

  useEffect(() => {
    if (!auth.isLoading && !auth.isAuthenticated && !auth.activeNavigator && !auth.error) {
      void auth.signinRedirect({ state: { returnTo: location.pathname } })
    }
  }, [auth.isLoading, auth.isAuthenticated, auth.activeNavigator, auth.error, location.pathname])

  if (auth.isLoading || auth.activeNavigator) {
    return (
      <div style={centered}>
        <Spin size="large" tip="加载中..." />
      </div>
    )
  }
  if (auth.error) {
    return (
      <Result
        status="error"
        title="登录失败"
        subTitle={auth.error.message}
        extra={<Button type="primary" onClick={() => void auth.signinRedirect()}>重试登录</Button>}
      />
    )
  }
  if (!auth.isAuthenticated) {
    return (
      <div style={centered}>
        <Spin size="large" tip="跳转登录..." />
      </div>
    )
  }
  if (!canRead(authorities)) {
    return (
      <Result
        status="403"
        title="无访问权限"
        subTitle="需要 Casdoor 组 authz-viewer 或 authz-admin"
        extra={<Button onClick={() => void auth.signoutRedirect()}>切换账号</Button>}
      />
    )
  }
  return <>{children}</>
}
