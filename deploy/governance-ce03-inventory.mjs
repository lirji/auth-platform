/** CE-03真实员工页；仅访问P6创建的隔离数据，响应丢失注入仍先提交真实业务请求。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const { chromium, expect } = createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run = process.env.P6_RUN, phase = process.env.P6_PHASE;
const Phase={READONLY:'readonly',WRITE:'write',REVOKED:'revoked-write',OUTAGE:'outage'};
assert(Object.values(Phase).includes(phase));
const f = JSON.parse(fs.readFileSync(path.join(run, 'catalog-ui.json'), 'utf8'));
const browser = await chromium.launch({headless:true});
const context = await browser.newContext({viewport:{width:1440,height:1000}});
if (!(phase === Phase.READONLY && f.interactive)) await context.addInitScript(f => {
  if (sessionStorage.getItem(`oidc.user:${f.authority}:${f.client}`)) return;
  const profile = JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
  sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`, JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page = await context.newPage();
const base = 'http://127.0.0.1:18665';
const url = `${base}/operations/inventory?tenant_id=${f.tenant}&store_id=${f.store}`;
const checks=[];
const shot = name => page.screenshot({path:path.join(run,`inventory-${name}.png`),fullPage:true,animations:'disabled'});
try {
  const calls=[];
  page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});
  await page.goto(url);
  if (phase === Phase.READONLY && f.interactive) {
    await page.getByRole('button',{name:'企业登录',exact:true}).click();
    await page.waitForURL(f.authority+'/**');
    await page.locator('#username').fill(f.user.name);
    await page.locator('#password').fill(f.user.password);
    await page.getByRole('button',{name:'Sign In',exact:true}).click();
    await page.waitForURL(base+'/operations/inventory?**',{timeout:30000});
    assert.equal(new URL(page.url()).searchParams.get('store_id'),f.store);
    assert.ok(!new URL(page.url()).searchParams.has('code'));
    checks.push('real password SSO returns to inventory with tenant/store and no authorization code');
  }
  if(phase===Phase.OUTAGE) {
    await expect(page.getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();
    await expect(page.getByText('p6-sku',{exact:true})).toHaveCount(0);
    await expect(page.getByRole('button',{name:'确认入库',exact:true})).toHaveCount(0);
    checks.push('real auth outage clears rows and receive form');
  } else if(phase===Phase.WRITE) {
    await expect(page.getByText('p6-sku',{exact:true})).toBeVisible();
    const submit=page.getByRole('button',{name:'确认入库',exact:true});
    await expect(submit).toBeVisible();
    await submit.click();
    await expect(page.getByText('请输入SKU标识',{exact:true})).toBeVisible();
    await page.getByLabel('SKU标识',{exact:true}).fill('p6-sku');
    await page.getByLabel('入库数量',{exact:true}).fill('3');
    await expect(page.getByText('请输入SKU标识',{exact:true})).toHaveCount(0);
    await expect(page.getByText('请输入入库数量',{exact:true})).toHaveCount(0);
    await shot('write-form');
    const keys=[];
    await page.route('**/v1/admin/inventory/receipts',async route=>{
      keys.push(route.request().headers()['idempotency-key']);
      if(keys.length===1){const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');}
      else await route.continue();
    });
    await submit.click();
    await expect(page.getByText('入库结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();
    await expect(page.getByLabel('SKU标识',{exact:true})).toBeDisabled();
    await shot('unknown-result');
    await page.getByRole('button',{name:'原样重试入库',exact:true}).click();
    await expect(page.getByText('库存已增加',{exact:true})).toBeVisible();
    await expect(page.getByRole('row').filter({hasText:'p6-sku'}).getByRole('cell').nth(1)).toHaveText('10');
    assert.equal(keys.length,2);assert.ok(keys[0]);assert.equal(keys[0],keys[1]);
    checks.push('validated real receipt; lost response freezes intent and same-key retry adds exactly three');
    await page.unroute('**/v1/admin/inventory/receipts');
    await page.getByLabel('SKU标识',{exact:true}).fill('unsaved');
    await page.getByLabel('门店编号',{exact:true}).fill(f.other);
    await page.getByRole('button',{name:'进入门店',exact:true}).click();
    const dialog=page.getByRole('dialog');await expect(dialog).toBeVisible();
    await dialog.getByRole('button',{name:'留在当前页',exact:true}).click();
    await expect(page.getByLabel('SKU标识',{exact:true})).toHaveValue('unsaved');
    checks.push('store change guards unsaved receipt and cancellation retains input');
    await page.getByRole('button',{name:'进入门店',exact:true}).click();
    await page.getByRole('dialog').getByRole('button',{name:'仍然离开',exact:true}).click();
    await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();
    await expect(page.getByText('p6-sku',{exact:true})).toHaveCount(0);
    checks.push('cross-store switch removes previous stock and denies writes');
  } else {
    await expect(page.getByText('当前可查看库存；入库需要另行授权',{exact:true})).toBeVisible();
    await expect(page.getByRole('button',{name:'确认入库',exact:true})).toHaveCount(0);
    if(phase===Phase.REVOKED) await expect(page.getByRole('row').filter({hasText:'p6-sku'}).getByRole('cell').nth(1)).toHaveText('10');
    checks.push(phase===Phase.READONLY?'read-only grant never renders receipt form':'write revocation hides receipt while independent read remains');
  }
  await shot(phase);
  if(phase===Phase.REVOKED) {
    await page.setViewportSize({width:390,height:844});await shot('390');
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    checks.push('390px viewport keeps document width bounded with table scroll');
    await page.getByRole('button',{name:'退出登录',exact:true}).click();
    await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();
    await page.evaluate(f=>{sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600}));},f);
    await page.goto(url);
    await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();
    await expect(page.getByText('p6-sku',{exact:true})).toHaveCount(0);
    checks.push('logout and real 401 remove stock and provide login recovery');
  }
  for(const r of calls){assert.equal(r.headers()['x-tenant-id'],f.tenant);assert.ok(r.headers().authorization?.startsWith('Bearer '));assert.ok(/^\/v1\/(admin\/inventory|operations\/inventory\/actions)(\?|\/|$)/.test(new URL(r.url()).pathname+'?'));}
  fs.writeFileSync(path.join(run,`inventory-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
} catch(error){await shot(phase+'-failure');throw error;}
finally {await browser.close();}
