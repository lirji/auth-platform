import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { chromium, expect } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || path.resolve('../commerce-platform/frontend/node_modules/@playwright/test'))
const dir = path.resolve(process.argv[2]), fixture = JSON.parse(fs.readFileSync(path.join(dir, 'browser.private.json')))
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage(), checks = [], shots = [], errors = [], publications = []
page.on('pageerror', e => errors.push(e.message))
await page.addInitScript(f => { window.__MG02 = f }, fixture)
await page.route('**/api/governance/**', async route => {
  const request = route.request(), endpoint = new URL(request.url()).pathname
  if (endpoint.endsWith('/catalog/publish')) publications.push({ command: request.headers()['x-command-id'], body: request.postDataJSON() })
  // 仅代理到本次实际JAR，保持真实认证、校验和数据库结果。
  await route.fulfill({ response: await route.fetch({ url: fixture.admin + endpoint }) })
})
const dialog = page.getByRole('dialog')
const text = page.getByLabel('应用清单JSON')
const preview = page.getByRole('button', { name: '预览清单差异', exact: true })
const publish = page.getByRole('button', { name: '发布已预览清单', exact: true })
async function inspect(value) {
  await text.fill(JSON.stringify(value, null, 2)); await preview.click()
  await expect(dialog.getByRole('heading', { name: /菜单变更/ })).toBeVisible()
}
async function shot(name, target) {
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 })
    await page.evaluate(() => document.fonts.ready)
    if (target) await target.scrollIntoViewIfNeeded()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const file = path.join(dir, `${name}-${width}.png`)
    await page.screenshot({ path: file, fullPage: true, animations: 'disabled' }); shots.push(file)
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
}
try {
  await page.goto(fixture.ui); await expect(dialog).toBeVisible()
  await inspect(fixture.candidate)
  await expect(dialog.locator('.g-catalog-change')).toHaveCount(3)
  await expect(publish).toBeEnabled()
  const changed = dialog.locator('details').filter({ hasText: '商品档案' })
  await changed.locator('summary').click()
  await expect(changed.getByText('/products', { exact: true })).toBeVisible()
  await expect(changed.getByText('/product-records', { exact: true })).toBeVisible()
  await expect(changed.getByText('group.a', { exact: true })).toBeVisible()
  await expect(changed.getByText('group.b', { exact: true })).toBeVisible()
  checks.push('stable ID shows actual old/new label, parent, route, order and mapping')
  await shot('valid-diff-before', changed.getByRole('region', { name: '修改前' }))
  await shot('valid-diff-after', changed.getByRole('region', { name: '修改后' }))
  await page.getByLabel('搜索菜单变更').fill('旧入口')
  await expect(dialog.locator('details')).toHaveCount(1)
  await page.getByLabel('搜索菜单变更').fill('不存在')
  await expect(dialog.getByText('没有匹配的菜单变更，请调整筛选。')).toBeVisible()
  checks.push('search narrows full diff and distinguishes no matches')
  await inspect(fixture.illegal)
  await expect(dialog.getByText('此候选不能发布', { exact: true })).toBeVisible()
  await expect(dialog.getByText(/已发布能力的资源或风险语义不能改变/)).toBeVisible()
  await expect(publish).toBeDisabled(); await shot('blocked-diff', dialog.locator('.ant-alert-error'))
  checks.push('real semantic violation is visible and publication disabled at three widths')
  await text.fill(JSON.stringify(fixture.candidate))
  await expect(dialog.locator('.g-catalog-changes')).toHaveCount(0)
  await expect(publish).toBeDisabled()
  checks.push('editing invalidates fixed preview immediately')
  await page.route('**/catalog/preview', async route => {
    const response = await route.fetch({ url: fixture.admin + '/api/governance/v1/catalog/preview' })
    const old = await response.json()
    for (const key of ['publishable', 'menu_changes', 'violations', 'presentation_hash', 'affected_capabilities']) delete old[key]
    await route.fulfill({ response, json: old })
  })
  await preview.click()
  await expect(dialog.getByText('当前服务未返回完整菜单差异')).toBeVisible(); await expect(publish).toBeDisabled()
  await expect(dialog.getByText('菜单内容与当前发布版本一致。')).toHaveCount(0)
  checks.push('old service response stays unavailable instead of claiming zero diff')
  await page.unroute('**/catalog/preview')
  await preview.click(); await expect(publish).toBeEnabled()
  // 只发布本次随机隔离应用，回执丢失模拟验证原键原体重试，服务端事务仍真实执行。
  let lost = false
  await page.route('**/catalog/publish', async route => {
    const request = route.request()
    publications.push({ command: request.headers()['x-command-id'], body: request.postDataJSON() })
    const response = await route.fetch({ url: fixture.admin + '/api/governance/v1/catalog/publish' })
    if (!lost) { lost = true; await route.abort('failed') } else await route.fulfill({ response })
  })
  await publish.click()
  const retry = page.getByRole('button', { name: '重试原发布命令', exact: true })
  await expect(retry).toBeVisible(); await expect(text).toBeDisabled()
  await expect(dialog.getByRole('button', { name: /关\s*闭/ })).toBeDisabled()
  await page.keyboard.press('Escape'); await expect(dialog).toBeVisible()
  await retry.click(); await expect(dialog.getByText('清单版本 2 已发布', { exact: true })).toBeVisible()
  assert.equal(publications.length, 2); assert.deepEqual(publications[0], publications[1])
  checks.push('lost committed response freezes input and closing; retry keeps same key and body')
  assert.deepEqual(errors, [])
  fs.writeFileSync(path.join(dir, 'browser-result.json'), JSON.stringify({ status: 'PASS', checks, shots, errors, isolated_publication_attempts: publications.length }, null, 2), { mode: 0o600 })
  console.log(JSON.stringify({ status: 'PASS', checks: checks.length, shots: shots.length }))
} finally { await context.close(); await browser.close() }
