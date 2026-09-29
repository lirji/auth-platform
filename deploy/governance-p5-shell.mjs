/** 真实API/身份会话下的P5-01 UI检查；本片注入真实PKCE Token，完整交互登录属于P5-07。 */
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { chromium, expect } = require(process.env.P5_PLAYWRIGHT_MODULE)
const root = process.env.P5_UI_FIXTURE
const fixture = JSON.parse(fs.readFileSync(path.join(root, 'fixture.json'), 'utf8'))
const origin = 'http://127.0.0.1:15275'
const browser = await chromium.launch({ headless: true })
const HTTP_FORBIDDEN = 403
const checks = []
const passed = check => checks.push({ check, result: 'PASS' })
async function session(token) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const profile = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
  const value = JSON.stringify({ access_token: token, token_type: 'Bearer', scope: 'openid', profile, expires_at: profile.exp })
  await context.addInitScript(({ key, value }) => sessionStorage.setItem(key, value), {
    key: `oidc.user:${process.env.VITE_CASDOOR_AUTHORITY}:${process.env.VITE_CASDOOR_CLIENT_ID}`, value,
  })
  return context
}
const route = tenant => `${origin}/governance?tenant=${tenant}`
try {
  const context = await session(fixture.member_token)
  const a = await context.newPage(), b = await context.newPage()
  await a.goto(route(fixture.tenants[0])); await b.goto(route(fixture.tenants[1]))
  await expect(a.getByRole('heading', { name: '我的工作台', exact: true })).toBeVisible()
  await expect(async () => {
    await a.getByRole('button', { name: '刷新应用', exact: true }).click()
    await expect(a.getByRole('link', { name: '进入 products', exact: true })).toBeVisible()
  }).toPass({ timeout: 15000 })
  await expect(b.getByRole('link', { name: '进入 products', exact: true })).toBeVisible()
  await expect(a.getByRole('button', { name: '查看授权管理', exact: true })).toHaveCount(0)
  await expect(a.getByRole('link', { name: '进入 products', exact: true })).toHaveAttribute('href', 'http://127.0.0.1:18605/collaboration/products')
  passed('PARTNER sees own real application; business entry contains no token; management action absent')
  const requested = []
  a.on('request', request => { if (request.url().includes('/me/applications')) requested.push(new URL(request.url()).searchParams.get('tenant_id')) })
  await a.getByRole('combobox', { name: '当前组织' }).click()
  await a.getByTitle(new RegExp('p5-enterprise-1-')).click()
  await expect(a).toHaveURL(route(fixture.tenants[1]))
  await expect(a.getByRole('link', { name: '进入 products', exact: true })).toBeVisible()
  await expect(b).toHaveURL(route(fixture.tenants[1]))
  await b.goto(route(fixture.tenants[0]))
  await a.getByRole('button', { name: '刷新应用' }).click()
  await expect.poll(() => requested.at(-1)).toBe(fixture.tenants[1])
  passed('two tabs retain independent tenant parameters; organization switch clears previous application')
  await a.screenshot({ path: path.join(root, 'shell-member-1440.png'), fullPage: true })
  await a.setViewportSize({ width: 390, height: 844 })
  await a.screenshot({ path: path.join(root, 'shell-member-390.png'), fullPage: true })
  if (await a.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)) throw new Error('narrow viewport overflow')
  passed('narrow viewport has no horizontal page overflow')
  const denied = await a.request.get(`${origin}/api/governance/v1/access/state?tenant_id=${fixture.tenants[0]}&application_id=commerce&environment=test`, { headers: { Authorization: `Bearer ${fixture.member_token}` } })
  if (denied.status() !== HTTP_FORBIDDEN) throw new Error('direct management request must be forbidden')
  passed('direct API bypass of hidden management action is forbidden')
  const manager = await session(fixture.manager_token), m = await manager.newPage()
  await m.goto(route(fixture.tenants[0]))
  await expect(m.getByRole('button', { name: '查看授权管理', exact: true })).toBeVisible()
  await expect(m.getByRole('link', { name: '进入 products', exact: true })).toHaveCount(0)
  await m.getByRole('button', { name: '查看授权管理', exact: true }).click()
  await expect(m.getByText('固定角色版本', { exact: true })).toBeVisible()
  await expect(m.getByText('product-reader', { exact: true })).toBeVisible()
  await m.screenshot({ path: path.join(root, 'shell-management-1440.png'), fullPage: true })
  passed('manager can open protected current-partition view without acquiring business access')
  fs.writeFileSync(path.join(root, 'ui-result.json'), JSON.stringify(checks, null, 2))
} finally { await browser.close() }
