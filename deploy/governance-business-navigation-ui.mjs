/** MG08真实电商浏览器，普通场景不拦截API；迟到实验仅延迟真实旧响应。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {setTimeout as delay} from 'node:timers/promises';
const {chromium,expect}=createRequire(import.meta.url)(process.env.NAV_PLAYWRIGHT_MODULE);
const root=process.env.NAV_UI_RUN,f=JSON.parse(fs.readFileSync(path.join(root,'navigation-ui.json'),'utf8'));
const origin='http://127.0.0.1:21764',browser=await chromium.launch({headless:true});
const Phase={ACTIVE:'active',MULTI:'multi',PENDING:'pending',REVOKED:'revoked',OUTAGE:'outage',FINISH:'finish'};
const checks=[],errors=[],businessRequests=[];
const save=(name,value)=>fs.writeFileSync(path.join(root,name),JSON.stringify(value,null,2),{mode:0o600});
const record=check=>checks.push({check,result:'PASS'});
async function contextFor(token){
 const context=await browser.newContext({viewport:{width:1440,height:1000}});
 await context.addInitScript(({authority,client,token})=>{
  const profile=JSON.parse(atob(token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
  sessionStorage.setItem(`oidc.user:${authority}:${client}`,JSON.stringify({access_token:token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
 },{authority:f.authority,client:f.client,token});
 return context;
}
const context=await contextFor(f.user_token),page=await context.newPage();
page.on('pageerror',error=>errors.push(error.message));
page.on('request',request=>{const u=new URL(request.url());if(u.pathname.startsWith('/v1/'))businessRequests.push(u.pathname);});
const target=(route='/operations/products')=>origin+route+'?tenant_id='+f.tenant;
async function shot(name){await page.screenshot({path:path.join(root,'mg08-'+name+'.png'),fullPage:true,animations:'disabled'});}
async function overflow(){
 try{await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),{timeout:5000}).toBe(false);}
 catch(error){save('overflow-diagnostics.json',await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,offenders:Array.from(document.querySelectorAll('body *')).map(e=>({tag:e.tagName,class:e.className,rect:e.getBoundingClientRect().toJSON(),display:getComputedStyle(e).display,visibility:getComputedStyle(e).visibility})).filter(e=>e.rect.right>innerWidth+1&&e.display!=='none')})));throw error;}
}
async function refresh(){await page.getByRole('button',{name:'刷新页面权限',exact:true}).click();await expect(page.getByText('正在加载可访问页面',{exact:true})).toHaveCount(0);}
async function productReady(){await expect(page.getByRole('heading',{name:'商品经营',exact:true})).toBeVisible();await expect(page.getByText('导航验收商品',{exact:true})).toBeVisible();}
async function call(route){return page.request.get(origin+'/v1/operations/scoped/product'+route,{headers:{Authorization:'Bearer '+f.user_token,'X-Tenant-Id':f.tenant}});}
async function phase(name){
 if(name===Phase.ACTIVE){
  await page.goto(target('/operations'));await expect(page).toHaveURL(/\/operations\/products\?/);await productReady();
  assert.equal((await call('/resources/P1')).status(),200);assert.equal((await call('/resources/P2')).status(),403);
  await expect(page.getByText('保密商品',{exact:true})).toHaveCount(0);
  await expect(page.locator('nav a')).toHaveCount(2);await expect(page.locator('nav a[href*="/operations/orders"]')).toHaveCount(0);
  await expect(page.locator('nav a').filter({hasText:'商品与门店'})).toHaveCount(0);
  record('real single capability shows only assigned-store product; direct other-store resource denied; ancestor has no href');
  const returns=await page.evaluate(async()=>{const {safeReturn}=await import('/src/iam/session.ts');return [safeReturn('https://evil.invalid/operations/products'),safeReturn('http://['),safeReturn('/operations/orders?tenant_id=11111111-1111-4111-8111-111111111111&operator=forged')];});
  assert.deepEqual(returns,['/operations','/operations','/operations/orders?tenant_id=11111111-1111-4111-8111-111111111111']);record('default landing selects visible route; allowlisted deep links preserved and external/malformed return rejected');
  const search=page.getByRole('combobox',{name:'搜索中央功能',exact:true});await search.fill('订单');await expect(page.locator('.ant-select-item-option-content')).toHaveCount(0);await page.keyboard.press('Escape');await search.fill('商品');await expect(page.locator('.ant-select-item-option-content')).toHaveCount(2);await shot('search-1440');await page.keyboard.press('Escape');record('search uses the same visible route set, including collaboration entry');
  for(const width of [1440,390,320]){
   await page.setViewportSize({width,height:width===1440?1000:844});await overflow();await shot('single-'+width);
   if(width!==1440){await page.getByRole('button',{name:'打开经营导航',exact:true}).click();await expect(page.getByRole('dialog')).toBeVisible();await expect(page.getByRole('dialog').getByRole('link',{name:'授权商品',exact:true})).toBeVisible();await expect(page.getByRole('dialog').getByRole('link',{name:'商品协作',exact:true})).toBeVisible();await overflow();await shot('mobile-navigation-'+width);await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).toHaveCount(0);}
  }
  record('desktop and 390/320 mobile navigation, search, dialog keyboard close and no horizontal overflow');
  await page.setViewportSize({width:1440,height:1000});await page.goto(target('/collaboration/products'));await expect(page.getByRole('heading',{name:'门店商品协作',exact:true})).toBeVisible();await page.getByRole('menuitem',{name:'商品与门店',exact:true}).click();await expect(page.getByRole('link',{name:'授权商品',exact:true})).toBeVisible();await shot('collaboration-1440');record('real collaboration entry retains navigation back to permitted operations');
  businessRequests.length=0;await page.goto(target('/operations/orders'));await expect(page.getByText('当前页面没有访问权限',{exact:true})).toBeVisible();await expect(page.getByRole('heading',{level:1})).toHaveCount(0);assert.equal(businessRequests.some(p=>p.includes('/orders')),false);await shot('deep-link-forbidden-1440');record('forbidden deep link is explicit and does not mount or fetch the forbidden business page');
  const other=await contextFor(f.manager_business_token),manager=await other.newPage();
  await manager.goto(target('/operations'));await expect(manager.getByText('当前组织暂无可访问页面',{exact:true})).toBeVisible();await expect(manager.locator('nav a')).toHaveCount(0);await manager.screenshot({path:path.join(root,'mg08-no-access-1440.png'),fullPage:true,animations:'disabled'});record('real manager identity with explicit local binding has no implicit business navigation');
  await other.close();const wrongContext=await contextFor(f.wrong_audience_token),wrong=await wrongContext.newPage();await wrong.goto(target('/operations'));await expect(wrong.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();assert.equal(await wrong.evaluate(({authority,client})=>sessionStorage.getItem(`oidc.user:${authority}:${client}`),f),null);record('actual wrong-audience 401 clears stored session and mounted data');await wrongContext.close();
  await page.goto(target());await productReady();
  let release,ready,done;const gate=new Promise(resolve=>{release=resolve;}),held=new Promise(resolve=>{ready=resolve;}),finished=new Promise(resolve=>{done=resolve;});let first=true;
  const handler=async route=>{if(!first){await route.continue();return;}first=false;const response=await route.fetch();assert.equal(response.status(),200);const body=await response.body();ready();await gate;await route.fulfill({response,body});done();};
  await page.route('**/v1/operations/navigation*',handler);
  await page.getByRole('button',{name:'刷新页面权限',exact:true}).click();await held;
  await page.evaluate(()=>{history.replaceState(null,'','/operations/products?tenant_id=33333333-3333-4333-8333-333333333333');dispatchEvent(new PopStateEvent('popstate'));});
  await expect(page.getByText('当前成员没有此组织或环境的访问权限',{exact:true})).toBeVisible();await expect(page.locator('nav a')).toHaveCount(0);release();await finished;
  await expect(page.getByText('当前成员没有此组织或环境的访问权限',{exact:true})).toBeVisible();await expect(page.locator('nav a')).toHaveCount(0);await shot('late-response-1440');await page.unroute('**/v1/operations/navigation*',handler);record('delayed actual old response cannot overwrite newly denied tenant; test proves client isolation only');
  await page.goto(target()+'&environment=production');await expect(page.getByText('当前成员没有此组织或环境的访问权限',{exact:true})).toBeVisible();await expect(page.locator('nav a')).toHaveCount(0);record('URL environment cannot select a different permission deployment');
  await page.goto(target());await productReady();
 } else if(name===Phase.MULTI){
  await refresh();await expect(page.locator('nav a')).toHaveCount(2);
  const nav=await page.request.get(origin+'/v1/operations/navigation',{headers:{Authorization:'Bearer '+f.user_token,'X-Tenant-Id':f.tenant}});assert.equal(nav.status(),200);assert.deepEqual((await nav.json()).capabilityHints,['commerce.product.read','commerce.product.update']);
  await page.getByRole('button',{name:'查看资料',exact:true}).click();await page.getByRole('button',{name:'编辑商品资料',exact:true}).click();await page.getByLabel('商品名称',{exact:true}).fill('保留未保存输入');
  // 显式客户端异步实验：模态禁止点击背景；程序触发只读刷新测试组件保留，不冒充用户点击。
  const refreshed=page.waitForResponse(response=>new URL(response.url()).pathname==='/v1/operations/navigation');
  await page.evaluate(()=>Array.from(document.querySelectorAll('button')).find(button=>button.textContent==='刷新页面权限').click());assert.equal((await refreshed).status(),200);
  await expect(page.getByLabel('商品名称',{exact:true})).toHaveValue('保留未保存输入');await shot('multi-dirty-refresh-1440');record('real multiple capabilities keep menu set coherent; explicit programmatic readonly refresh preserves unsaved form (client lifecycle test)');
  await page.getByRole('dialog').locator('.ant-modal-close').click();await expect(page.getByRole('dialog',{name:'放弃未保存的商品修改？',exact:true})).toBeVisible();await page.getByRole('button',{name:'继续编辑',exact:true}).click();await expect(page.getByLabel('商品名称',{exact:true})).toHaveValue('保留未保存输入');await page.getByRole('dialog').locator('.ant-modal-close').click();await page.getByRole('button',{name:'放弃修改',exact:true}).click();await expect(page.getByRole('dialog')).toHaveCount(0);record('original dirty-close guard remains effective after navigation refresh');
 } else if(name===Phase.PENDING){
  await refresh();await expect(page.getByText('页面导航暂不可用',{exact:true})).toBeVisible();await expect(page.locator('nav a')).toHaveCount(0);await expect(page.getByRole('combobox',{name:'搜索中央功能',exact:true})).toHaveCount(0);assert.equal((await call('/resources/P1')).status(),503);await shot('pending-1440');record('real revoke before projection clears previously allowed menus and search; business read rejected 503');
 } else if(name===Phase.REVOKED){
  await refresh();await expect(page.getByText('当前组织暂无可访问页面',{exact:true})).toBeVisible();await expect(page.locator('nav a')).toHaveCount(0);assert.equal((await call('/resources/P1')).status(),403);await shot('revoked-1440');record('projected revoke shows NO_ACCESS; direct business read is denied despite earlier open page');
 } else if(name===Phase.OUTAGE){
  await refresh();await expect(page.getByText('页面导航暂不可用',{exact:true})).toBeVisible();await expect(page.locator('nav a')).toHaveCount(0);await page.setViewportSize({width:320,height:844});await overflow();await shot('outage-320');record('actual stopped Auth server produces explicit failure with zero old menus');
 } else if(name!==Phase.FINISH)throw new Error('unknown browser phase');
 assert.deepEqual(errors,[]);
}
let number=0;
try{
 while(true){
  const commandPath=path.join(root,'navigation-command.json');
  if(!fs.existsSync(commandPath)){await delay(100);continue;}
  const command=JSON.parse(fs.readFileSync(commandPath,'utf8'));
  if(command.number===number){await delay(100);continue;}
  number=command.number;
  try{await phase(command.phase);save(`navigation-result-${number}.json`,{status:'PASS',phase:command.phase,checks});}
  catch(error){await shot('failure-'+command.phase);save(`navigation-result-${number}.json`,{status:'FAIL',phase:command.phase,checks,error:error.message});throw error;}
  if(command.phase===Phase.FINISH)break;
 }
}finally{save('navigation-browser-checks.json',{checks,errors});await browser.close();}
