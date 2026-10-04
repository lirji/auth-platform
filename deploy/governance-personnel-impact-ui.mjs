/** MG17真实接口只读页面；失败响应仅验证恢复界面，不伪造成功或业务访问。 */
import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { chromium, expect: baseExpect } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || path.resolve('../commerce-platform/frontend/node_modules/@playwright/test'))
const HTTP_OK = 200, HTTP_UNAVAILABLE = 503, HTTP_CONFLICT = 409
const expect = baseExpect.configure({ timeout: 15000 })
const dir = path.resolve(process.argv[2]), f = JSON.parse(fs.readFileSync(path.join(dir, 'browser.private.json')))
const browser = await chromium.launch({ headless: true }), context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage(), checks = [], shots = [], errors = [], requests = [], reports = [], cacheHeaders = []
const scrub = v => String(v).replace(/Bearer\s+[A-Za-z0-9_.-]+/gi, 'Bearer [REDACTED]').replace(/eyJ[A-Za-z0-9_.-]+/g, '[JWT REDACTED]')
let failRead = 0, closing = false
page.on('pageerror', e => { if (!closing) errors.push(scrub(e.message)) })
await context.addInitScript(value => { window.__MG17 = value }, f)
await page.route('**/api/governance/v1/**', async route => {
  const r = route.request(), url = new URL(r.url()); requests.push({ method: r.method(), path: url.pathname, query: url.search })
  if (failRead && url.pathname.endsWith('/personnel-impact')) {
    await route.fulfill({ status: failRead, contentType: 'application/json', body: JSON.stringify({ code: failRead === HTTP_UNAVAILABLE ? 'DEPENDENCY_UNAVAILABLE' : 'VERSION_CONFLICT', trace_id: '7a548eab-255d-4337-9228-3dc7cf20c039' }) }); return
  }
  const response = await route.fetch({ url: f.admin + url.pathname + url.search })
  if (url.pathname.endsWith('/personnel-impact') && response.status() === HTTP_OK) { reports.push(await response.json()); cacheHeaders.push(response.headers()['cache-control']) }
  await route.fulfill({ response })
})
const card = title => page.locator('.ant-card').filter({ has: page.getByText(title, { exact: true }) })
const current = card('当前人员事实'), changes = card('已接受的人员与组织变更'), sources = card('当前与历史授权来源'), dialog = page.getByRole('dialog')
async function shot(name, locator) {
  if (await dialog.isVisible()) {
    // 截图必须取已完成入场的真实弹层，避免准备帧只有遮罩却被误记为视觉PASS。
    await expect(dialog).not.toHaveClass(/ant-zoom/)
    await expect.poll(() => dialog.evaluate(e => e.contains(document.activeElement))).toBe(true)
  }
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 }); await page.evaluate(() => document.fonts.ready)
    await locator.evaluate(e => e.scrollIntoView({ block: 'start' }))
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const file = path.join(dir, `${name}-${width}.png`)
    await page.screenshot({ path: file, fullPage: false, animations: 'disabled' }); shots.push(file)
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
}
async function recoverCurrent() {
  for (let n = 0; n < 3; n++) {
    try { await expect(current).toBeVisible({ timeout: 5000 }); return } catch (e) {
      const retry = page.getByRole('button', { name: '重试查询', exact: true }); if (n === 2 || !await retry.isVisible()) throw e; await retry.click()
    }
  }
}
async function openSource(id) {
  let index = reports.at(-1).sources.findIndex(s => s.grant.grant_id === id)
  if (index < 0) {
    await sources.getByRole('button', { name: '下一页来源', exact: true }).click(); await expect(sources.getByRole('button', { name: '来源首页', exact: true })).toBeVisible()
    index = reports.at(-1).sources.findIndex(s => s.grant.grant_id === id)
  }
  assert.ok(index >= 0); await sources.locator('tbody tr.ant-table-row').nth(index).getByRole('button', { name: '查看完整来源', exact: true }).click(); await expect(dialog).toBeVisible()
}
async function closeDialog() {
  // 等待组件完成焦点迁入后再按Esc，避免把入场动画期间的按键发给背景列表。
  await expect.poll(() => dialog.evaluate(e => e.contains(document.activeElement))).toBe(true)
  await page.keyboard.press('Escape'); await expect(dialog).not.toBeVisible()
}
try {
  await page.goto(f.ui); await recoverCurrent(); await expect(page.getByRole('heading', { name: '人员变更核对', exact: true })).toBeVisible()
  await expect(page.getByText('源端同步状态未知', { exact: false })).toBeVisible(); assert.equal(reports.at(-1).target.membership_id, f.member)
  await expect(changes.locator('tbody tr.ant-table-row')).toHaveCount(100); await expect(sources.locator('tbody tr.ant-table-row')).toHaveCount(100)
  await shot('personnel-current-and-sources', current)
  await shot('personnel-source-list', page.getByText('当前与历史授权来源', { exact: true }))
  checks.push({ check: 'actual qualified personnel report, minimal directory facts and UNKNOWN source freshness; 100-row first pages', result: 'PASS' })
  await changes.getByRole('button', { name: '查看变更证据', exact: true }).first().click(); await expect(dialog.getByText('首次出现，未记录前值。', { exact: true })).toBeVisible(); await shot('personnel-first-change-evidence', dialog)
  await closeDialog()
  await changes.getByRole('button', { name: '下一页变更', exact: true }).click(); await expect(changes.locator('tbody tr.ant-table-row')).toHaveCount(8)
  await changes.getByRole('button', { name: '查看变更证据', exact: true }).last().click(); await expect(dialog.getByText('接受前事实', { exact: true })).toBeVisible(); await expect(dialog.getByText('接受后事实', { exact: true })).toBeVisible(); await expect(dialog.getByText('首次出现，未记录前值。', { exact: true })).toHaveCount(0)
  await closeDialog(); await changes.getByRole('button', { name: '变更首页', exact: true }).click(); await expect(changes.locator('tbody tr.ant-table-row')).toHaveCount(100)
  checks.push({ check: 'real immutable first and subsequent before-after facts, stable 100/8 change pagination, Escape and return retain target', result: 'PASS' })
  await openSource(f.group); await expect(dialog.getByText('当前与历史组关系', { exact: true })).toBeVisible(); await expect(dialog.getByText('第 1 代 · 历史关系 · 组织有效', { exact: true })).toBeVisible(); await expect(dialog.getByText(/主岗/)).toBeVisible(); await shot('personnel-historical-group-source', dialog.getByText('当前与历史组关系', { exact: true }))
  await closeDialog(); if (await sources.getByRole('button', { name: '来源首页', exact: true }).isVisible()) { await sources.getByRole('button', { name: '来源首页', exact: true }).click(); await expect(sources.locator('tbody tr.ant-table-row')).toHaveCount(100) }
  await openSource(f.revoked); await expect(dialog.getByText('本来源撤权已实际确认', { exact: true })).toBeVisible(); await shot('personnel-real-revocation-receipt', dialog.getByText('真实撤权回执', { exact: true }))
  await dialog.getByRole('link', { name: '前往原来源诊断与受控回收', exact: true }).click(); await expect(page.getByText('授权诊断与来源回收', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: '核对该人员的变更与全部来源', exact: true }).click(); await recoverCurrent(); await expect(page.getByLabel('核对成员编号')).toHaveValue(f.member)
  checks.push({ check: 'real GROUP historical assignment details and actual completed one-source revoke proof; both directions of original diagnostic link', result: 'PASS' })
  failRead = HTTP_UNAVAILABLE; await page.getByRole('button', { name: '重新读取当前依据', exact: true }).click(); await expect(page.getByText('服务暂时不可用，当前状态尚未确认，请稍后重试。', { exact: true })).toBeVisible(); await expect(current).toHaveCount(0); await expect(sources).toHaveCount(0); await shot('personnel-unavailable-clears-old-data', page.getByRole('alert').last())
  failRead = 0; await page.getByRole('button', { name: '重试查询', exact: true }).click(); await recoverCurrent()
  failRead = HTTP_CONFLICT; await changes.getByRole('button', { name: '下一页变更', exact: true }).click(); await expect(page.getByRole('button', { name: '重试查询', exact: true })).toBeVisible(); await expect(current).toHaveCount(0)
  failRead = 0; await page.getByRole('button', { name: '重试查询', exact: true }).click(); await recoverCurrent(); await expect(changes.locator('tbody tr.ant-table-row')).toHaveCount(100)
  checks.push({ check: '503 and 409 response fixtures hide stale report; retry clears cursors and rereads actual current basis', result: 'PASS', failure_provider: 'RESPONSE_FIXTURE_ONLY' })
  await page.goto(f.ui + '?kind=ordinary'); await expect(page.getByText('无权访问当前范围，请检查成员关系或管理委派。', { exact: true })).toBeVisible(); await expect(current).toHaveCount(0); await expect(sources).toHaveCount(0)
  checks.push({ check: 'actual ordinary-member HTTP403 remains refusal and removes all personnel details', result: 'PASS' })
  assert.equal(errors.length, 0); assert.ok(requests.every(r => r.method === 'GET')); assert.ok(cacheHeaders.length >= 6 && cacheHeaders.every(h => h === 'no-store'))
  fs.writeFileSync(path.join(dir, 'browser-result.json'), JSON.stringify({ status: 'PASS', checks, shots, errors, requests, cacheHeaders }, null, 2), { mode: 0o600 })
  process.stdout.write(`PASS: MG17 browser ${checks.length} groups; ${shots.length} screenshots; zero management writes\n`)
} catch (e) {
  await page.screenshot({ path: path.join(dir, 'failure-current.png'), fullPage: true, animations: 'disabled' }).catch(() => {})
  const body = await page.locator('body').innerText().catch(() => '')
  fs.writeFileSync(path.join(dir, 'browser-failure.json'), JSON.stringify({ status: 'FAIL', error: scrub(e.stack), body_text: scrub(body), checks, shots, errors, requests }, null, 2), { mode: 0o600 }); throw new Error(scrub(e.message))
} finally { closing = true; await page.unrouteAll({ behavior: 'ignoreErrors' }); await context.close(); await browser.close() }
