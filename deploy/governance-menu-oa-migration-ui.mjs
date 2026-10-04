import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { spawn } from 'node:child_process'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { chromium, expect: baseExpect } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || path.resolve('../commerce-platform/frontend/node_modules/@playwright/test'))
const HTTP_ACCEPTED = 202
const expect = baseExpect.configure({ timeout: 15000 })
const dir = path.resolve(process.argv[2]), f = JSON.parse(fs.readFileSync(path.join(dir, 'browser.private.json')))
const browser = await chromium.launch({ headless: true }), checks = [], shots = [], errors = [], upgrades = [], mutations = []
const scrub = value => String(value).replace(/Bearer\s+[A-Za-z0-9_.-]+/gi, 'Bearer [REDACTED]').replace(/eyJ[A-Za-z0-9_.-]+/g, '[JWT REDACTED]')
let loseUpgrade = true, legacyTask, closing = false
const contexts = []
const partitionQuery = new URLSearchParams({ tenant: f.partition.tenant_id, application: f.partition.application_id, environment: f.partition.environment })
const apiQuery = new URLSearchParams(f.partition)
async function screen(member, route) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } }); contexts.push(context)
  const page = await context.newPage(); page.on('pageerror', error => errors.push(scrub(error.message)))
  await page.addInitScript(value => { window.__MG14B = value }, { token: member ? f.member_token : f.token, subject: member ? f.member_subject : f.subject, issuer: f.issuer, route })
  await page.route('**/api/governance/**', async handler => {
    const request = handler.request(), url = new URL(request.url())
    let response
    try { response = await handler.fetch({ url: f.admin + url.pathname + url.search }) }
    catch { if (!closing) await handler.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: crypto.randomUUID() }) }); return }
    if (request.method() === 'POST') mutations.push({ path: url.pathname, body: request.postDataJSON(), status: response.status(), result: response.ok() ? await response.json() : undefined })
    if (request.method() === 'POST' && url.pathname.endsWith('/requests/role-migration') && response.status() === HTTP_ACCEPTED) {
      upgrades.push({ body: request.postDataJSON(), result: await response.json() })
      if (loseUpgrade) { loseUpgrade = false; await handler.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: crypto.randomUUID() }) }); return }
    }
    if (legacyTask && request.method() === 'GET' && url.pathname.endsWith('/role-migrations/' + legacyTask) && response.ok()) {
      const body = await response.json(); for (const item of body.items) delete item.replacement_request_id
      await handler.fulfill({ response, json: body }); return
    }
    await handler.fulfill({ response })
  })
  await page.goto(f.ui); return { context, page }
}
async function cli(name, config) {
  const file = path.join(dir, `browser-${name}-${crypto.randomUUID()}.log`), fd = fs.openSync(file, 'wx', 0o600)
  try {
    const result = await new Promise((resolve, reject) => {
      const child = spawn('java', ['-Xmx256m', '-Dloader.main=com.lrj.authz.admin.governance.' + name, '-cp', f.jar, 'org.springframework.boot.loader.launch.PropertiesLauncher', config], { stdio: ['ignore', fd, fd] })
      const timer = setTimeout(() => { child.kill('SIGTERM'); reject(new Error('owned consumer timeout')) }, 40000)
      child.once('error', error => { clearTimeout(timer); reject(error) }); child.once('exit', code => { clearTimeout(timer); resolve(code) })
    }); assert.equal(result, 0)
  } finally { fs.closeSync(fd) }
}
async function decide(context, id) {
  await cli('ApprovalStartCli', f.worker)
  const result = await context.request.post(f.fixture + '/decide', { headers: { 'X-Test-Fixture': f.fixture_key }, data: { request_id: id, outcome: 'APPROVED' }, timeout: 45000 })
  assert.equal(result.status(), 200)
}
async function read(context, endpoint, member = true) {
  const result = await context.request.get(f.admin + '/api/governance/v1/' + endpoint + '?' + apiQuery, { headers: { Authorization: 'Bearer ' + (member ? f.member_token : f.token) } })
  assert.equal(result.status(), 200); return result.json()
}
async function photos(page, name) {
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 }); await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const file = path.join(dir, `${name}-${width}.png`); await page.screenshot({ path: file, fullPage: true, animations: 'disabled' }); shots.push(file)
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
}
async function project() {
  for (const config of f.projection) {
    const deadline = Date.now() + 40000
    let ready = false
    while (Date.now() < deadline) {
      const file = path.join(dir, `browser-project-${crypto.randomUUID()}.log`), fd = fs.openSync(file, 'wx', 0o600)
      let code
      try {
        code = await new Promise((resolve, reject) => {
        const child = spawn('java', ['-Xmx256m', '-Dloader.main=com.lrj.authz.admin.governance.ReliableProjectionCli', '-cp', f.jar, 'org.springframework.boot.loader.launch.PropertiesLauncher', config], { stdio: ['ignore', fd, fd] })
        const timer = setTimeout(() => { child.kill('SIGTERM'); reject(new Error('owned projection timeout')) }, 30000)
          child.once('error', error => { clearTimeout(timer); reject(error) }); child.once('exit', result => { clearTimeout(timer); resolve(result) })
        })
      } finally { fs.closeSync(fd) }
      if (code === 0) { ready = true; break }
      assert.ok(code === 2 && /result=(BUSY|APPLIED|RETRY_WAIT|RECOVERED)\b/.test(fs.readFileSync(file, 'utf8')), 'owned projection failed or blocked')
      await new Promise(resolve => setTimeout(resolve, 500))
    }
    assert.ok(ready, 'owned projection did not confirm READY within budget')
  }
}

try {
  const self = await screen(true, '/governance/requests?' + partitionQuery + '&request=' + f.originals[1].id)
  await expect(self.page.getByRole('button', { name: '申请升级', exact: true })).toBeVisible(); await self.page.getByRole('button', { name: '申请升级', exact: true }).click()
  const form = self.page.getByRole('dialog'); await expect(form.getByText('原截止（不续期）', { exact: true })).toBeVisible(); await expect(form.getByRole('spinbutton')).toHaveCount(0)
  await form.getByRole('combobox', { name: '可申请策略' }).press('ArrowDown'); await self.page.locator('.ant-select-dropdown').getByText('reader · v2 · 策略2', { exact: true }).click()
  await form.getByRole('textbox', { name: '申请原因' }).fill('页面升级待切换'); await photos(self.page, 'oa-upgrade-fixed-form')
  await form.getByRole('button', { name: '提交申请', exact: true }).click(); await expect(form.getByText('申请结果尚未确认，重试将保留原申请范围和时间。')).toBeVisible()
  await expect(form.getByRole('button', { name: /关\s*闭/ })).toBeDisabled()
  await form.getByRole('button', { name: '重试原申请', exact: true }).click()
  await expect(self.page.getByRole('dialog').getByText('申请详情与实际进度', { exact: true })).toBeVisible()
  assert.equal(upgrades.length, 2); assert.deepEqual(upgrades[0].body, upgrades[1].body); assert.equal(upgrades[0].result.id, upgrades[1].result.id)
  const replacement = upgrades[0].result
  assert.equal(replacement.valid_to, f.originals[1].valid_to); assert.deepEqual(replacement.scope_rule, f.originals[1].scope_rule)
  assert.ok(!('valid_to' in upgrades[0].body) && !('member_id' in upgrades[0].body)); assert.equal(replacement.grant_id, null)
  checks.push({ check: 'self upgrade uses real compatible policies fixed scope and deadline; lost committed response replays original command', result: 'PASS' })
  await decide(self.context, replacement.id); await self.page.getByRole('button', { name: '刷新详情', exact: true }).click()
  await expect(self.page.getByText('实际状态：已新批准，等待迁移切换', { exact: true })).toBeVisible(); await photos(self.page, 'oa-approved-waiting')
  assert.equal((await read(self.context, 'requests/' + replacement.id)).grant_id, null)
  assert.equal((await read(self.context, 'requests/' + f.originals[1].id + '/execution')).display_state, 'ACTIVE')
  checks.push({ check: 'fresh trusted callback APPROVED waits with no Grant and old real source remains ACTIVE', result: 'PASS' })

  const manager = await screen(false, '/governance/roles?' + partitionQuery + '&q=reader')
  const old = manager.page.locator('.ant-table-tbody').first().locator('tr.ant-table-row').filter({ has: manager.page.getByRole('cell', { name: '1', exact: true }) })
  await old.getByRole('button', { name: '迁移预览', exact: true }).click(); const dialog = manager.page.getByRole('dialog')
  await dialog.getByRole('combobox', { name: '迁移目标角色版本' }).press('ArrowDown'); await manager.page.locator('.ant-select-dropdown').getByText('reader · v2', { exact: true }).click()
  const source = dialog.locator('.ant-table').first().locator('tr.ant-table-row').filter({ hasText: f.originals[1].id + ':single:1' }); await source.getByRole('checkbox').check()
  await dialog.getByRole('button', { name: '预览选中授权', exact: true }).click(); await expect(dialog.getByText('当前条件满足 1 条，排除 0 条', { exact: true })).toBeVisible()
  await dialog.getByRole('checkbox', { name: /仅为当前条件满足的 1 项创建任务/ }).check(); await dialog.getByRole('button', { name: '创建选中资格的迁移任务', exact: true }).click()
  const detail = manager.page.getByRole('region', { name: '角色迁移任务详情' }); await expect(detail).toBeVisible()
  const creation = mutations.find(x => x.path.endsWith('/role-migrations') && x.status === HTTP_ACCEPTED); assert.equal(creation.body.grants[0].replacement_request_id, replacement.id)
  let actual
  async function round() {
    await detail.getByRole('button', { name: '推进本轮', exact: true }).click(); await expect(detail.getByRole('button', { name: '停止继续发请求', exact: true })).toHaveCount(0)
    actual = await read(manager.context, 'access/role-migrations/' + creation.result.id, false)
  }
  await round(); assert.equal(actual.items[0].state, 'WAIT_REVOKE_CONFIRM'); await project(); await round(); assert.equal(actual.items[0].state, 'READY_TO_GRANT'); await round(); assert.equal(actual.items[0].state, 'WAIT_NEW_CONFIRM')
  assert.equal(actual.items[0].new_grant.source_type, 'OA_REQUEST'); assert.equal(actual.items[0].new_grant.source_id, replacement.id + ':single:1'); assert.equal(actual.items[0].new_grant.valid_to, f.originals[1].valid_to)
  await detail.locator('.ant-table-row-expand-icon').first().click(); await expect(detail.getByText('审批授权（新版独立批准）', { exact: true })).toBeVisible()
  const beforeLegacy = mutations.filter(x => x.path.endsWith('/advance')).length
  legacyTask = actual.id; await detail.getByRole('button', { name: '推进本轮', exact: true }).click(); await expect(detail.getByText('来源信息缺失，当前任务只读', { exact: true })).toBeVisible(); await expect(detail.getByRole('button', { name: '推进本轮', exact: true })).toBeDisabled(); assert.equal(mutations.filter(x => x.path.endsWith('/advance')).length, beforeLegacy)
  legacyTask = undefined; await detail.getByRole('button', { name: '刷新任务状态', exact: true }).click(); await expect(detail.getByRole('button', { name: '推进本轮', exact: true })).toBeEnabled()
  checks.push({ check: 'manager explicitly binds fresh approval; old OA response missing approval metadata stays readonly and refresh restores same task', result: 'PASS' })
  await project(); await round(); assert.equal(actual.state, 'COMPLETED'); assert.ok(actual.items[0].new_operation_id); await photos(manager.page, 'oa-migration-completed')
  checks.push({ check: 'actual page checkpoints require real old and new graph receipts; source stays OA_REQUEST and original deadline', result: 'PASS' })

  await self.page.goto(f.ui); // 同一隔离成员会话重开真实服务器申请记录。
  await expect(self.page.getByRole('button', { name: '申请升级', exact: true })).toHaveCount(0)
  // 第二原申请已经被迁移；重开时由真实执行状态隐藏升级按钮。
  const again = await read(self.context, 'requests/' + replacement.id + '/execution'); assert.equal(again.display_state, 'ACTIVE')
  checks.push({ check: 'new request binds actual Grant and shows graph-confirmed ACTIVE', result: 'PASS' })

  const third = await screen(true, '/governance/requests?' + partitionQuery + '&request=' + f.originals[2].id)
  await third.page.getByRole('button', { name: '申请升级', exact: true }).click(); const secondForm = third.page.getByRole('dialog')
  await secondForm.getByRole('combobox', { name: '可申请策略' }).press('ArrowDown'); await third.page.locator('.ant-select-dropdown').getByText('reader · v2 · 策略2', { exact: true }).click(); await secondForm.getByRole('textbox', { name: '申请原因' }).fill('本人撤回待迁移批准')
  await secondForm.getByRole('button', { name: '提交申请', exact: true }).click(); await expect(third.page.getByRole('dialog').getByText('申请详情与实际进度', { exact: true })).toBeVisible()
  const cancelled = upgrades.at(-1).result; await decide(third.context, cancelled.id); await third.page.getByRole('button', { name: '刷新详情', exact: true }).click(); await expect(third.page.getByText('实际状态：已新批准，等待迁移切换', { exact: true })).toBeVisible()
  await third.page.getByRole('button', { name: '取消申请 / 回收本来源', exact: true }).click(); await third.page.locator('.ant-modal-confirm').getByRole('button', { name: '确认取消或回收', exact: true }).click(); await expect(third.page.getByText('取消或回收已受理，请刷新查看实际结果。')).toBeVisible(); await third.page.getByRole('button', { name: '刷新详情', exact: true }).click()
  const stopped = await read(third.context, 'requests/' + cancelled.id); assert.equal(stopped.state, 'CANCELLED'); assert.equal(stopped.withdrawn, true); assert.equal(stopped.grant_id, null)
  assert.equal((await read(third.context, 'requests/' + f.originals[2].id + '/execution')).display_state, 'ACTIVE'); await photos(third.page, 'oa-new-approval-withdrawn')
  checks.push({ check: 'self withdrawal of approved ungranted replacement is persisted and leaves original source ACTIVE', result: 'PASS' })
  assert.equal(errors.length, 0); checks.push({ check: 'actual self and manager page APIs have no runtime errors', result: 'PASS' })
  fs.writeFileSync(path.join(dir, 'browser-result.json'), JSON.stringify({ status: 'PASS', checks, shots, errors, mutations }, null, 2), { mode: 0o600 })
} catch (error) {
  fs.writeFileSync(path.join(dir, 'browser-result.json'), JSON.stringify({ status: 'FAIL', checks, shots, errors: [...errors, scrub(error.stack)], mutations }, null, 2), { mode: 0o600 }); throw new Error(scrub(error.message))
} finally { closing = true; for (const context of contexts) await context.close(); await browser.close() }
