import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { chromium, expect } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || path.resolve('../commerce-platform/frontend/node_modules/@playwright/test'))
const dir=path.resolve(process.argv[2]),fixture=JSON.parse(fs.readFileSync(path.join(dir,'browser.private.json')))
const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage()
const checks=[],shots=[],errors=[];let writes=0
page.on('pageerror',e=>errors.push(e.message));await page.addInitScript(f=>{window.__MG02=f},fixture)
await page.route('**/api/governance/**',async route=>{
 const request=route.request(),url=new URL(request.url())
 if(!['/catalog/drift','/catalog/preview'].some(endpoint=>url.pathname.endsWith(endpoint))&&request.method()!=='GET')writes++
 try{await route.fulfill({response:await route.fetch({url:fixture.admin+url.pathname+url.search})})}
 catch(error){if(!String(error).includes('ERR_ABORTED'))throw error}
})
const panel=page.getByRole('region',{name:'来源与发布漂移核对'}),button=panel.getByRole('button',{name:'核对当前来源与发布'}),text=page.getByLabel('应用清单JSON')
const state=()=>panel.getByText('源码 / 发布',{exact:true}).locator('xpath=ancestor::tr[1]')
const envelope=manifest=>({manifest,source:fixture.declared_source,auto_grants:false,auto_roles:false})
async function inspect(value,expected){await text.fill(JSON.stringify(value,null,2));await button.click();await expect(state()).toContainText(expected)}
async function shot(name,target){for(const width of [1440,390,320]){
 await page.setViewportSize({width,height:1000});await page.evaluate(()=>document.fonts.ready);await target.scrollIntoViewIfNeeded()
 await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true)
 const file=path.join(dir,`${name}-${width}.png`);await page.screenshot({path:file,fullPage:true,animations:'disabled'});shots.push(file)
}await page.setViewportSize({width:1440,height:1000})}
try{
 await page.goto(fixture.ui)
 await inspect(envelope(fixture.candidate),'声明一致');await shot('source-matched',state())
 await expect(panel.getByText('运行状态未知 · UNKNOWN',{exact:true})).toBeVisible()
 await expect(panel.getByText(/mg06-isolated:declared-v1/)).toBeVisible();await shot('deployment-and-runtime',panel.getByText('实际运行核验',{exact:true}))
 checks.push('current fixed source matches while stale trusted deployment remains separate and runtime explicitly unknown')
 await inspect(envelope(fixture.manifest),'源码声明与发布不一致');await shot('old-version',state())
 await panel.getByText('查看当前固定两版的具体差异',{exact:true}).click()
 await expect(panel.getByRole('heading',{name:/菜单变更/})).toBeVisible();checks.push('old project declaration exposes actual fixed version and complete differences without writing')
 await inspect(envelope(fixture.display_candidate),'显示内容不一致')
 await panel.getByText('查看当前固定两版的具体差异',{exact:true}).click()
 const changed=panel.locator('details.g-catalog-change').filter({hasText:'商品档案 · 窄屏长名称核对'}).last()
 await changed.locator('summary').click();await expect(changed.getByText('商品档案 · 窄屏长名称核对',{exact:true}).last()).toBeVisible()
 await shot('display-drift-before',changed.getByRole('region',{name:'修改前'}));await shot('display-drift-after',changed.getByRole('region',{name:'修改后'}));checks.push('same permission hash with only display differences is independently reviewable')
 await inspect(fixture.candidate,'依据未知');checks.push('missing source cannot be displayed as declaration match')
 await page.route('**/catalog/drift',route=>route.fulfill({status:403,contentType:'application/json',body:JSON.stringify({code:'ACCESS_DENIED'})}))
 await button.click();await expect(panel.getByText(/无权访问当前范围/)).toBeVisible();await expect(state()).toHaveCount(0)
 await page.unroute('**/catalog/drift')
 await page.route('**/catalog/drift',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'DEPENDENCY_UNAVAILABLE'})}))
 await button.click();await expect(panel.getByText(/服务暂时不可用/)).toBeVisible();await expect(state()).toHaveCount(0)
 await page.unroute('**/catalog/drift');await inspect(envelope(fixture.candidate),'声明一致')
 await text.fill(JSON.stringify(envelope(fixture.display_candidate)));await expect(state()).toHaveCount(0)
 checks.push('Owner denial and dependency failure clear prior evidence; editing removes old result')
 assert.deepEqual(errors,[]);assert.equal(writes,0)
 fs.writeFileSync(path.join(dir,'browser-result.json'),JSON.stringify({status:'PASS',checks,shots,errors,management_writes:writes},null,2),{mode:0o600})
 process.stdout.write(JSON.stringify({status:'PASS',checks:checks.length,shots:shots.length})+'\n')
}finally{await context.close();await browser.close()}
