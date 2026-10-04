/** MG16真实Owner目录／汇总与分区诊断；仅模拟丢失已提交响应和读取失败，不伪造成功。 */
import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { chromium, expect: baseExpect } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || path.resolve('../commerce-platform/frontend/node_modules/@playwright/test'))
const HTTP_OK = 200, HTTP_UNAVAILABLE = 503
const CONSOLE_ERROR_KIND = 'error'
const expect = baseExpect.configure({ timeout: 15000 })
const dir = path.resolve(process.argv[2]), f = JSON.parse(fs.readFileSync(path.join(dir, 'browser.private.json')))
const browser = await chromium.launch({ headless: true }), context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage(), checks = [], shots = [], errors = [], writes = [], policies = [], consoleErrors = [], requests = [], readRetries = []
const scrub = value => String(value).replace(/Bearer\s+[A-Za-z0-9_.-]+/gi, 'Bearer [REDACTED]').replace(/eyJ[A-Za-z0-9_.-]+/g, '[JWT REDACTED]')
let closing = false, lose = true, failRead = false
page.on('pageerror', error => { if (!closing) errors.push(scrub(error.message)) })
page.on('console', message => { if (!closing && message.type() === CONSOLE_ERROR_KIND) consoleErrors.push(scrub(message.text())) })
await context.addInitScript(value => { window.__MG16 = value }, f)
await page.route('**/api/governance/v1/**', async route => {
  const request = route.request(), url = new URL(request.url())
  requests.push({ method: request.method(), path: url.pathname })
  if (failRead && url.pathname.endsWith('/capability-retirement') && url.pathname.includes('/catalog/')) {
    await route.fulfill({ status: HTTP_UNAVAILABLE, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: 'a3a33304-c9de-4dc5-a81e-54df7cd57f7c' }) }); return
  }
  const response = await route.fetch({ url: f.admin + url.pathname + url.search })
  if (request.method() === 'POST' && url.pathname.endsWith('/capability-retire')) {
    const data = await response.json(); writes.push({ body: request.postDataJSON(), status: response.status(), result: data }); policies.push(response.headers()['cache-control'])
    if (lose && response.status() === HTTP_OK) { lose = false; await route.fulfill({ status: HTTP_UNAVAILABLE, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: 'd95f85ee-6a0e-476f-bb41-6d3f49ba6f73' }) }); return }
  }
  await route.fulfill({ response })
})
const region = page.getByRole('region', { name: '能力引用与退役' }), lifecycle = page.getByRole('region', { name: '能力生命周期' }), dialog = page.getByRole('dialog')
async function shot(name, locator) {
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 }); await page.evaluate(() => document.fonts.ready); await locator.scrollIntoViewIfNeeded()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    // 全页截图从页面顶端取景，避免把视口外的固定跳转链接绘入长图正文。
    await page.evaluate(() => window.scrollTo(0, 0))
    const file = path.join(dir, `${name}-${width}.png`); await page.screenshot({ path: file, fullPage: true, animations: 'disabled' }); shots.push(file)
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
}
async function choose(code) {
  const select = page.getByRole('combobox', { name: 'Owner目录能力' })
  await select.focus(); await select.press('ArrowDown')
  await page.getByTitle(code, { exact: true }).last().click()
  await expect(region).toBeVisible()
}
async function visibleWithRecovery(locator) {
  // 依赖503只使用真实页面查询重试，不替换成功响应或忽略持续失败。
  for (let attempt = 0; attempt < 3; attempt++) {
    try { await expect(locator).toBeVisible({ timeout: 5000 }); return }
    catch (error) {
      const retry = page.getByRole('button', { name: '重试查询', exact: true })
      if (attempt === 2 || !await retry.isVisible()) throw error
      readRetries.push({ attempt: attempt + 1, operation: 'actual-query-retry' }); await retry.click()
    }
  }
}
try {
  await page.goto(f.ui); await visibleWithRecovery(page.getByText('应用 Owner 目录治理', { exact: true }))
  await choose(f.capabilities[0])
  await region.getByRole('button', { name: '检查全应用退出条件' }).click(); await expect(region.getByText('项目接口退出尚未证明', { exact: true })).toBeVisible()
  await expect(region.getByRole('button', { name: '确认最终退役', exact: true })).toBeDisabled(); await expect(region.getByText('授权来源', { exact: true })).toBeVisible(); await shot('owner-unproven-future-source', region)
  checks.push({ check: 'last delegation exited; actual Owner directory remains available; future source and UNPROVEN cannot retire', result: 'PASS' })
  await region.getByRole('button', { name: '查看当前分区引用' }).click(); await expect(region.getByText('无权访问当前范围，请检查成员关系或管理委派。', { exact: true })).toBeVisible()
  checks.push({ check: 'Owner metadata does not inherit independent personnel diagnostics; actual 403 shown', result: 'PASS' })
  await choose(f.capabilities[1]); await region.getByRole('button', { name: '检查全应用退出条件' }).click(); await expect(region.getByText('项目接口退出已核验', { exact: true })).toBeVisible(); await expect(region.getByRole('button', { name: '确认最终退役', exact: true })).toBeEnabled()
  failRead = true; await region.getByRole('button', { name: '检查全应用退出条件' }).click(); await expect(region.getByText('服务暂时不可用，当前状态尚未确认，请稍后重试。', { exact: true })).toBeVisible(); await expect(region.getByRole('button', { name: '确认最终退役', exact: true })).toHaveCount(0)
  failRead = false; await region.getByRole('button', { name: '检查全应用退出条件' }).click(); await expect(region.getByRole('button', { name: '确认最终退役', exact: true })).toBeEnabled()
  checks.push({ check: 'failed reference read hides stale retirement controls and actual refresh recovers current basis', result: 'PASS' })
  await region.getByRole('button', { name: '确认最终退役', exact: true }).click(); await expect(dialog.getByRole('button', { name: '提交最终退役' })).toBeDisabled()
  await dialog.getByLabel('退役原因').fill('实际Owner页全部引用退出后退役'); await dialog.getByRole('checkbox').check(); await dialog.getByRole('button', { name: '提交最终退役' }).click()
  await expect(dialog.getByText('结果尚未确认，只能重试原命令', { exact: true })).toBeVisible(); await expect(dialog.getByLabel('退役原因')).toBeDisabled(); await expect(dialog.getByRole('button', { name: /取\s*消/ })).toBeDisabled(); await page.keyboard.press('Escape'); await expect(dialog).toBeVisible(); await shot('retirement-lost-committed-response', dialog)
  for (let n = 0; n < 3; n++) {
    const response = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/capability-retire'))
    await dialog.getByRole('button', { name: '重试原命令', exact: true }).click()
    if ((await response).status() === HTTP_OK) break
  }
  await expect(dialog.getByText('已确认最终退役', { exact: true })).toBeVisible(); assert.ok(writes.length >= 2 && !lose)
  writes.forEach(w => assert.deepEqual(w.body, writes[0].body)); writes.filter(w => w.status === HTTP_OK).forEach(w => assert.deepEqual(w.result.receipt, writes[0].result.receipt))
  await dialog.getByRole('button', { name: /关\s*闭/ }).click(); await expect(lifecycle.getByText('最终退役不可恢复', { exact: true })).toBeVisible(); await expect(lifecycle.getByRole('button')).toHaveCount(0); await shot('owner-retired-tombstone', lifecycle)
  checks.push({ check: 'actual committed retirement loss recovers exact original basis/version/reason/command; final state has no restoration action', result: 'PASS' })
  const ownerRequests = requests.slice(); assert.ok(!ownerRequests.some(r => r.path.endsWith('/access/state') || r.path.endsWith('/access/published-catalog')))
  await page.goto(f.ui + '?kind=manager'); await visibleWithRecovery(lifecycle.getByText('最终退役不可恢复', { exact: true })); await expect(region.getByRole('button', { name: '检查全应用退出条件' })).toHaveCount(0)
  await region.getByRole('button', { name: '查看当前分区引用' }).click(); await expect(region.getByText('当前分区引用，仅展示你已获诊断授权的数据。', { exact: true })).toBeVisible(); await expect(region.getByText('角色版本', { exact: true })).toBeVisible(); await shot('manager-retired-history-readonly', region)
  checks.push({ check: 'actual ordinary manager sees retired metadata and independently authorized history without Owner controls', result: 'PASS' })
  assert.ok(policies.length >= 2 && policies.every(p => p === 'no-store')); assert.equal(errors.length, 0)
  assert.ok(consoleErrors.every(e => e.includes('Failed to load resource') && /403|503/.test(e)))
  checks.push({ check: 'mutation no-store, no page errors, only explicitly exercised 403/503 transport errors; Owner does not query manager-only endpoints', result: 'PASS' })
  fs.writeFileSync(path.join(dir, 'browser-result.json'), JSON.stringify({ status: 'PASS', checks, shots, errors, consoleErrors, writes, policies, requests, readRetries }, null, 2), { mode: 0o600 })
  process.stdout.write(`PASS: MG16 browser ${checks.length} groups; ${shots.length} screenshots\n`)
} catch (error) {
  await page.screenshot({ path: path.join(dir, 'failure-current.png'), fullPage: true, animations: 'disabled' }).catch(() => {})
  const body = await page.locator('body').innerText().catch(() => '')
  fs.writeFileSync(path.join(dir, 'browser-failure.json'), JSON.stringify({ status: 'FAIL', error: scrub(error.stack), body_text: scrub(body), checks, shots, errors, consoleErrors, writes, requests }, null, 2), { mode: 0o600 })
  throw new Error(scrub(error.message))
} finally { closing = true; await page.unrouteAll({ behavior: 'ignoreErrors' }); await context.close(); await browser.close() }
