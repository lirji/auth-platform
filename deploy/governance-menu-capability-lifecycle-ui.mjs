/** 实际Owner/管理者组件与真实HTTP；只丢失已提交响应或制造读取故障，绝不模拟成功写入。 */
import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { chromium, expect: baseExpect } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || path.resolve('../commerce-platform/frontend/node_modules/@playwright/test'))
const HTTP_OK = 200
const expect = baseExpect.configure({ timeout: 15000 })
const dir = path.resolve(process.argv[2]), f = JSON.parse(fs.readFileSync(path.join(dir, 'browser.private.json')))
const browser = await chromium.launch({ headless: true }), context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage(), checks = [], shots = [], errors = [], writes = [], policies = []
const scrub = value => String(value).replace(/Bearer\s+[A-Za-z0-9_.-]+/gi, 'Bearer [REDACTED]').replace(/eyJ[A-Za-z0-9_.-]+/g, '[JWT REDACTED]')
let lose = true, failRead = false, legacy = false, closing = false
page.on('pageerror', error => errors.push(scrub(error.message)))
await page.addInitScript(value => { window.__MG15 = value }, f)
await page.route('**/api/governance/**', async route => {
  const request = route.request(), url = new URL(request.url())
  if (failRead && url.pathname.endsWith('/published-catalog')) { await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: 'e8ae7591-4a46-4acf-bfba-3b74d1e89a82' }) }); return }
  let response
  try { response = await route.fetch({ url: f.admin + url.pathname + url.search }) }
  catch { if (!closing) await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: 'e8ae7591-4a46-4acf-bfba-3b74d1e89a82' }) }); return }
  if (url.pathname.endsWith('/capability-lifecycle')) {
    const result = await response.json(); writes.push({ body: request.postDataJSON(), status: response.status(), result }); if (response.ok()) policies.push(response.headers()['cache-control'])
    if (lose && response.ok()) { lose = false; await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: 'e8ae7591-4a46-4acf-bfba-3b74d1e89a82' }) }); return }
  }
  if (legacy && url.pathname.endsWith('/published-catalog') && response.ok()) {
    const data = await response.json(); delete data.lifecycle_owner
    data.capabilities.forEach(cap => { delete cap.lifecycle_state; delete cap.lifecycle_version; delete cap.lifecycle_reason })
    await route.fulfill({ response, json: data }); return
  }
  await route.fulfill({ response })
})
const region = page.getByRole('region', { name: '能力生命周期' }), dialog = page.getByRole('dialog')
async function shot(name, locator) {
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 }); await page.evaluate(() => document.fonts.ready)
    if (locator) await locator.scrollIntoViewIfNeeded()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const file = path.join(dir, `${name}-${width}.png`); await page.screenshot({ path: file, fullPage: true, animations: 'disabled' }); shots.push(file)
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
}
async function retryOriginal() {
  for (let n = 0; n < 3; n++) {
    await expect(dialog.getByRole('button', { name: '重试原命令', exact: true })).toBeEnabled()
    const response = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/capability-lifecycle'))
    await dialog.getByRole('button', { name: '重试原命令', exact: true }).click()
    if ((await response).status() === HTTP_OK) { await expect(dialog.getByText('原命令已确认', { exact: true })).toBeVisible(); return }
  }
  throw new Error('original command not confirmed within bounded attempts')
}
try {
  await page.goto(f.ui); await expect(region.getByRole('button', { name: '弃用能力', exact: true })).toBeEnabled()
  await region.getByRole('button', { name: '弃用能力', exact: true }).click(); await expect(dialog).toBeVisible()
  await expect(dialog.getByText('作用于本权限实例内此应用的全部企业与环境', { exact: true })).toBeVisible()
  await expect(dialog.getByRole('button', { name: '确认提交', exact: true })).toBeDisabled()
  await dialog.getByLabel('生命周期变更原因').fill('隔离页面停止新增使用')
  await dialog.getByRole('checkbox', { name: '我已核对作用范围与已有授权的处理规则', exact: true }).check()
  await dialog.getByRole('button', { name: '确认提交', exact: true }).click()
  await expect(dialog.getByText('提交结果尚未确认', { exact: true })).toBeVisible()
  await expect(dialog.getByLabel('生命周期变更原因')).toBeDisabled(); await expect(dialog.getByRole('button', { name: /取\s*消/ })).toBeDisabled()
  await page.keyboard.press('Escape'); await expect(dialog).toBeVisible(); await shot('owner-unknown-original-command', dialog)
  await retryOriginal()
  const commits = writes.filter(value => value.status === HTTP_OK); assert.ok(commits.length >= 2 && !lose)
  writes.forEach(value => assert.deepEqual(value.body, writes[0].body)); commits.forEach(value => assert.deepEqual(value.result.receipt, commits[0].result.receipt))
  await dialog.getByRole('button', { name: /关\s*闭/ }).click(); await expect(dialog).toHaveCount(0)
  await expect(region.getByText('版本 1 · 已弃用，停止新增使用', { exact: true })).toBeVisible(); await expect(region.getByRole('button', { name: '恢复新增使用', exact: true })).toBeEnabled()
  await expect(region.getByText('最近变更原因：隔离页面停止新增使用', { exact: true })).toBeVisible(); await shot('owner-deprecated-detail', region)
  checks.push({ check: 'Owner confirms actual scope and recovers lost committed response with exact original command', result: 'PASS' })
  await region.getByRole('button', { name: '恢复新增使用', exact: true }).click(); await dialog.getByLabel('生命周期变更原因').fill('隔离页面显式恢复新增')
  await dialog.getByRole('checkbox').check(); await dialog.getByRole('button', { name: '确认提交', exact: true }).click(); await expect(dialog.getByText('原命令已确认', { exact: true })).toBeVisible(); await dialog.getByRole('button', { name: /关\s*闭/ }).click()
  await expect(region.getByText('版本 2 · 允许新增使用', { exact: true })).toBeVisible()
  assert.equal(writes.at(-1).body.expected_version, 1); assert.equal(writes.at(-1).body.state, 'ACTIVE'); assert.notEqual(writes.at(-1).body.command_id, writes[0].body.command_id)
  checks.push({ check: 'explicit restoration uses new command and expected version without claiming grants were recovered', result: 'PASS' })
  failRead = true; await page.getByRole('button', { name: '刷新目录', exact: true }).click(); await expect(page.getByText('服务暂时不可用，当前状态尚未确认，请稍后重试。', { exact: true })).toBeVisible(); await expect(region).toHaveCount(0)
  failRead = false; await page.getByRole('button', { name: '重试查询', exact: true }).click(); await expect(region.getByRole('button', { name: '弃用能力', exact: true })).toBeEnabled()
  checks.push({ check: 'catalog 503 hides stale Owner actions and real query recovery restores current metadata', result: 'PASS' })
  legacy = true; await page.getByRole('button', { name: '刷新目录', exact: true }).click(); await expect(region.getByText('当前响应缺少生命周期信息', { exact: true })).toBeVisible(); await expect(region.getByRole('button')).toHaveCount(0)
  legacy = false; await page.getByRole('button', { name: '刷新目录', exact: true }).click(); await expect(region.getByRole('button', { name: '弃用能力', exact: true })).toBeEnabled()
  checks.push({ check: 'compatible old response missing metadata displays missing state and never invents Owner permission', result: 'PASS' })
  await page.goto(f.ui + '?kind=manager'); await expect(region.getByText('仅当前应用 Owner 可以弃用或恢复；分区管理委派不包含此权限。', { exact: true })).toBeVisible(); await expect(region.getByRole('button')).toHaveCount(0); await shot('ordinary-manager-readonly', region)
  checks.push({ check: 'actual ordinary manager reads metadata without Owner controls at three widths', result: 'PASS' })
  assert.ok(policies.length >= 3 && policies.every(value => value === 'no-store')); assert.equal(errors.length, 0)
  checks.push({ check: 'actual mutation responses are no-store and page has no runtime errors', result: 'PASS' })
  fs.writeFileSync(path.join(dir, 'browser-result.json'), JSON.stringify({ status: 'PASS', checks, shots, errors, writes, policies }, null, 2), { mode: 0o600 })
  process.stdout.write(`PASS: MG15 browser ${checks.length} groups; ${shots.length} screenshots\n`)
} catch (error) {
  await page.screenshot({ path: path.join(dir, 'failure-current.png'), fullPage: true, animations: 'disabled' }).catch(() => {})
  const body = await page.locator('body').innerText().catch(() => '')
  fs.writeFileSync(path.join(dir, 'browser-failure.json'), JSON.stringify({ status: 'FAIL', error: scrub(error.stack), body_text: scrub(body), checks, shots, errors, writes }, null, 2), { mode: 0o600 })
  throw new Error(scrub(error.message))
} finally { closing = true; await page.unrouteAll({ behavior: 'ignoreErrors' }); await context.close(); await browser.close() }
