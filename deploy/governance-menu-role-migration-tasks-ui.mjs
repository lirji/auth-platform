import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
const { chromium, expect: baseExpect } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || path.resolve('../commerce-platform/frontend/node_modules/@playwright/test'))
const HTTP_ACCEPTED = 202
const HTTP_UNAVAILABLE = 503
const expect = baseExpect.configure({ timeout: 15000 })
const dir = path.resolve(process.argv[2]), f = JSON.parse(fs.readFileSync(path.join(dir, 'browser.private.json')))
const browser = await chromium.launch({ headless: true }), context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage(), checks = [], shots = [], errors = [], creates = [], mutations = [], cachePolicies = []
const scrub = value => String(value).replace(/Bearer\s+[A-Za-z0-9_.-]+/gi, 'Bearer [REDACTED]').replace(/eyJ[A-Za-z0-9_.-]+/g, '[JWT REDACTED]')
let loseCreate = true, closing = false, unavailableTask
page.on('pageerror', error => errors.push(scrub(error.message)))
await page.addInitScript(value => { window.__MG13 = value }, f)
await page.route('**/api/governance/**', async route => {
  const request = route.request(), url = new URL(request.url())
  if (unavailableTask && request.method() === 'GET' && url.pathname.endsWith('/role-migrations/' + unavailableTask)) {
    await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: 'e8ae7591-4a46-4acf-bfba-3b74d1e89a82' }) }); return
  }
  let response
  try { response = await route.fetch({ url: f.admin + url.pathname + url.search }) }
  catch { if (!closing) await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: 'e8ae7591-4a46-4acf-bfba-3b74d1e89a82' }) }); return }
  if (request.method() === 'POST' && /\/role-migrations(?:\/.*)?$/.test(url.pathname)) mutations.push({ path: url.pathname, body: request.postDataJSON(), status: response.status() })
  if (/\/role-migrations(?:\/.*)?$/.test(url.pathname) && response.ok()) cachePolicies.push(response.headers()['cache-control'])
  if (request.method() === 'POST' && url.pathname.endsWith('/role-migrations')) {
    const result = await response.json(); creates.push({ body: request.postDataJSON(), status: response.status(), result })
    // 已提交的真实响应丢失；原命令重试必须返回同任务，不能重建。
    if (loseCreate && response.status() === HTTP_ACCEPTED) { loseCreate = false; await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', trace_id: 'e8ae7591-4a46-4acf-bfba-3b74d1e89a82' }) }); return }
  }
  await route.fulfill({ response })
})
const dialog = page.getByRole('dialog'), detail = page.getByRole('region', { name: '角色迁移任务详情' })
async function request(endpoint, body) {
  const response = await context.request.post(f.admin + '/api/governance/v1/access' + endpoint, { headers: { Authorization: 'Bearer ' + f.token }, data: body })
  assert.equal(response.status(), HTTP_ACCEPTED); return response.json()
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
async function target() {
  const combo = page.getByRole('combobox', { name: '迁移目标角色版本' }); await combo.focus(); await combo.press('ArrowDown')
  await page.locator('.ant-select-dropdown').getByText('reader · v2', { exact: true }).click()
}
async function open() {
  const old = page.locator('.ant-table-tbody').first().locator('tr.ant-table-row').filter({ has: page.getByRole('cell', { name: '1', exact: true }) })
  await old.getByRole('button', { name: '迁移预览', exact: true }).click(); await expect(dialog).toBeVisible()
}
async function round() {
  await detail.getByRole('button', { name: '推进本轮', exact: true }).click()
  await expect(detail.getByRole('button', { name: '停止继续发请求', exact: true })).toHaveCount(0)
  await expect(detail.getByRole('button', { name: '刷新任务状态', exact: true })).toBeEnabled()
}
async function shot(name, locator) {
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 }); await page.evaluate(() => document.fonts.ready)
    if (locator) await locator.scrollIntoViewIfNeeded()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const file = path.join(dir, `${name}-${width}.png`); await page.screenshot({ path: file, fullPage: true, animations: 'disabled' }); shots.push(file)
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
}
try {
  await page.goto(f.ui); await expect(page.getByLabel('搜索当前页角色')).toBeVisible(); await open(); await target()
  const sources = dialog.locator('.ant-table').first(); await expect(sources.locator('tbody tr.ant-table-row')).toHaveCount(6)
  await sources.locator('thead').getByRole('checkbox').check()
  const reserved = sources.locator('tbody tr.ant-table-row').filter({ hasText: f.grants[4].source_id }); await reserved.getByRole('checkbox').uncheck()
  await page.getByRole('button', { name: '预览选中授权', exact: true }).click()
  await expect(dialog.getByText('当前条件满足 3 条，排除 2 条', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '创建选中资格的迁移任务', exact: true })).toBeDisabled()
  await dialog.getByRole('checkbox', { name: /仅为当前条件满足的 3 项创建任务/ }).check()
  await page.getByRole('button', { name: '创建选中资格的迁移任务', exact: true }).click()
  await expect(dialog.getByText('创建结果未知，保留原计划和命令')).toBeVisible()
  await expect(dialog.getByRole('button', { name: /关\s*闭/ })).toBeDisabled()
  // 隔离IdP偶发503时继续点击同一个重试入口，最多三次；每次真实响应保留，不生成替代命令。
  for (let attempt = 0; attempt < 3 && !(await detail.isVisible()); attempt++) {
    const response = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/role-migrations'))
    await page.getByRole('button', { name: '重试原创建命令', exact: true }).click()
    const settled = await response
    if (settled.status() === HTTP_ACCEPTED) { await expect(detail).toBeVisible(); break }
    assert.equal(settled.status(), HTTP_UNAVAILABLE)
    await expect(page.getByRole('button', { name: '重试原创建命令', exact: true })).toBeEnabled()
  }
  await expect(detail).toBeVisible()
  const committedCreates = creates.filter(value => value.status === HTTP_ACCEPTED)
  assert.ok(committedCreates.length >= 2 && !loseCreate)
  for (const value of creates) assert.deepEqual(value.body, creates[0].body)
  for (const value of committedCreates) assert.equal(value.result.id, committedCreates[0].result.id)
  const createdTask = committedCreates[0].result, taskId = createdTask.id
  checks.push({ check: 'explicit eligible subset and lost committed create response recover original command', result: 'PASS' })
  await request('/strict-revoke', { ...f.partition, command_id: crypto.randomUUID(), grant_id: f.grants[2].id, expected_version: 1 })
  await round(); await expect(detail.getByText(/已核验 0，失败 1，取消 0，待处理 2/)).toBeVisible()
  await expect(detail.getByText('等待真实撤权回执', { exact: true })).toHaveCount(2)
  await project(); await round(); await expect(detail.getByText('已确认撤权，待授新', { exact: true })).toHaveCount(2)
  // 同分区首项授新会进入UPDATING；第二项须等待真实READY，不能假定整轮同时授新。
  await round(); await expect(detail.getByText('等待新授权确认', { exact: true })).toHaveCount(1)
  await expect(detail.getByText('已确认撤权，待授新', { exact: true })).toHaveCount(1)
  await project(); await round(); await expect(detail.getByText(/已核验 1，失败 1，取消 0，待处理 1/)).toBeVisible()
  await expect(detail.getByText('等待新授权确认', { exact: true })).toHaveCount(1)
  const scopedIndex = createdTask.items.findIndex(item => item.old_grant_id === f.grants[0].id)
  assert.ok(scopedIndex >= 0)
  await detail.locator('.ant-table-row-expand-icon').nth(scopedIndex).click()
  await expect(detail.getByText('指定门店：S1', { exact: true })).toBeVisible()
  await expect(detail.getByText('新来源谱系', { exact: true })).toBeVisible()
  await shot('migration-new-pending', detail.getByText('新来源谱系', { exact: true }))
  await project(); await round(); await expect(detail.getByText(/已核验 2，失败 1，取消 0，待处理 0/)).toBeVisible()
  await expect(detail.getByRole('button', { name: '推进本轮', exact: true })).toBeDisabled()
  checks.push({ check: 'partial external revocation never restored and two real projection-confirmed items finish', result: 'PASS' })
  await shot('migration-partial-finished', detail.getByText('处理结束，存在失败', { exact: true }))
  unavailableTask = taskId; await detail.getByRole('button', { name: '刷新任务状态', exact: true }).click()
  await expect(detail.getByText('服务暂时不可用，当前状态尚未确认，请稍后重试。')).toBeVisible()
  await expect(detail.getByRole('button', { name: '推进本轮', exact: true })).toHaveCount(0)
  unavailableTask = undefined; await detail.getByRole('button', { name: '重试查询', exact: true }).click(); await expect(detail.getByText(taskId, { exact: true })).toBeVisible()
  checks.push({ check: '503 hides stale actions and real retry returns same persisted task', result: 'PASS' })
  await page.keyboard.press('Escape'); await expect(dialog).toHaveCount(0); await expect(page.getByLabel('搜索当前页角色')).toHaveValue('reader')
  await open(); await dialog.getByRole('button', { name: '查看已保存迁移任务', exact: true }).click()
  const history = page.getByRole('region', { name: '已保存迁移任务' }); await history.locator('tr.ant-table-row').filter({ hasText: taskId }).getByRole('button', { name: '查看／继续任务', exact: true }).click(); await expect(detail.getByText(taskId, { exact: true })).toBeVisible()
  checks.push({ check: 'close and reopen finds server history with original role list filter preserved', result: 'PASS' })
  await detail.getByRole('button', { name: '返回预览与历史任务', exact: true }).click()
  // 重开弹层只恢复服务器任务，未保存的选择已清空，清空按钮应保持禁用。
  await expect(dialog.getByRole('button', { name: '清空选择', exact: true })).toBeDisabled()
  await target()
  const nextSources = dialog.locator('.ant-table').last(); await expect(nextSources.locator('tbody tr.ant-table-row')).toHaveCount(6)
  await nextSources.locator('tbody tr.ant-table-row').filter({ hasText: f.grants[4].source_id }).getByRole('checkbox').check()
  await page.getByRole('button', { name: '预览选中授权', exact: true }).click(); await expect(dialog.getByText('当前条件满足 1 条，排除 0 条', { exact: true })).toBeVisible()
  await dialog.getByRole('checkbox', { name: /仅为当前条件满足的 1 项创建任务/ }).check(); await page.getByRole('button', { name: '创建选中资格的迁移任务', exact: true }).click(); await expect(detail).toBeVisible()
  await round(); await project(); await round(); await round(); await expect(detail.getByText('新授权待生效', { exact: true })).toBeVisible()
  await detail.getByRole('button', { name: '取消后续迁移', exact: true }).click(); await page.getByRole('button', { name: '停止后续步骤', exact: true }).click()
  await expect(detail.getByText('后续步骤已停止，已有授权效果保留', { exact: true })).toBeVisible()
  await project(); await detail.getByRole('button', { name: '刷新任务状态', exact: true }).click()
  await expect(detail.getByRole('cell').filter({ hasText: '新授权生效记录' })).toBeVisible()
  await shot('migration-cancelled-existing-grant', detail.getByText('后续步骤已停止，已有授权效果保留', { exact: true }))
  checks.push({ check: 'cancel after submitted new grant preserves actual ACTIVE effect and prevents further steps', result: 'PASS' })
  assert.ok(cachePolicies.length > 10 && cachePolicies.every(value => value === 'no-store')); assert.equal(errors.length, 0)
  checks.push({ check: 'actual task responses no-store and page has no runtime errors', result: 'PASS' })
  fs.writeFileSync(path.join(dir, 'browser-result.json'), JSON.stringify({ status: 'PASS', checks, shots, errors, creates: creates.map(value => ({ id: value.result.id, command_id: value.body.command_id, status: value.status })), mutations, cachePolicies }, null, 2), { mode: 0o600 })
  process.stdout.write(`PASS: MG13 browser ${checks.length} groups; ${shots.length} screenshots\n`)
} catch (error) {
  await page.screenshot({ path: path.join(dir, 'failure-current.png'), fullPage: true, animations: 'disabled' }).catch(() => {})
  const body = await page.locator('body').innerText().catch(() => '')
  fs.writeFileSync(path.join(dir, 'browser-failure.json'), JSON.stringify({ status: 'FAIL', error: scrub(error.stack), body_text: scrub(body), checks, shots, errors }, null, 2), { mode: 0o600 })
  throw new Error(scrub(error.message))
} finally {
  closing = true; await page.unrouteAll({ behavior: 'ignoreErrors' }); await context.close(); await browser.close()
}
