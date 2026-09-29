/** 在真实P4跨进程试验的实际Flowable待办上执行P5审批界面验收。 */
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
const { chromium, expect } = createRequire(import.meta.url)(process.env.P5_PLAYWRIGHT_MODULE)
const root = process.env.P5_OA_FIXTURE, fixture = JSON.parse(fs.readFileSync(path.join(root, 'oa-ui.json'), 'utf8'))
const browser = await chromium.launch({ headless: true }), origin = 'http://127.0.0.1:15276', checks = []
const HTTP_FORBIDDEN = 403
async function page(token) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const profile = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString())
  await context.addInitScript(({ key, value }) => sessionStorage.setItem(key, value), { key: `oidc.user:${process.env.VITE_CASDOOR_AUTHORITY}:${process.env.VITE_CASDOOR_CLIENT_ID}`, value: JSON.stringify({ access_token: token, token_type: 'Bearer', scope: 'openid', profile, expires_at: profile.exp }) })
  return context.newPage()
}
try {
  const approver = await page(fixture.manager_token)
  await approver.goto(origin + '/workbench')
  const row = approver.locator(`tr[data-row-key="${fixture.task_id}"]`)
  await expect(row).toBeVisible({ timeout: 15000 })
  await row.getByRole('button', { name: '查看审批依据', exact: true }).click()
  await expect(approver.getByText(fixture.snapshot_hash, { exact: true })).toBeVisible()
  await expect(approver.getByText('指定门店：S001', { exact: true })).toBeVisible()
  const drawer = approver.locator('.ant-drawer-content-wrapper').last()
  await expect.poll(async () => { const b = await drawer.boundingBox(); return b ? Math.round(b.x + b.width) : -1 }).toBe(1440)
  await approver.screenshot({ path: path.join(root, 'oa-approval-basis-1440.png'), fullPage: true, animations: 'disabled' })
  checks.push({ check: 'actual assigned OA task displays exact immutable request hash, capabilities, scope and time', result: 'PASS' })
  const external = await page(fixture.member_token)
  await external.goto(origin + '/workbench')
  await expect(external.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()
  await expect(external.getByText('需要权限: oa:flow:todo:view', { exact: true })).toBeVisible()
  await expect(external.getByRole('button', { name: '查看审批依据', exact: true })).toHaveCount(0)
  const denied = await external.request.get(origin + '/api/v1/flow/central-access/tasks/' + fixture.task_id, { headers: { Authorization: 'Bearer ' + fixture.member_token } })
  if (denied.status() !== HTTP_FORBIDDEN) throw new Error('external must not read employee approval basis')
  await external.screenshot({ path: path.join(root, 'oa-external-denied-1440.png'), fullPage: true, animations: 'disabled' })
  checks.push({ check: 'external has no employee todo and cannot query approval basis directly', result: 'PASS' })
  await approver.bringToFront()
  const completion = approver.waitForResponse(r => r.url().endsWith('/' + fixture.task_id + '/complete') && r.request().method() === 'POST')
  await approver.getByRole('button', { name: '同意此固定申请', exact: true }).click()
  const completed = await completion
  if (!completed.ok()) throw new Error('real OA completion failed with HTTP ' + completed.status())
  await expect(approver.getByText('已办理', { exact: true })).toBeVisible()
  await expect(approver.getByRole('button', { name: '同意此固定申请', exact: true })).toHaveCount(0)
  checks.push({ check: 'assigned approver completes real Flowable task from reviewed basis; downstream P4 verifies signed result and actual grant', result: 'PASS' })
  fs.writeFileSync(path.join(root, 'oa-ui-result.json'), JSON.stringify(checks, null, 2))
} catch (failure) {
  for (const [index, context] of browser.contexts().entries()) if (context.pages()[0]) await context.pages()[0].screenshot({ path: path.join(root, `oa-ui-failure-${index}.png`), fullPage: true, animations: 'disabled' })
  throw failure
} finally { await browser.close() }
