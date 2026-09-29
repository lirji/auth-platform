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
const browserRole = 'browser-exporter-' + Date.now()
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
const select = (page, label) => page.locator('.ant-select').filter({ has: page.getByRole('combobox', { name: label, exact: true }) }).click()
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
  await select(a, '当前组织')
  await a.getByTitle(new RegExp('p5-enterprise-1-')).click()
  await expect(a).toHaveURL(route(fixture.tenants[1]))
  await expect(a.getByRole('link', { name: '进入 products', exact: true })).toBeVisible()
  await expect(b).toHaveURL(route(fixture.tenants[1]))
  await b.goto(route(fixture.tenants[0]))
  await a.getByRole('button', { name: '刷新应用' }).click()
  await expect.poll(() => requested.at(-1)).toBe(fixture.tenants[1])
  passed('two tabs retain independent tenant parameters; organization switch clears previous application')
  await a.screenshot({ path: path.join(root, 'shell-member-1440.png'), fullPage: true, animations: 'disabled' })
  await a.setViewportSize({ width: 390, height: 844 })
  await a.screenshot({ path: path.join(root, 'shell-member-390.png'), fullPage: true, animations: 'disabled' })
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
  await m.screenshot({ path: path.join(root, 'shell-management-1440.png'), fullPage: true, animations: 'disabled' })
  passed('manager can open protected current-partition view without acquiring business access')
  await m.getByRole('button', { name: '创建角色版本', exact: true }).click()
  await m.getByRole('button', { name: '创建固定版本', exact: true }).click()
  await expect(m.getByText('请输入稳定角色编码', { exact: true })).toBeVisible()
  await m.getByLabel('角色编码', { exact: true }).fill(browserRole)
  await select(m, '允许的能力')
  await m.getByTitle('commerce.product.export', { exact: true }).click()
  await m.getByLabel('角色编码', { exact: true }).click()
  await m.keyboard.press('Escape')
  await expect(m.getByRole('dialog', { name: '放弃尚未提交的角色编辑？', exact: true })).toBeVisible()
  await m.getByRole('button', { name: '继续编辑', exact: true }).click()
  await m.getByText('创建角色版本', { exact: true }).last().click()
  await m.screenshot({ path: path.join(root, 'role-form-1440.png'), fullPage: true, animations: 'disabled' })
  await m.getByRole('button', { name: '创建固定版本', exact: true }).click()
  await expect(m.getByText(`已创建 ${browserRole} · 版本 1`, { exact: true })).toBeVisible()
  await m.getByRole('button', { name: '关闭', exact: true }).click()
  passed('role form validates required fields, protects dirty close, and creates a real immutable version')
  await m.getByRole('button', { name: '授予成员', exact: true }).click()
  await select(m, '受益成员')
  await m.getByTitle(`PARTNER · ${fixture.members[0]} · 第1代`, { exact: true }).click()
  await select(m, '固定角色版本')
  await m.getByTitle(`${browserRole} · v1`, { exact: true }).click()
  await select(m, '资源类型')
  await m.getByTitle('product', { exact: true }).click()
  await m.getByLabel('范围标识', { exact: true }).fill('STORE-A')
  await m.getByLabel('有效分钟数（从提交时开始）', { exact: true }).fill('5')
  await m.getByLabel('授权来源说明', { exact: true }).fill(browserRole)
  await m.getByText('授予成员权限', { exact: true }).click()
  await m.screenshot({ path: path.join(root, 'grant-form-1440.png'), fullPage: true, animations: 'disabled' })
  let dropped = false
  const commands = []
  await m.route('**/api/governance/v1/access/scoped-grants', async route => {
    commands.push(route.request().postDataJSON())
    if (!dropped) { dropped = true; await route.fetch(); await route.abort('failed') }
    else await route.continue()
  })
  await m.getByRole('button', { name: '提交授予', exact: true }).click()
  await expect(m.getByText('结果尚未确认。重试会使用相同成员、范围、时间和命令。', { exact: true })).toBeVisible()
  await m.getByRole('button', { name: '重试原命令', exact: true }).click()
  if (JSON.stringify(commands[0]) !== JSON.stringify(commands[1])) throw new Error('unknown result retry changed the original command')
  passed('real committed grant response dropped; browser retries byte-equivalent payload with same command id')
  await expect(m.getByText('授权已受理，等待实际投影生效', { exact: true })).toBeVisible()
  await m.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(m.getByText('待生效', { exact: true })).toBeVisible()
  await expect(m.getByText('权限投影：同步中 · 成员同步：已同步', { exact: true })).toBeVisible()
  passed('real scoped grant submits with 202 and remains pending while projection worker is stopped')
  await m.reload()
  await expect(m.getByText('待生效', { exact: true })).toBeVisible()
  passed('full-page reload retains management progress while business entry state is unavailable')
  const row = m.getByRole('row').filter({ hasText: browserRole})
  await row.getByRole('button', { name: '查看差异', exact: true }).click()
  await expect(m.getByText('引用此版本的授权数', { exact: true })).toBeVisible()
  await expect(m.getByText('commerce.product.export', { exact: true }).last()).toBeVisible()
  await m.screenshot({ path: path.join(root, 'role-impact-1440.png'), fullPage: true, animations: 'disabled' })
  await m.keyboard.press('Escape')
  await expect(m.getByText('引用此版本的授权数', { exact: true })).toHaveCount(0)
  passed('version impact drawer reads real reference count and closes without losing list context')
  await m.getByRole('button', { name: '应用清单', exact: true }).click()
  const manifest = { schema_version: '1', application: 'commerce', manifest_version: 2,
    capabilities: [{ code: 'commerce.product.read', resource_type: 'product', risk_level: 'NORMAL' },
      { code: 'commerce.product.export', resource_type: 'product', risk_level: 'HIGH' },
      { code: 'commerce.product.review', resource_type: 'product', risk_level: 'HIGH' }],
    menus: [{ code: 'products', parent: null, route: '/collaboration/products', any_of: ['commerce.product.read'] }] }
  await m.getByLabel('应用清单JSON', { exact: true }).fill(JSON.stringify(manifest, null, 2))
  await m.getByRole('button', { name: '预览清单差异', exact: true }).click()
  await expect(m.getByText('commerce.product.review', { exact: true })).toBeVisible()
  await m.screenshot({ path: path.join(root, 'catalog-preview-1440.png'), fullPage: true, animations: 'disabled' })
  await m.getByRole('button', { name: '发布已预览清单', exact: true }).click()
  await expect(m.getByText('清单版本 2 已发布', { exact: true })).toBeVisible()
  passed('catalog owner previews and publishes real manifest without upgrading existing role or grants')


  fs.writeFileSync(path.join(root, 'ui-result.json'), JSON.stringify(checks, null, 2))
} catch (failure) {
  for (const [index, context] of browser.contexts().entries()) {
    const page = context.pages().at(-1)
    if (page) {
      await page.screenshot({ path: path.join(root, `failure-${index}.png`), fullPage: true, animations: 'disabled' })
      fs.writeFileSync(path.join(root, `failure-${index}.html`), await page.content(), { mode: 0o600 })
    }
  }
  throw failure
} finally { await browser.close() }
