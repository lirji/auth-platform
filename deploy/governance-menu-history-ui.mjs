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
 const request=route.request(), url=new URL(request.url())
 if(request.method()!=='GET')writes++
 await route.fulfill({response:await route.fetch({url:fixture.admin+url.pathname+url.search})})
})
const panel=page.getByRole('region',{name:'应用发布历史'}),refresh=panel.getByRole('button',{name:'刷新发布历史'})
async function shot(name,target){for(const width of [1440,390,320]){
 await page.setViewportSize({width,height:1000});await target.scrollIntoViewIfNeeded()
 await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true)
 const file=path.join(dir,`${name}-${width}.png`);await page.screenshot({path:file,fullPage:true,animations:'disabled'});shots.push(file)
}await page.setViewportSize({width:1440,height:1000})}
try{
 await page.goto(fixture.ui);await refresh.click();await expect(panel.getByText('当前页 2 条版本记录，每页最多100条。')).toBeVisible()
 await expect(panel.getByText('来源未记录',{exact:true})).toHaveCount(1);await expect(panel.getByText('源码提交 '+fixture.declared_source.commit,{exact:true})).toBeVisible()
 await expect(panel.getByRole('button',{name:'读取更早的发布版本'})).toBeDisabled();await shot('history-list',panel.getByText('版本 1',{exact:true}))
 checks.push('real historical source and unknown-source records in descending fixed versions')
 await panel.getByRole('button',{name:'查看版本 1 详情'}).click();const detail=panel.locator('[aria-label="固定发布详情"]')
 await expect(detail.getByText('固定版本 1',{exact:true})).toBeVisible();await expect(detail.getByText(fixture.declared_source.artifact_hash,{exact:true})).toBeVisible()
 await expect(detail.getByText('原发布预览 · 以当时基础版本为准',{exact:true})).toBeVisible()
 await shot('history-source',detail.getByText('源码提交',{exact:true}))
 await detail.getByText('固定版本实际菜单清单',{exact:true}).click();await expect(detail.locator('pre')).toContainText('/products')
 await expect(detail.locator('pre')).not.toContainText('/product-records');await shot('history-fixed-menu',detail.locator('pre'))
 checks.push('fixed older menu and original preview remain independent of newer current publication')
 await refresh.click();await panel.getByRole('button',{name:'查看版本 2 详情'}).click()
 await expect(detail.getByText('来源未记录',{exact:true})).toHaveCount(2);checks.push('legacy entry publication retains unknown source instead of fabricated metadata')
 // 只注入只读失败展示分支；真正的跨Owner拒绝和404在HTTP验收执行。
 await page.route('**/catalog/releases?**',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'DEPENDENCY_UNAVAILABLE'})}))
 await refresh.click();await expect(panel.getByText(/服务暂时不可用/)).toBeVisible();await expect(panel.getByText('当前应用尚无发布版本。')).toHaveCount(0)
 await expect(detail).toHaveCount(0);checks.push('read failure clears prior version and cannot claim empty history')
 assert.deepEqual(errors,[]);assert.equal(writes,0)
 fs.writeFileSync(path.join(dir,'browser-result.json'),JSON.stringify({status:'PASS',checks,shots,errors,ui_writes:writes},null,2),{mode:0o600})
 process.stdout.write(JSON.stringify({status:'PASS',checks:checks.length,shots:shots.length})+'\n')
}finally{await context.close();await browser.close()}
