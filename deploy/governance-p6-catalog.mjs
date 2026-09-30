/** 只对P6工具创建的隔离数据库执行真实浏览器读改；无API模拟。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const { chromium, expect } = createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run = process.env.P6_RUN, phase = process.env.P6_PHASE;
const Phase = {ACTIVE:'active',REVOKED:'revoked',OUTAGE:'outage'};
assert(Object.values(Phase).includes(phase), 'unknown browser phase');
const f = JSON.parse(fs.readFileSync(path.join(run, 'catalog-ui.json'), 'utf8'));
const browser = await chromium.launch({headless: true});
const context = await browser.newContext({viewport: {width:1440,height:1000}});
await context.addInitScript(f => {
  const profile = JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
  if (sessionStorage.getItem(`oidc.user:${f.authority}:${f.client}`)) return;
  sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
}, f);
const page = await context.newPage();
const base = 'http://127.0.0.1:18665';
const url = `${base}/operations/catalog?tenant_id=${f.tenant}&store_id=${f.store}`;
const checks=[];
const shot = name => page.screenshot({path:path.join(run,`catalog-${name}.png`),fullPage:true,animations:'disabled'});
try {
  if (phase === Phase.ACTIVE) {
    const calls=[];
    page.on('request', r => { if(r.url().includes('/v1/')) calls.push(r); });
    await page.goto(url);
    await expect(page.getByText('P6 sku',{exact:true})).toBeVisible();
    await page.getByRole('tab',{name:'商品资料（SPU）'}).click();
    await page.getByRole('button',{name:'编辑资料',exact:true}).click();
    const modal=page.getByRole('dialog');
    await expect(modal).toBeVisible();
    await shot('edit');
    await modal.getByLabel('商品名称',{exact:true}).fill('P6 browser product');
    await modal.getByRole('button',{name:'确认提交',exact:true}).click();
    await expect(modal).toBeHidden();
    await expect(page.getByText('P6 browser product',{exact:true})).toBeVisible();
    assert(calls.length>0);
    for(const r of calls){assert.equal(r.headers()['x-tenant-id'],f.tenant);assert.equal(r.headers().authorization,'Bearer '+f.token);}
    assert(calls.every(r => !r.url().includes('/admin/') && !r.url().includes('/v1/me')));
    checks.push('real CATALOG page reads and edits with central bearer/tenant and no legacy identity');
    await shot('active');
    await page.getByLabel('门店编号',{exact:true}).fill(f.other);
    await page.getByRole('button',{name:'进入门店',exact:true}).click();
    await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();
    await expect(page.getByText('P6 browser product',{exact:true})).toHaveCount(0);
    checks.push('store switch clears prior rows and cross-store denial is visible');
    await shot('denied');
    await page.goto(url.replace(f.tenant,'00000000-0000-4000-8000-000000000000'));
    await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();
    checks.push('foreign tenant cannot reuse session authority');
    await page.goto(url); await expect(page.getByText('P6 sku',{exact:true})).toBeVisible();
    await page.setViewportSize({width:390,height:844}); await shot('390');
    await page.getByRole('button',{name:'退出登录',exact:true}).click();
    await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();
    checks.push('logout removes business UI and OIDC session');
    // 损坏Bearer只验证真实401恢复；不伪造后端成功响应。
    await page.evaluate(f=>{const key=`oidc.user:${f.authority}:${f.client}`;sessionStorage.setItem(key,JSON.stringify({access_token:'invalid',token_type:'Bearer',scope:'openid profile',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600}));},f);
    await page.goto(url);
    await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();
    checks.push('real 401 removes catalog content and exposes login recovery');
    await page.evaluate(f=>{const key=`oidc.user:${f.authority}:${f.client}`;const u=JSON.parse(sessionStorage.getItem(key));u.expires_at=1;sessionStorage.setItem(key,JSON.stringify(u));},f);
    await page.reload();
    await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();
    checks.push('expired OIDC session cannot render business content');
  } else {
    await page.goto(url);
    const message=phase===Phase.REVOKED?'当前身份没有此操作权限。':'授权服务暂不可用，请稍后重试';
    await expect(page.getByText(message,{exact:false}).first()).toBeVisible();
    await expect(page.getByText('P6 sku',{exact:true})).toHaveCount(0);
    await shot(phase);
    checks.push(`real ${phase} returns visible denial/unavailability without old credential fallback`);
  }
  fs.writeFileSync(path.join(run,`catalog-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
} catch(e){await shot(phase+'-failure');throw e;}
finally {await browser.close();}
