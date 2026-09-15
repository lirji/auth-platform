import { useEffect } from 'react'
import { useAuth } from 'react-oidc-context'
import { useNavigate } from 'react-router-dom'
import { Button, Result, Spin } from 'antd'
import { sanitizeReturnTo } from '../auth/returnTo'
import '../styles/login.css'

/** OIDC 回调页:react-oidc-context 自动用 code 换 token,完成后跳回原深链。 */
export default function CallbackPage() {
  const auth = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    if (!auth.isLoading && auth.isAuthenticated) {
      const state = auth.user?.state as { returnTo?: string } | undefined
      navigate(sanitizeReturnTo(state?.returnTo), { replace: true })
    }
  }, [auth.isLoading, auth.isAuthenticated, auth.user, navigate])

  if (auth.error) {
    return (
      <div className="login-root">
        <Result
          status="error"
          title="登录回调失败"
          subTitle={auth.error.message}
          extra={<Button type="primary" onClick={() => void auth.signinRedirect()}>重试登录</Button>}
        />
      </div>
    )
  }
  return (
    <div className="login-root">
      <div className="login-callback">
        <Spin size="large" />
        <span>正在完成登录…</span>
      </div>
    </div>
  )
}
