/** MG18真实写入仅发生于自有分区；故障响应用于界面恢复，投影和进程恢复使用自有夹具控制端。 */
import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { GrantState } from '../auth-console/src/features/governance/shared/codes.ts'
const { chromium, expect: baseExpect } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || path.resolve('../commerce-platform/frontend/node_modules/@playwright/test'))
const expect = baseExpect.configure({ timeout: 15000 }), dir = path.resolve(process.argv[2]), f = JSON.parse(fs.readFileSync(path.join(dir, 'browser.private.json')))
const browser = await chromium.launch({ headless: true }), context = await browser.newContext({ viewport: { width: 1440, height: 1000 } }), page = await context.newPage()
const checks = [], shots = [], errors = [], requests = [], details = [], cacheHeaders = []
const scrub = v => String(v).replace(/Bearer\s+[A-Za-z0-9_.-]+/gi, 'Bearer [REDACTED]').replace(/eyJ[A-Za-z0-9_.-]+/g, '[JWT REDACTED]')
let failRead = 0, unknownOnce = false, originalUnknown, closing = false
page.on('pageerror', e => { if (!closing) errors.push(scrub(e.message)) })
await context.addInitScript(v => { window.__MG18 = v }, f)
await page.route('**/api/governance/v1/**', async route => {
  const r = route.request(), url = new URL(r.url()), body = r.postDataJSON()
  requests.push({ method: r.method(), path: url.pathname, body: body ? { ...body, reason: '[recorded in owned database]' } : null })
  if (body) assert.equal(body.tenant_id, f.partition.tenant_id)
  if (failRead && r.method() === 'GET' && /\/reviews\//.test(url.pathname)) { await route.fulfill({ status: failRead, contentType: 'application/json', body: JSON.stringify({ code: failRead === 503 ? 'DEPENDENCY_UNAVAILABLE' : 'VERSION_CONFLICT', trace_id: '7a548eab-255d-4337-9228-3dc7cf20c039' }) }); return }
  const response = await route.fetch({ url: f.admin + url.pathname + url.search })
  if (response.ok() && /\/reviews/.test(url.pathname)) { const value = await response.json(); if (value.current_report) details.push(value); cacheHeaders.push(response.headers()['cache-control']) }
  if (unknownOnce && r.method() === 'POST' && url.pathname.endsWith('/decision')) { unknownOnce = false; originalUnknown = body; await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: '7a548eab-255d-4337-9228-3dc7cf20c039' }) }); return }
  await route.fulfill({ response })
})
const card = title => page.locator('.ant-card').filter({ has: page.getByText(title, { exact: true }) })
const sources = card('当前与历史授权来源'), task = card('固定复核范围与负责人'), items = card('所选来源与逐项决定'), dialog = page.getByRole('dialog')
const current = () => details.at(-1)
const row = grant => items.locator('tbody tr.ant-table-row').nth(current().items.findIndex(i => i.grant_id === grant))
async function shot(name, locator) {
  if (await dialog.isVisible()) { await expect(dialog).not.toHaveClass(/ant-zoom/); await expect.poll(() => dialog.evaluate(e => e.contains(document.activeElement))).toBe(true) }
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 }); await page.evaluate(() => document.fonts.ready); await locator.evaluate(e => e.scrollIntoView({ block: 'start' }))
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const file = path.join(dir, `${name}-${width}.png`); await page.screenshot({ path: file, fullPage: false, animations: 'disabled' }); shots.push(file)
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
}
async function choice(label) { await dialog.locator('.ant-select-selector').first().click(); await page.locator('.ant-select-item-option').filter({ hasText: label }).click() }
async function submit(reason) { await dialog.getByLabel('记录原因', { exact: true }).fill(reason); await dialog.getByRole('button', { name: '提交本次记录', exact: true }).click() }
async function closeDialog() { await expect.poll(() => dialog.evaluate(e => e.contains(document.activeElement))).toBe(true); await page.keyboard.press('Escape'); await expect(dialog).not.toBeVisible() }
async function control(kind) { const response = await fetch(`${f.control}/${kind}`, { method: 'POST', headers: { Authorization: `Bearer ${f.control_key}` }, signal: AbortSignal.timeout(90000) }); assert.equal(response.status, 200) }
try {
  await page.goto(f.ui); await expect(sources.locator('tbody tr.ant-table-row')).toHaveCount(4)
  await sources.locator('thead .ant-checkbox-input').check(); await sources.getByRole('button', { name: '创建人工复核', exact: true }).click()
  await expect(dialog).toBeVisible(); await dialog.getByRole('button', { name: '确认创建复核', exact: true }).click(); await expect(dialog.getByText('请选择具有当前管理与独立诊断资格的负责人', { exact: true })).toBeVisible()
  await choice(f.manager); await dialog.getByLabel('复核原因', { exact: true }).fill('实际页面核对岗位变更后完整例外来源')
  await shot('review-create-selected-sources', dialog)
  await dialog.getByRole('button', { name: '确认创建复核', exact: true }).click(); await expect(task).toBeVisible(); await expect(dialog).not.toBeVisible(); assert.equal(current().items.length, 4)
  const taskId = current().id; await shot('review-fixed-scope', task)
  checks.push({ check: 'actual current-page selection, responsible validation, create and fixed four-source task', result: 'PASS' })
  await row(f.directs[1]).getByRole('button', { name: '记录决定', exact: true }).click(); await choice('保留意见'); unknownOnce = true; await submit('保留当前个人例外，不恢复或延期授权')
  await expect(dialog.getByText('结果尚未确认，请重试原命令', { exact: true })).toBeVisible(); await expect(dialog.getByRole('button', { name: /关\s*闭/ })).toBeDisabled(); await page.keyboard.press('Escape'); await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: '重试原复核命令', exact: true }).click(); await expect(dialog).not.toBeVisible(); assert.equal(current().items.find(i => i.grant_id === f.directs[1]).state, 'KEPT')
  const replayRequests = requests.filter(r => r.method === 'POST' && r.path.endsWith('/decision')); assert.equal(replayRequests[0].body.command_id, replayRequests[1].body.command_id); assert.deepEqual({ ...originalUnknown, reason: '[recorded in owned database]' }, replayRequests[1].body)
  checks.push({ check: 'response lost after actual KEEP commit; original frozen command and body retry without duplicate decision', result: 'PASS', failure_provider: 'RESPONSE_FIXTURE_AFTER_ACTUAL_COMMIT' })
  await row(f.group).getByRole('button', { name: '记录决定', exact: true }).click(); await choice('撤销本来源'); await dialog.getByLabel('记录原因', { exact: true }).fill('核对全组影响后先调查')
  await dialog.getByRole('button', { name: '提交本次记录', exact: true }).click(); await expect(dialog.getByText('请明确确认全组影响', { exact: true })).toBeVisible(); await shot('review-whole-group-warning', dialog)
  await choice('需调查'); await dialog.getByRole('button', { name: '提交本次记录', exact: true }).click(); await expect(dialog).not.toBeVisible(); assert.equal(current().state, 'NEEDS_INVESTIGATION')
  await row(f.directs[0]).getByRole('button', { name: '记录决定', exact: true }).click(); await choice('撤销本来源'); await submit('撤销这一条来源，其他来源分别保留核对'); await expect(dialog).not.toBeVisible(); assert.equal(current().items.find(i => i.grant_id === f.directs[0]).state, 'WAIT_REVOKE')
  await shot('review-pending-real-revoke', page.getByText('所选来源与逐项决定', { exact: true }))
  await row(f.directs[0]).getByRole('button', { name: '确认实际撤权', exact: true }).click(); await submit('确认必须取得实际图操作'); await expect(dialog.getByText('状态已变化，请刷新后核对；未确认的提交请使用原命令重试。', { exact: true })).toBeVisible(); await closeDialog()
  checks.push({ check: 'GROUP whole-beneficiary acknowledgment enforced; actual investigation and strict revoke202; unproven completion409', result: 'PASS' })
  await task.getByRole('button', { name: '取消未处理项', exact: true }).click(); await expect(dialog.getByText('只取消未处理项，已撤权不会恢复', { exact: true })).toBeVisible(); await submit('取消调查和未处理项，已发撤权继续核对'); await expect(dialog).not.toBeVisible(); assert.equal(current().state, 'CANCELLED')
  await control('project'); await control('restart'); await page.goto(f.ui + '?review=' + encodeURIComponent(taskId)); await expect(task).toBeVisible(); assert.equal(current().id, taskId); assert.equal(current().items.find(i => i.grant_id === f.directs[0]).state, 'WAIT_REVOKE')
  await row(f.directs[0]).getByRole('button', { name: '确认实际撤权', exact: true }).click(); await submit('重启后按原意图核对真实删除操作'); await expect(dialog).not.toBeVisible(); assert.equal(current().state, 'CANCELLED'); const revoked = current().items.find(i => i.grant_id === f.directs[0]); assert.equal(revoked.state, 'REVOKED'); assert.ok(revoked.confirmed_operation_id)
  await row(f.directs[0]).getByRole('button', { name: '查看固定来源', exact: true }).click(); await expect(dialog.getByText('复核决定与真实完成证明', { exact: true })).toBeVisible(); await shot('review-real-completion-after-resume', dialog.getByText('复核决定与真实完成证明', { exact: true })); await closeDialog()
  const real = current().current_report; assert.equal(real.sources.filter(s => s.grant.grant_state === GrantState.ACTIVE).length, 3); assert.equal(real.sources.filter(s => s.grant.grant_state === GrantState.REVOKED).length, 1)
  checks.push({ check: 'cancel preserves issued revoke; actual owned Admin exit/restart resumes original task; actual graph proof completes same source while other three remain', result: 'PASS' })
  failRead = 503; await page.getByRole('button', { name: '重新读取复核依据', exact: true }).click(); await expect(page.getByText('服务暂时不可用，当前状态尚未确认，请稍后重试。', { exact: true })).toBeVisible(); await expect(task).toHaveCount(0); await expect(items).toHaveCount(0); await shot('review-unavailable-clears-old-task', page.getByRole('alert').last())
  failRead = 0; await page.getByRole('button', { name: '重试查询', exact: true }).click(); await expect(task).toBeVisible()
  await page.goto(f.ui + '?kind=ordinary&review=' + encodeURIComponent(taskId)); await expect(page.getByText('无权访问当前范围，请检查成员关系或管理委派。', { exact: true })).toBeVisible(); await expect(task).toHaveCount(0)
  checks.push({ check: '503 response fixture removes stale task and retry; actual ordinary-member403 hides personnel and decisions', result: 'PASS', failure_provider: '503_RESPONSE_FIXTURE_ONLY' })
  assert.equal(errors.length, 0); assert.ok(cacheHeaders.length >= 10 && cacheHeaders.every(h => h === 'no-store'))
  fs.writeFileSync(path.join(dir, 'browser-result.json'), JSON.stringify({ status: 'PASS', task_id: taskId, checks, shots, errors, requests, cacheHeaders }, null, 2), { mode: 0o600 })
  process.stdout.write(`PASS: MG18 ${checks.length} browser groups; ${shots.length} screenshots; writes only owned partition\n`)
} catch (e) {
  await page.screenshot({ path: path.join(dir, 'failure-current.png'), fullPage: true, animations: 'disabled' }).catch(() => {})
  const body = await page.locator('body').innerText().catch(() => '')
  fs.writeFileSync(path.join(dir, 'browser-failure.json'), JSON.stringify({ status: 'FAIL', error: scrub(e.stack), body_text: scrub(body), checks, shots, errors, requests }, null, 2), { mode: 0o600 }); throw new Error(scrub(e.message))
} finally { closing = true; await page.unrouteAll({ behavior: 'ignoreErrors' }); await context.close(); await browser.close() }
