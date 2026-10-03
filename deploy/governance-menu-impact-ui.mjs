import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { chromium, expect } = createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE || path.resolve('../commerce-platform/frontend/node_modules/@playwright/test'))
const dir=path.resolve(process.argv[2]), fixture=JSON.parse(fs.readFileSync(path.join(dir,'browser.private.json')))
const browser=await chromium.launch({headless:true}), context=await browser.newContext({viewport:{width:1440,height:1000}}), page=await context.newPage()
const checks=[],shots=[],errors=[]
let publications=0
page.on('pageerror',e=>errors.push(e.message))
await page.addInitScript(f=>{window.__MG02=f},fixture)
await page.route('**/api/governance/**',async route=>{
 const endpoint=new URL(route.request().url()).pathname
 if(endpoint.endsWith('/catalog/publish'))publications++
 await route.fulfill({response:await route.fetch({url:fixture.admin+endpoint})})
})
const dialog=page.getByRole('dialog'), panel=page.getByRole('region',{name:'菜单授权影响'}), text=page.getByLabel('应用清单JSON')
const inspect=panel.getByRole('button',{name:'重新分析授权影响'})
async function preview(){await text.fill(JSON.stringify(fixture.candidate));await page.getByRole('button',{name:'预览清单差异',exact:true}).click();await expect(panel).toBeVisible()}
async function shot(name,target){
 for(const width of [1440,390,320]){
  await page.setViewportSize({width,height:1000});await target.scrollIntoViewIfNeeded()
  await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true)
  const file=path.join(dir,`${name}-${width}.png`);await page.screenshot({path:file,fullPage:true,animations:'disabled'});shots.push(file)
 }
 await page.setViewportSize({width:1440,height:1000})
}
try{
 await page.goto(fixture.ui);await preview();await inspect.click()
 await expect(panel.getByText('依据完整，计数覆盖当前范围',{exact:true})).toBeVisible()
 await expect(panel.getByText('0 / 3',{exact:true})).toBeVisible();await expect(panel.getByText('0 / 2',{exact:true})).toBeVisible()
 const count=panel.locator('.ant-descriptions-row').filter({hasText:'去重人员合计'});await expect(count.getByText('2',{exact:true})).toBeVisible()
 await expect(panel.getByText('潜在关系统计不代表实际业务访问已允许',{exact:true})).toBeVisible()
 await expect(panel.getByRole('button',{name:'读取下一页影响明细'})).toBeDisabled()
 await shot('impact-complete',panel.getByText('统计时点',{exact:true}));checks.push('real pending sources and deduplicated whole-partition statistics with fixed basis')
 const source=panel.locator('details').filter({hasText:'DIRECT'}).first();await source.locator('summary').click()
 await expect(source.getByText('固定范围',{exact:true})).toBeVisible();await expect(source.locator('pre')).toContainText('SPECIFIED_STORES')
 await shot('impact-source',source.locator('pre'));checks.push('actual beneficiary/source/version/period/typed stored scope rendered at three widths')
 await page.route('**/access/catalog-impact',async route=>await route.fulfill({response:await route.fetch({url:fixture.owner_only_admin+'/api/governance/v1/access/catalog-impact'})}))
 await inspect.click();await expect(panel.getByText(/未取得可用的影响报告/)).toBeVisible()
 await expect(panel.getByText('去重人员合计',{exact:true})).toHaveCount(0);await shot('impact-denied',panel.locator('.ant-alert-error'))
 checks.push('real Owner/manager without independent diagnostic qualification shows denial and removes prior counts')
 await page.unroute('**/access/catalog-impact')
 // 有界依赖故障只验证UI分支；不会把模拟503当成真实PG恢复证据。
 await page.route('**/access/catalog-impact',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'DEPENDENCY_UNAVAILABLE'})}))
 await inspect.click();await expect(panel.getByText(/服务暂时不可用/)).toBeVisible();await expect(panel.getByText('去重人员合计',{exact:true})).toHaveCount(0)
 checks.push('injected dependency failure cannot display stale full or zero-impact report')
 await page.unroute('**/access/catalog-impact')
 // 先取得真实响应，再有控制地延迟交付，以检查编辑候选后的过期响应隔离。
 let release,received
 const receivedPromise=new Promise(resolve=>{received=resolve}), releasePromise=new Promise(resolve=>{release=resolve})
 await page.route('**/access/catalog-impact',async route=>{
  const response=await route.fetch({url:fixture.admin+'/api/governance/v1/access/catalog-impact'});received();await releasePromise
  try{await route.fulfill({response})}catch(failure){
   // 只接受浏览器明确报告的取消；其他交付异常仍导致验收失败。
   if(!route.request().failure()?.errorText.includes('ERR_ABORTED'))throw failure
   checks.push('browser confirmed obsolete impact request cancellation')
  }
 })
 await inspect.click();await receivedPromise;await text.fill(JSON.stringify({...fixture.candidate,manifest_version:3}));release()
 await expect(panel).toHaveCount(0);await expect(page.getByRole('button',{name:'发布已预览清单',exact:true})).toBeDisabled()
 await page.unroute('**/access/catalog-impact');await preview();await inspect.click();await expect(panel.getByText('依据完整，计数覆盖当前范围',{exact:true})).toBeVisible()
 checks.push('candidate editing cancels old impact request and new candidate obtains fresh report')
 assert.deepEqual(errors,[]);assert.equal(publications,0)
 fs.writeFileSync(path.join(dir,'browser-result.json'),JSON.stringify({status:'PASS',checks,shots,errors,publication_writes:publications},null,2),{mode:0o600})
 process.stdout.write(JSON.stringify({status:'PASS',checks:checks.length,shots:shots.length})+'\n')
}finally{await context.close();await browser.close()}
