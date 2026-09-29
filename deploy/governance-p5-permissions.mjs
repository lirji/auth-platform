/** P5-04真实解释/撤权/审计；两阶段之间由Python调用真实图执行器。 */
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
const { chromium, expect } = createRequire(import.meta.url)(process.env.P5_PLAYWRIGHT_MODULE)
const root = process.env.P5_UI_FIXTURE, fixture = JSON.parse(fs.readFileSync(path.join(root, 'fixture.json'), 'utf8'))
const origin = 'http://127.0.0.1:15275', phase = process.env.P5_PERMISSIONS_PHASE, checks = [], browser = await chromium.launch({ headless: true })
const HTTP_FORBIDDEN = 403, HTTP_ACCEPTED = 202
const TestPhase = { REVOKE: 'revoke', RECEIPT: 'receipt' }, PermissionState = { ACTIVE: 'ACTIVE', REVOKED: 'REVOKED' }
const partition = `tenant_id=${fixture.tenants[0]}&application_id=commerce&environment=test`
const route = page => `${origin}/governance/${page}?tenant=${fixture.tenants[0]}&application=commerce&environment=test`
const primary = fixture.read_grants[0].id, remaining = fixture.independent_grant.id
const passed = check => checks.push({ check, result: 'PASS' })
async function page(token) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const profile = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
  await context.addInitScript(({ key, value }) => sessionStorage.setItem(key, value), { key: `oidc.user:${process.env.VITE_CASDOOR_AUTHORITY}:${process.env.VITE_CASDOOR_CLIENT_ID}`, value: JSON.stringify({ access_token: token, token_type: 'Bearer', scope: 'openid', profile, expires_at: profile.exp }) })
  return context.newPage()
}
try {
  const admin = await page(fixture.manager_token), member = await page(fixture.member_token)
  const own = async () => (await (await member.request.get(`${origin}/api/governance/v1/me/permissions?${partition}`, { headers: { Authorization: `Bearer ${fixture.member_token}` } })).json()).items
  await admin.goto(route('diagnostic') + '&grant=' + primary)
  await expect(admin.getByText(primary, { exact: true })).toBeVisible()
  if (phase === TestPhase.REVOKE) {
    await expect(admin.getByText('指定门店：STORE-A', { exact: true })).toBeVisible()
    await admin.screenshot({ path: path.join(root, 'grant-diagnostic-1440.png'), fullPage: true, animations: 'disabled' })
    const foreign = await admin.request.get(`${origin}/api/governance/v1/access/explanations?${partition}&grant_id=${fixture.read_grants[1].id}`, { headers: { Authorization: `Bearer ${fixture.manager_token}` } })
    if (foreign.status() !== HTTP_FORBIDDEN) throw new Error('foreign grant explanation must be denied')
    await member.goto(route('diagnostic') + '&grant=' + primary)
    await expect(member.getByText('无权访问当前范围，请检查成员关系或管理委派。', { exact: true })).toBeVisible()
    passed('explicit diagnostic authority reads one scoped grant; foreign partition ID and ordinary member are denied')
    const before = await own()
    if (!before.some(g => g.grant_id === primary && g.effective_state === PermissionState.ACTIVE) || !before.some(g => g.grant_id === remaining && g.effective_state === PermissionState.ACTIVE)) throw new Error('two independent sources must be active before revoke')
    await admin.getByRole('button', { name: '回收本来源', exact: true }).click()
    const receipt = admin.waitForResponse(r => r.url().endsWith('/access/strict-revoke') && r.request().method() === 'POST')
    await admin.getByRole('button', { name: '确认回收本来源', exact: true }).click()
    if ((await receipt).status() !== HTTP_ACCEPTED) throw new Error('strict revoke must be accepted asynchronously')
    await expect(admin.getByText('回收已受理，等待实际投影回执', { exact: true })).toBeVisible()
    await admin.reload(); await expect(admin.getByText('回收已受理，等待实际投影回执', { exact: true })).toBeVisible()
    passed('real admin revoke returns 202; page and reload retain processing instead of false completion')
    await admin.goto(route('audit'))
    await expect(admin.getByText('DENIED', { exact: true }).first()).toBeVisible()
    await expect(admin.getByText('REVOKE_GRANT', { exact: true })).toBeVisible()
    await admin.screenshot({ path: path.join(root, 'access-audit-1440.png'), fullPage: true, animations: 'disabled' })
    passed('protected audit includes committed revoke and denied diagnostic attempts')
  } else {
    await expect(admin.getByText('本来源回收已完成', { exact: true })).toBeVisible()
    await admin.screenshot({ path: path.join(root, 'revocation-receipt-1440.png'), fullPage: true, animations: 'disabled' })
    const grants = await own()
    if (!grants.some(g => g.grant_id === primary && g.effective_state === PermissionState.REVOKED && g.operation_id)) throw new Error('revoked source requires current version real receipt')
    if (!grants.some(g => g.grant_id === remaining && g.effective_state === PermissionState.ACTIVE)) throw new Error('independent same-capability source must remain active')
    await member.goto(route('permissions') + '&grant=' + remaining)
    await expect(member.getByText(fixture.independent_grant.source_id, { exact: false })).toBeVisible()
    const drawer = member.locator('.ant-drawer-content-wrapper').last()
    await expect.poll(async () => { const b = await drawer.boundingBox(); return b ? Math.round(b.x + b.width) : -1 }).toBe(1440)
    await member.screenshot({ path: path.join(root, 'own-permission-source-1440.png'), fullPage: true, animations: 'disabled' })
    await member.goto(`${origin}/governance?tenant=${fixture.tenants[0]}`)
    await expect(member.getByRole('link', { name: '进入 products', exact: true })).toBeVisible()
    passed('real projection receipt confirms one source revoked; independent same capability and business entry remain')
  }
  fs.writeFileSync(path.join(root, `permissions-${phase}-result.json`), JSON.stringify(checks, null, 2))
} finally { await browser.close() }
