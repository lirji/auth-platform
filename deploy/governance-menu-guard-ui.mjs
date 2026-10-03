import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { chromium, expect } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || path.resolve('../commerce-platform/frontend/node_modules/@playwright/test'))
const dir=path.resolve(process.argv[2]),fixture=JSON.parse(fs.readFileSync(path.join(dir,'browser.private.json')))
const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage()
const checks=[],shots=[],errors=[],publications=[],enables=[];let oldWrites=0
page.on('pageerror',e=>errors.push(e.message));await page.addInitScript(f=>{window.__MG02=f},fixture)
await page.route('**/api/governance/**',async route=>{
 const request=route.request(),url=new URL(request.url())
 if(url.pathname.endsWith('/catalog/publish'))oldWrites++
 try {await route.fulfill({response:await route.fetch({url:fixture.admin+url.pathname+url.search})})}
 catch(error){if(!String(error).includes('ERR_ABORTED'))throw error}
})
const dialog=page.getByRole('dialog'),text=page.getByLabel('应用清单JSON'),preview=page.getByRole('button',{name:'预览清单差异',exact:true}),fix=page.getByRole('button',{name:'固定发布预览',exact:true}),publish=page.getByRole('button',{name:'发布固定预览',exact:true})
const fixed=page.getByRole('region',{name:'固定发布预览'}),mode=page.getByRole('region',{name:'应用发布模式'})
async function inspect(value){await text.fill(JSON.stringify(value,null,2));await preview.click();await expect(dialog.getByRole('heading',{name:/菜单变更/})).toBeVisible()}
async function shot(name,target){for(const width of [1440,390,320]){
 await page.setViewportSize({width,height:1000});await page.evaluate(()=>document.fonts.ready);await target.scrollIntoViewIfNeeded()
 await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true)
 const file=path.join(dir,`${name}-${width}.png`);await page.screenshot({path:file,fullPage:true,animations:'disabled'});shots.push(file)
}await page.setViewportSize({width:1440,height:1000})}
try{
 await page.goto(fixture.ui);await expect(dialog).toBeVisible()
 await text.fill(JSON.stringify({manifest:fixture.candidate,auto_grants:true}));await preview.click()
 await expect(dialog.getByText('导出信封含未知字段或自动授权声明')).toBeVisible();await expect(publish).toBeDisabled()
 await inspect(fixture.illegal);await expect(dialog.getByText('此候选不能发布',{exact:true})).toBeVisible();await expect(fix).toBeDisabled()
 await inspect(fixture.candidate);await expect(publish).toBeDisabled();await fix.click()
 await expect(dialog.getByText(/输入不符合要求/)).toBeVisible();await expect(fixed).toHaveCount(0)
 checks.push('invalid declaration and semantic violation blocked; old diff is insufficient for guarded publication')
 const envelope={manifest:fixture.candidate,source:fixture.declared_source,auto_grants:false,auto_roles:false}
 await inspect(envelope);await page.getByLabel('业务变更原因').fill('菜单入口调整，保留当前来源')
 await page.getByRole('combobox',{name:'授权处理决定'}).click();await page.getByTitle('保留当前授权',{exact:true}).click()
 await shot('release-decision',page.getByRole('region',{name:'发布处理决定'}))
 await page.getByRole('button',{name:'重新分析授权影响',exact:true}).click()
 await expect(dialog.getByText('依据完整，计数覆盖当前范围',{exact:true})).toBeVisible()
 await page.getByRole('checkbox',{name:'将当前分区的完整影响依据纳入发布确认'}).check()
 await fix.click();await expect(fixed.getByText('固定预览已保存',{exact:true})).toBeVisible();await expect(publish).toBeEnabled()
 await shot('fixed-ticket',fixed.getByText('基础目录摘要',{exact:true}))
 await page.getByLabel('业务变更原因').fill('菜单入口调整，保留当前来源并记录业务依据')
 await expect(fixed).toHaveCount(0);await expect(publish).toBeDisabled()
 await fix.click();await expect(fixed).toBeVisible();checks.push('fixed candidate includes source and independently confirmed impact; reason edit invalidates ticket')
 // 新功能不可用时禁止回退旧写入口，保留差异和输入供升级后重新固定。
 await page.route('**/catalog/release-preview',route=>route.fulfill({status:404,contentType:'application/json',body:JSON.stringify({code:'NOT_FOUND'})}))
 await fix.click();await expect(dialog.getByText(/此环境尚未开启该功能/)).toBeVisible();await expect(publish).toBeDisabled();assert.equal(oldWrites,0)
 await page.unroute('**/catalog/release-preview');await fix.click();await expect(fixed).toBeVisible()
 checks.push('missing fixed-preview API stays unavailable without legacy publication fallback')
 await mode.getByRole('button',{name:'读取发布模式'}).click();await expect(mode.getByText('兼容旧入口 · LEGACY',{exact:true})).toBeVisible()
 await mode.getByText('启用受控发布',{exact:true}).click()
 const enable=mode.getByRole('button',{name:'启用当前应用受控发布',exact:true});await expect(enable).toBeDisabled()
 await mode.getByRole('checkbox').check();await page.getByLabel('启用受控发布原因').fill('仅本次随机隔离应用已完成调用方升级并退出旧写节点')
 await shot('guard-enable',page.getByLabel('启用受控发布原因'))
 let lostEnable=false
 await page.route('**/catalog/enable-guard',async route=>{
  enables.push(route.request().postDataJSON());const response=await route.fetch({url:fixture.admin+'/api/governance/v1/catalog/enable-guard'})
  assert.equal(response.status(),200)
  if(!lostEnable){lostEnable=true;await route.abort('failed')}else await route.fulfill({response})
 })
 await enable.click();const retryEnable=mode.getByRole('button',{name:'重试原启用命令',exact:true})
 await expect(retryEnable).toBeVisible();await expect(text).toBeDisabled();await expect(dialog.getByRole('button',{name:/关\s*闭/})).toBeDisabled()
 await retryEnable.click();await expect(mode.getByText('受控发布 · GUARDED',{exact:true})).toBeVisible()
 assert.equal(enables.length,2);assert.deepEqual(enables[0],enables[1])
 const rejected=await page.request.post(fixture.admin+'/api/governance/v1/catalog/publish',{headers:{Authorization:'Bearer '+fixture.token,'X-Command-Id':crypto.randomUUID()},data:fixture.candidate})
 assert.equal(rejected.status(),409);checks.push('explicit isolated one-way enable survives lost response with same command and blocks actual legacy HTTP')
 await shot('guarded-ticket',mode.getByText('受控发布 · GUARDED',{exact:true}))
 let lostPublish=false
 await page.route('**/catalog/release-publish',async route=>{
  publications.push(route.request().postDataJSON());const response=await route.fetch({url:fixture.admin+'/api/governance/v1/catalog/release-publish'})
  assert.equal(response.status(),200)
  if(!lostPublish){lostPublish=true;await route.abort('failed')}else await route.fulfill({response})
 })
 await publish.click();const retry=page.getByRole('button',{name:'重试原发布命令',exact:true})
 await expect(retry).toBeVisible();await expect(text).toBeDisabled();await expect(page.getByLabel('业务变更原因')).toBeDisabled()
 await expect(dialog.getByRole('button',{name:/关\s*闭/})).toBeDisabled();await page.keyboard.press('Escape');await expect(dialog).toBeVisible()
 await retry.click();await expect(dialog.getByText('清单版本 2 已发布',{exact:true})).toBeVisible()
 assert.equal(publications.length,2);assert.deepEqual(publications[0],publications[1]);assert.deepEqual(Object.keys(publications[0]).sort(),['command_id','preview_id'])
 await shot('publication-receipt',dialog.getByText('清单版本 2 已发布',{exact:true}));checks.push('real committed publication loses response; retry binds original command and preview while freezing editing and closing')
 assert.deepEqual(errors,[]);assert.equal(oldWrites,0)
 fs.writeFileSync(path.join(dir,'browser-result.json'),JSON.stringify({status:'PASS',checks,shots,errors,legacy_ui_writes:oldWrites,publication_attempts:publications.length,enable_attempts:enables.length},null,2),{mode:0o600})
 process.stdout.write(JSON.stringify({status:'PASS',checks:checks.length,shots:shots.length})+'\n')
}finally{await context.close();await browser.close()}
