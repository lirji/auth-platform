import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { chromium, expect: baseExpect } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || path.resolve('../commerce-platform/frontend/node_modules/@playwright/test'))
const expect = baseExpect.configure({ timeout: 15000 })
const dir = path.resolve(process.argv[2]), fixture = JSON.parse(fs.readFileSync(path.join(dir, 'browser.private.json')))
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage(), checks = [], shots = [], errors = [], previews = [], writes = []
const cachePolicies = []
let closing = false
const scrub = text => text.replace(/Bearer\s+[A-Za-z0-9_.-]+/gi, 'Bearer [REDACTED]').replace(/eyJ[A-Za-z0-9_.-]+/g, '[JWT REDACTED]')
page.on('pageerror', e => errors.push(e.message))
await page.addInitScript(f => { window.__MG12 = f }, fixture)
await page.route('**/api/governance/**', async route => {
  const request = route.request(), url = new URL(request.url()), endpoint = url.pathname + url.search
  if (request.method() !== 'GET' && !url.pathname.endsWith('/role-migration-preview')) writes.push(url.pathname)
  // 保留游标和分区查询参数，页面接口全部经过实际JAR和独立PG。
  let response
  try { response = await route.fetch({ url: fixture.admin + endpoint }) }
  catch { if (!closing) await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: '0a3e1c3b-0870-4f3a-bbac-597b64dd7fe6' }) }); return }
  if (url.pathname.endsWith('/role-migration-preview')) previews.push({ input: request.postDataJSON(), status: response.status(), result: await response.json() })
  if (/\/role-migration-(?:preview|grants)$/.test(url.pathname) && response.ok()) cachePolicies.push(response.headers()['cache-control'])
  await route.fulfill({ response })
})
const dialog = page.getByRole('dialog'), preview = page.getByRole('button', { name: '预览选中授权', exact: true })
const report = page.getByRole('region', { name: '角色迁移资格结果' })
async function target(version) {
  const combobox = page.getByRole('combobox', { name: '迁移目标角色版本' })
  await combobox.focus(); await combobox.press('ArrowDown')
  await page.locator('.ant-select-dropdown').getByText(`reader · v${version}`, { exact: true }).click()
}
async function shot(name, locator) {
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 })
    await page.evaluate(() => document.fonts.ready)
    if (locator) await locator.scrollIntoViewIfNeeded()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const file = path.join(dir, `${name}-${width}.png`)
    await page.screenshot({ path: file, fullPage: true, animations: 'disabled' }); shots.push(file)
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
}
try {
  await page.goto(fixture.ui)
  const list = page.locator('.ant-table-tbody').first()
  const old = list.locator('tr.ant-table-row').filter({ has: page.getByRole('cell', { name: '1', exact: true }) })
  await expect(old).toHaveCount(1)
  await page.getByLabel('搜索当前页角色').fill('reader')
  await old.getByRole('button', { name: '迁移预览', exact: true }).click()
  await expect(dialog).toBeVisible(); await expect(preview).toBeDisabled()
  await expect(dialog.getByText('只读核对，不执行授权迁移')).toBeVisible()
  await target(2)
  const sources = dialog.locator('.ant-table').first()
  await expect(sources.locator('tbody tr.ant-table-row')).toHaveCount(20)
  await sources.locator('thead').getByRole('checkbox').check()
  await expect(dialog.getByText(/已明确选择 20 \/ 50 条/)).toBeVisible()
  await page.getByRole('button', { name: '下一页授权引用' }).click()
  await expect(sources.locator('tbody tr.ant-table-row')).toHaveCount(3)
  await sources.locator('thead').getByRole('checkbox').check()
  await expect(dialog.getByText(/已明确选择 23 \/ 50 条/)).toBeVisible()
  await preview.click()
  await expect(report.getByText('当前条件满足 22 条，排除 1 条', { exact: true })).toBeVisible()
  assert.equal(previews.at(-1).input.grant_ids.length, 23)
  assert.equal(new Set(previews.at(-1).input.grant_ids).size, 23)
  await expect(report.getByText('此来源需按组或审批规则单独处理')).toBeVisible()
  await expect(report.getByText(/本报告不是执行票据/)).toBeVisible()
  checks.push('actual role entry, explicit higher version, cross-page 23 UUIDs and OA exclusion')
  const scoped = previews.at(-1).result.items.findIndex(item => item.grant.id === fixture.grants[0].id)
  await report.locator('tbody tr.ant-table-row').nth(scoped).locator('.ant-table-row-expand-icon').click()
  await expect(report.getByText('指定门店：S1', { exact: true })).toBeVisible()
  await expect(report.getByText(previews.at(-1).result.items[scoped].proposed_source_id, { exact: true })).toBeVisible()
  await shot('fixed-scope', report.getByText('指定门店：S1', { exact: true }))
  checks.push('actual immutable S1 scope, original time window and lineage visible at three widths')
  const count = previews.length
  await preview.click()
  await expect.poll(() => previews.length).toBe(count + 1)
  await expect(report.getByText('当前条件满足 22 条，排除 1 条', { exact: true })).toBeVisible()
  checks.push('same selection refresh performs a fresh authenticated assessment')
  await target(3); await expect(report).toHaveCount(0)
  await preview.click()
  await expect(report.getByText('当前条件满足 0 条，排除 23 条', { exact: true })).toBeVisible()
  await expect(report.getByText('新增能力须走独立授权或审批').first()).toBeVisible()
  await shot('expanded-role-excluded', report.getByText('新增能力须走独立授权或审批').first())
  checks.push('target change immediately clears stale qualification; added capabilities all excluded')
  // 可控503只验证UI恢复；其他接口和前后SQL仍是真实集成。
  await page.route('**/role-migration-preview', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: '2d4a43aa-711b-4996-90da-ced82a1b8831' }) }))
  await preview.click()
  await expect(dialog.getByText('服务暂时不可用，当前状态尚未确认，请稍后重试。')).toBeVisible()
  await expect(report).toHaveCount(0)
  await expect(dialog.getByText(/已明确选择 23 \/ 50 条/)).toBeVisible()
  await expect(dialog.locator('.ant-select-selection-item')).toHaveText('reader · v3')
  await shot('unavailable-preserves-input', dialog.locator('.ant-alert-error'))
  await page.unroute('**/role-migration-preview')
  await page.getByRole('button', { name: '重试查询' }).click()
  await expect(report.getByText('当前条件满足 0 条，排除 23 条', { exact: true })).toBeVisible()
  checks.push('simulated 503 hides old report, preserves inputs and retry calls real backend')
  await page.getByRole('button', { name: '清空选择' }).click()
  await expect(report).toHaveCount(0); await expect(preview).toBeDisabled()
  await expect(dialog.getByText(/已明确选择 0 \/ 50 条/)).toBeVisible()
  await page.keyboard.press('Escape'); await expect(dialog).toHaveCount(0)
  await expect(old.getByRole('button', { name: '迁移预览', exact: true })).toBeFocused()
  await expect(page.getByLabel('搜索当前页角色')).toHaveValue('reader')
  await expect(old).toHaveCount(1)
  checks.push('clear selection and keyboard close preserve role list filter and context')
  // 延迟真实响应，在选择变化后回送，证明旧结果不能覆盖新的选择。
  await old.getByRole('button', { name: '迁移预览', exact: true }).click()
  await target(2); await expect(sources.locator('tbody tr.ant-table-row')).toHaveCount(20)
  await sources.locator('tbody tr.ant-table-row').first().getByRole('checkbox').check()
  let release, entered, settled
  const gate = new Promise(resolve => { release = resolve }), pending = new Promise(resolve => { entered = resolve }), completed = new Promise(resolve => { settled = resolve })
  await page.route('**/role-migration-preview', async route => {
    const response = await route.fetch({ url: fixture.admin + new URL(route.request().url()).pathname })
    entered(); await gate
    await route.fulfill({ response }).catch(() => {}) // 已取消请求的响应不能进入页面。
    settled()
  })
  await preview.click(); await pending
  await target(3); release(); await completed
  await expect(report).toHaveCount(0)
  await expect(dialog.locator('.ant-select-selection-item')).toHaveText('reader · v3')
  await page.keyboard.press('Escape'); await expect(dialog).toHaveCount(0)
  checks.push('late real response cannot display a qualification for the previous target')
  assert.deepEqual(writes, []); assert.deepEqual(errors, [])
  assert.ok(cachePolicies.length >= previews.length && cachePolicies.every(value => value?.includes('no-store')))
  checks.push('authorized migration GET and POST enforce actual no-store response headers')
  fs.writeFileSync(path.join(dir, 'browser-result.json'), JSON.stringify({ status: 'PASS', checks, shots, errors, governance_writes: writes, real_preview_requests: previews.length, no_store_responses: cachePolicies.length }, null, 2), { mode: 0o600 })
  process.stdout.write(JSON.stringify({ status: 'PASS', checks: checks.length, shots: shots.length }) + '\n')
} catch (error) {
  const failure = scrub(String(error.stack ?? error))
  fs.writeFileSync(path.join(dir, 'browser-failure.json'), JSON.stringify({ status: 'FAIL', checks, shots, failure, errors }, null, 2), { mode: 0o600 })
  process.stderr.write(failure + '\n'); process.exitCode = 1
} finally { closing = true; await page.unrouteAll({ behavior: 'ignoreErrors' }); await context.close(); await browser.close() }
