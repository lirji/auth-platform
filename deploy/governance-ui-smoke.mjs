/** 真实OIDC Token注入已有会话存储，所有菜单/授权请求仍走真实后端；不拦截API响应。 */
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
const require = createRequire(import.meta.url)
const { chromium, expect } = require(process.env.P2_PLAYWRIGHT_MODULE)
const root = process.env.P2_UI_FIXTURE
const fixture = JSON.parse(fs.readFileSync(path.join(root, 'fixture.json'), 'utf8'))
const output = path.join(root, 'ui')
const partition = { tenant_id: fixture.tenant, application_id: fixture.application, environment: fixture.environment }
const browser = await chromium.launch({ headless: true })
const checks = []
function passed(check) { checks.push({ check, result: 'PASS' }) }
async function session(token, width = 1440) {
  const context = await browser.newContext({ viewport: { width, height: 1000 } })
  const profile = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
  const value = JSON.stringify({ access_token: token, token_type: 'Bearer', scope: 'openid', profile, expires_at: profile.exp })
  await context.addInitScript(({ key, value }) => sessionStorage.setItem(key, value), {
    key: `oidc.user:${process.env.VITE_CASDOOR_AUTHORITY}:${process.env.VITE_CASDOOR_CLIENT_ID}`, value,
  })
  const page = await context.newPage()
  await page.goto('http://127.0.0.1:15273/governance')
  await expect(page.getByRole('heading', { name: '企业应用权限' })).toBeVisible()
  return { context, page }
}
async function query(page) {
  await page.getByLabel('企业标识').fill(fixture.tenant)
  await page.getByLabel('应用编码').fill(fixture.application)
  await page.getByLabel('环境', { exact: true }).fill(fixture.environment)
  await page.getByRole('button', { name: '查询我的应用' }).click()
}
async function api(route, body, token = fixture.admin_token) {
  const response = await fetch('http://127.0.0.1:18102/api/governance/v1/access/' + route, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  if (!response.ok) throw new Error(`management HTTP ${response.status}`)
  return response.json()
}
function project() {
  execFileSync('java', ['-Dloader.main=com.lrj.authz.admin.governance.ProjectionCli', '-cp', 'auth-platform-admin/target/auth-platform-admin-0.1.0-SNAPSHOT.jar',
    'org.springframework.boot.loader.launch.PropertiesLauncher', path.join(root, 'projection.properties')], { stdio: 'pipe', timeout: 30000 })
}
try {
  const member = await session(fixture.member_management_token)
  await member.page.getByRole('button', { name: '查询我的应用' }).click()
  await expect(member.page.getByText('请输入企业标识', { exact: true })).toBeVisible()
  passed('empty query validation')
  await query(member.page)
  await expect(member.page.getByRole('link', { name: 'stores', exact: true })).toBeVisible()
  await expect(member.page.getByRole('link', { name: 'stores', exact: true })).toHaveAttribute('href', 'http://127.0.0.1:8601/stores')
  await member.page.screenshot({ path: path.join(output, 'member-1440.png'), fullPage: true })
  await member.page.getByRole('button', { name: '查看管理授权状态' }).click()
  await expect(member.page.getByText('无权查看此范围，请核对当前企业成员关系或管理委派。')).toBeVisible()
  passed('real menu allows; ordinary member management denied')
  await member.page.setViewportSize({ width: 390, height: 844 })
  await member.page.screenshot({ path: path.join(output, 'member-390-forbidden.png'), fullPage: true })
  await member.page.getByLabel('环境', { exact: true }).fill('foreign')
  await expect(member.page.getByRole('link', { name: 'stores', exact: true })).toHaveCount(0)
  passed('changing partition clears old menu')
  const pending = await api('grants', { ...partition, command_id: randomUUID(), member_id: fixture.members.external,
    member_generation: 1, role_id: fixture.role_id, scope: 'TENANT_ALL', source_id: 'ui-' + randomUUID(),
    valid_from: new Date(Date.now() - 1000).toISOString(), valid_to: new Date(Date.now() + 600000).toISOString() })
  const admin = await session(fixture.admin_token)
  await query(admin.page)
  await expect(admin.page.getByText('暂时无法确认授权状态，请稍后重试。')).toBeVisible()
  await admin.page.getByRole('button', { name: '查看管理授权状态' }).click()
  await expect(admin.page.getByText('待生效', { exact: true }).first()).toBeVisible()
  await admin.page.screenshot({ path: path.join(output, 'manager-pending-1440.png'), fullPage: true })
  passed('pending graph displays pending and menu unavailable')
  project()
  await admin.page.getByRole('button', { name: '查看管理授权状态' }).click()
  await expect(admin.page.getByText('图已确认', { exact: true }).first()).toBeVisible()
  await admin.page.screenshot({ path: path.join(output, 'manager-active-1440.png'), fullPage: true })
  await admin.page.setViewportSize({ width: 390, height: 844 })
  await admin.page.screenshot({ path: path.join(output, 'manager-390.png'), fullPage: true })
  passed('graph confirmation reflected by real state refresh')
  for (const id of [pending.id, fixture.grant_id]) await api('revoke', { ...partition, command_id: randomUUID(), grant_id: id, expected_version: 1 })
  project()
  await query(member.page)
  await expect(member.page.getByText('当前没有可见入口')).toBeVisible()
  await expect(member.page.getByRole('link', { name: 'stores', exact: true })).toHaveCount(0)
  await member.page.screenshot({ path: path.join(output, 'member-revoked-390.png'), fullPage: true })
  const config = Object.fromEntries(fs.readFileSync(path.join(root, 'consumer.properties'), 'utf8').trim().split('\n').map(line => { const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1)] }))
  const result = await fetch('http://127.0.0.1:18101/internal/governance/v1/access/check', { method: 'POST',
    headers: { Authorization: `Bearer ${config['central.credential']}`, 'X-User-Access-Token': fixture.member_business_token, 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenant_id: fixture.tenant, request_id: randomUUID(), capability: 'commerce.store.read', resource_type: 'store' }) })
  if (!result.ok || (await result.json()).decision !== 'DENY') throw new Error('hidden menu backend bypass')
  passed('revoked menu hidden and direct backend call independently denies')
  fs.writeFileSync(path.join(output, 'result.json'), JSON.stringify(checks, null, 2) + '\n')
  process.stdout.write(JSON.stringify({ result: 'PASS', checks: checks.length, evidence: path.join(output, 'result.json') }) + '\n')
} finally { await browser.close() }
