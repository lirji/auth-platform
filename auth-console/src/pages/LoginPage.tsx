import { useEffect } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { Button } from 'antd'
import { SafetyCertificateOutlined, SafetyOutlined } from '@ant-design/icons'
import { config } from '../config'
import { sanitizeReturnTo } from '../auth/returnTo'
import '../styles/login.css'

/** 门户正式入口：先落到本页，点击后再发起 Casdoor，不预选 tenant/clientId。 */
export default function LoginPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const returnTo = sanitizeReturnTo(params.get('returnTo') ?? params.get('redirect'))
  const configured = Boolean(config.casdoorClientId)
  const busy = auth.isLoading || Boolean(auth.activeNavigator)

  useEffect(() => {
    if (!auth.isLoading && auth.isAuthenticated) {
      navigate(returnTo, { replace: true })
    }
  }, [auth.isAuthenticated, auth.isLoading, navigate, returnTo])

  if (!configured) {
    return (
      <div className="login-root">
        <div className="login-alert" role="status">
          <h2>未配置统一身份应用</h2>
          <p>请先运行 deploy/auth-console-provision.sh，或在 .env.local 填写 VITE_CASDOOR_CLIENT_ID。</p>
        </div>
      </div>
    )
  }

  return (
    <div className="login-root">
      <main className="login-card">
        <section className="login-aside" aria-label="平台说明">
          <div className="login-brand">
            <span className="login-brand-mark" aria-hidden="true">
              <SafetyOutlined />
            </span>
            <span>
              <strong>权限管控台</strong>
              <small>AUTH PLATFORM</small>
            </span>
          </div>
          <div>
            <p className="login-kicker">身份与授权</p>
            <h1>统一身份，细粒度授权</h1>
            <p>用 Casdoor 确认你是谁，用 SpiceDB 判定能不能动这一条资源。</p>
            <ul className="login-features">
              <li>
                <span className="login-feature-icon" aria-hidden="true">①</span>
                <span>
                  <b>统一登录</b>
                  <small>授权码 + PKCE，不在页面保存 secret</small>
                </span>
              </li>
              <li>
                <span className="login-feature-icon" aria-hidden="true">②</span>
                <span>
                  <b>ReBAC 判权</b>
                  <small>关系元组 fail-closed，缺字段即拒绝</small>
                </span>
              </li>
              <li>
                <span className="login-feature-icon" aria-hidden="true">③</span>
                <span>
                  <b>授予与同步</b>
                  <small>组 / 部门差量对账，租户前缀防串权</small>
                </span>
              </li>
            </ul>
          </div>
        </section>

        <section className="login-form">
          <p className="login-form-kicker">统一登录</p>
          <h2>登录权限管控台</h2>
          <p className="login-form-lead">进入后由 Casdoor 校验组织与组成员。本页不预选租户，也不携带 clientId。</p>
          <ul className="login-meta">
            <li>
              <span>登录组织</span>
              <code>built-in</code>
            </li>
            <li>
              <span>所需组</span>
              <code>authz-admin</code>
            </li>
          </ul>
          <Button
            type="primary"
            block
            size="large"
            className="login-submit"
            icon={<SafetyCertificateOutlined />}
            loading={busy}
            onClick={() => void auth.signinRedirect({ state: { returnTo } })}
          >
            使用统一身份登录
          </Button>
          <p className="login-note">登录成功后回到来源页。关闭标签即清除会话。</p>
        </section>
      </main>
    </div>
  )
}
