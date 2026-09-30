/** 目录真实浏览器验收：仅操作P6自有租户，响应丢失在真实提交后注入。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const { chromium, expect } = createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE;
const Phase={READONLY:'readonly',WRITE:'write',REVOKED:'revoked-write',CREATE:'create-only',OUTAGE:'outage'};
assert(Object.values(Phase).includes(phase));
const f=JSON.parse(fs.readFileSync(path.join(run,'directory-ui.json'),'utf8'));
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000}});
if(!(phase===Phase.READONLY && f.interactive))await context.addInitScript(f=>{
  const key=`oidc.user:${f.authority}:${f.client}`;if(sessionStorage.getItem(key))return;
  const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
  sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page=await context.newPage(),base='http://127.0.0.1:18665';
const url=`${base}/operations/directory?tenant_id=${f.tenant}`;
const checks=[],calls=[];
const shot=name=>page.screenshot({path:path.join(run,`directory-${name}.png`),fullPage:true,animations:'disabled'});
const storeTab=()=>page.getByRole('tab',{name:'门店目录',exact:true}).click();
try{
  page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});
  await page.goto(url);
  if(phase===Phase.READONLY && f.interactive){
    await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');
    await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);
    await page.getByRole('button',{name:'Sign In',exact:true}).click();await page.waitForURL(base+'/operations/directory?**',{timeout:30000});
    assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);assert.ok(!new URL(page.url()).searchParams.has('code'));
    checks.push('real password PKCE returns to directory tenant with code removed');
  }
  if(phase===Phase.OUTAGE){
    await expect(page.getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();
    await expect(page.getByText(f.merchant,{exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'确认创建商家',exact:true})).toHaveCount(0);
    checks.push('real central outage removes directory and creation');
  }else if(phase===Phase.WRITE){
    const submit=page.getByRole('button',{name:'确认创建商家',exact:true});await expect(submit).toBeVisible();await submit.click();
    await expect(page.getByText('请输入商家名称',{exact:true})).toBeVisible();
    await page.getByLabel('商家编号',{exact:true}).fill('ce03-ui-merchant');await page.getByLabel('商家名称',{exact:true}).fill('目录验收商家');
    await expect(page.getByText('请输入商家名称',{exact:true})).toHaveCount(0);
    await expect(page.getByText('请输入商家编号',{exact:true})).toHaveCount(0);
    await shot('merchant-form');const keys=[];
    await page.route('**/v1/admin/merchants',async route=>{
      if(route.request().method()!=='POST')return route.continue();keys.push(route.request().headers()['idempotency-key']);
      if(keys.length===1){const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');}else await route.continue();
    });
    await submit.click();await expect(page.getByText('创建结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await expect(page.getByLabel('商家编号',{exact:true})).toBeDisabled();await shot('unknown');
    await page.getByRole('button',{name:'原样重试创建商家',exact:true}).click();await expect(page.getByText('商家已创建：ce03-ui-merchant',{exact:true})).toBeVisible();
    assert.equal(keys.length,2);assert.ok(keys[0]);assert.equal(keys[0],keys[1]);await page.unroute('**/v1/admin/merchants');
    checks.push('real merchant creation response loss freezes input and retries same key');
    await storeTab();await expect(page.getByRole('button',{name:'确认创建门店',exact:true})).toBeVisible();
    await page.getByLabel('门店编号',{exact:true}).fill('ce03-ui-store');await page.getByLabel('所属商家编号',{exact:true}).fill('ce03-ui-merchant');await page.getByLabel('门店名称',{exact:true}).fill('目录验收门店');
    await shot('store-form');await page.getByRole('button',{name:'确认创建门店',exact:true}).click();await expect(page.getByText('门店已创建：ce03-ui-store',{exact:true})).toBeVisible();
    checks.push('real store creation uses known actual merchant without extra read grant');
    await page.getByLabel('门店名称',{exact:true}).fill('未保存');await page.getByRole('button',{name:'退出登录',exact:true}).click();
    await expect(page.getByRole('dialog')).toBeVisible();await page.getByRole('dialog').getByRole('button',{name:'留在当前页',exact:true}).click();
    await expect(page.getByLabel('门店名称',{exact:true})).toHaveValue('未保存');
    await page.getByRole('tab',{name:'商家目录',exact:true}).click();await storeTab();await expect(page.getByLabel('门店名称',{exact:true})).toHaveValue('未保存');
    checks.push('logout cancellation and tab switching preserve unfinished input');
  }else if(phase===Phase.CREATE){
    await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();await storeTab();
    const panel=page.getByRole('tabpanel',{name:'门店目录',exact:true});await expect(panel.getByRole('button',{name:'确认创建门店',exact:true})).toBeVisible();
    await expect(panel.getByText('当前身份没有此操作权限。').first()).toBeVisible();
    await page.getByLabel('门店编号',{exact:true}).fill('ce03-ui-create-only');await page.getByLabel('所属商家编号',{exact:true}).fill('ce03-ui-merchant');await page.getByLabel('门店名称',{exact:true}).fill('仅创建岗位门店');
    await page.getByRole('button',{name:'确认创建门店',exact:true}).click();await expect(page.getByText('门店已创建：ce03-ui-create-only',{exact:true})).toBeVisible();
    checks.push('real store create-only capability works after both directory read grants revoked');
  }else{
    await expect(page.getByText(f.merchant,{exact:true})).toBeVisible();
    await expect(page.getByText('当前没有新建商家权限，可联系管理员申请',{exact:true})).toBeVisible();
    await expect(page.getByRole('button',{name:'确认创建商家',exact:true})).toHaveCount(0);await shot(phase+'-merchant');await storeTab();
    await expect(page.getByText(f.store,{exact:true})).toBeVisible();
    if(phase===Phase.READONLY)await expect(page.getByText('当前没有新建门店权限，可联系管理员申请',{exact:true})).toBeVisible();
    else await expect(page.getByRole('button',{name:'确认创建门店',exact:true})).toBeVisible();
    checks.push(phase===Phase.READONLY?'read-only merchant and store lists hide independent create forms':'merchant creation revoked while independent store creation remains');
  }
  await shot(phase);
  if(phase===Phase.REVOKED){
    await page.setViewportSize({width:390,height:844});await shot('390');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));checks.push('390px form and table keep document width bounded');
    await page.goto(`${base}/operations/directory?tenant_id=00000000-0000-0000-0000-000000000000`);
    await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();await expect(page.getByText(f.merchant,{exact:true})).toHaveCount(0);checks.push('foreign tenant denies directory and clears prior rows');
    await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();
    await page.evaluate(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600})),f);
    await page.goto(url);await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();await expect(page.getByRole('tab')).toHaveCount(0);checks.push('logout and real 401 remove business panels');
  }
  for(const r of calls){assert.ok(r.headers().authorization?.startsWith('Bearer '));assert.ok([f.tenant,'00000000-0000-0000-0000-000000000000'].includes(r.headers()['x-tenant-id']));assert.match(new URL(r.url()).pathname,/^\/v1\/(admin\/(merchants|stores)|operations\/directory\/(merchants|stores)\/create-access)$/);}
  fs.writeFileSync(path.join(run,`directory-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
}catch(error){await shot(phase+'-failure');throw error;}finally{await browser.close();}
