/** 积分兑换商品页真实浏览器验收；仅丢弃已提交响应，不替代业务结果。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE;
const Phase={DEFINE:'define-only',STATUS:'status',READ:'read',REVOKED:'revoked',OUTAGE:'outage'};
assert(Object.values(Phase).includes(phase));
const f=JSON.parse(fs.readFileSync(path.join(run,'offers-ui.json'),'utf8'));
const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'UTC'});
if(!(phase===Phase.DEFINE && f.interactive))await context.addInitScript(f=>{
 const key=`oidc.user:${f.authority}:${f.client}`;if(sessionStorage.getItem(key))return;
 const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
 sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page=await context.newPage(),base='http://127.0.0.1:18665',url=`${base}/operations/point-offers?tenant_id=${f.tenant}`,offer='ce04-offer-ui-c';
const checks=[],calls=[];
const tab=name=>page.getByRole('tab',{name,exact:true}).click(),panel=name=>page.getByRole('tabpanel',{name,exact:true});
const shot=name=>page.screenshot({path:path.join(run,`offers-${name}.png`),fullPage:true,animations:'disabled'});
async function narrow(name){await page.setViewportSize({width:390,height:844});await shot('390-'+name);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.setViewportSize({width:1440,height:1000});}
async function directory(){await tab('兑换商品目录');const p=panel('兑换商品目录');await p.getByLabel('查询门店编号',{exact:true}).fill(f.store);await p.getByRole('button',{name:'查询兑换商品',exact:true}).click();return p;}
async function fillDefinition(p,id,name,kind,from,to){
 for(const [label,value] of [['积分商品编号',id],['门店编号',f.store],['商品名称',name],['资产编号',kind==='优惠券'?'ce04-offer-coupon':'ce04-offer-entitlement'],['资产版本','1'],['兑换积分','100'],['总兑换额度','5'],['单会员兑换上限','2'],['生效时间',from],['失效时间',to]])await p.getByLabel(label,{exact:true}).fill(String(value));
 await p.getByRole('radio',{name:kind,exact:true}).check();
}
const local=value=>new Date(value).toISOString().slice(0,16);
async function loseOnce(pattern){const keys=[],bodies=[];await page.route(pattern,async route=>{keys.push(route.request().headers()['idempotency-key']);bodies.push(route.request().postData());if(keys.length===1){const r=await route.fetch();assert.equal(r.status(),200);await route.abort('failed');}else await route.continue();});return ()=>{assert.equal(keys.length,2);assert.ok(keys[0]);assert.equal(keys[0],keys[1]);assert.equal(bodies[0],bodies[1]);return JSON.parse(bodies[0]);};}
async function retry(p,field,title,verify){await expect(p.getByText('操作结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await expect(p.getByLabel(field,{exact:true})).toBeDisabled();await shot(phase+'-unknown');await p.getByRole('button',{name:'原样重试',exact:true}).click();await expect(p.getByText(title+'成功',{exact:true})).toBeVisible();await expect(p.getByRole('button',{name:'确认'+title,exact:true})).toBeVisible();return verify();}
try{
 page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});await page.goto(url);
 if(phase===Phase.DEFINE && f.interactive){await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);await page.getByRole('button',{name:'Sign In',exact:true}).click();await page.waitForURL(base+'/operations/point-offers?**',{timeout:30000});assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);assert.ok(!new URL(page.url()).searchParams.has('code'));checks.push('real PKCE returns to point offers without callback code');}
 if(phase===Phase.DEFINE){
  const d=await directory();await expect(d.getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('定义兑换商品');const p=panel('定义兑换商品'),submit=p.getByRole('button',{name:'确认定义兑换商品',exact:true});await expect(submit).toBeVisible();for(const label of ['积分商品编号','门店编号','资产编号'])await expect(p.getByLabel(label,{exact:true})).toHaveAttribute('maxlength','64');await submit.click();await expect(p.getByText('请选择资产类型',{exact:true})).toBeVisible();
  await fillDefinition(p,offer,'积分券商品UI','优惠券',local(f.from),local(f.from));await submit.click();await expect(p.getByText('失效时间必须晚于生效时间',{exact:true})).toBeVisible();await p.getByLabel('失效时间',{exact:true}).fill(local(f.to));await shot('define-form');await narrow('define-form');
  const verify=await loseOnce('**/v1/admin/point-offers');await submit.click();await expect(p.getByText('操作结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await page.getByRole('button',{name:'退出登录',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:'留在当前页',exact:true}).click();await tab('兑换商品目录');await tab('定义兑换商品');const body=await retry(p,'积分商品编号','定义兑换商品',verify);assert.equal(body.kind,'COUPON');assert.equal(body.storeId,f.store);checks.push('define without read validates window, retains unknown input across tabs/logout cancel and retries original key');
  await page.unroute('**/v1/admin/point-offers');await fillDefinition(p,'ce04-offer-ui-d','积分权益商品UI','权益',local(f.futureFrom),local(f.futureTo));await submit.click();await expect(p.getByText('定义兑换商品成功',{exact:true})).toBeVisible();await expect(p.getByText('ce04-offer-ui-d',{exact:true})).toBeVisible();checks.push('both actual asset kinds use explicit definition fields and independent permission');
  await tab('停启兑换商品');await expect(panel('停启兑换商品').getByText('当前没有停启兑换商品权限，可联系管理员申请',{exact:true})).toBeVisible();checks.push('definition permission does not imply status');
 }else if(phase===Phase.STATUS){
  const d=await directory();await expect(d.getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('停启兑换商品');const p=panel('停启兑换商品'),submit=p.getByRole('button',{name:'确认停启兑换商品',exact:true});await expect(submit).toBeVisible();await p.getByLabel('积分商品编号',{exact:true}).fill(offer);await p.getByLabel('当前商品版本',{exact:true}).fill('8');await p.getByRole('radio',{name:'停用',exact:true}).check();await p.getByLabel('操作原因',{exact:true}).fill('积分兑换页面隔离停用');await submit.click();await expect(p.getByText('请核对最新版本和当前状态后，再提交操作',{exact:true})).toBeVisible();await expect(p.getByLabel('操作原因',{exact:true})).toHaveValue('积分兑换页面隔离停用');await shot('conflict');await p.getByLabel('当前商品版本',{exact:true}).fill('0');await shot('status-form');await narrow('status-form');
  const verify=await loseOnce(`**/v1/admin/point-offers/${offer}/status`);await submit.click();const body=await retry(p,'积分商品编号','停启兑换商品',verify);assert.equal(body.active,false);await expect(p.getByText('停用',{exact:true}).first()).toBeVisible();checks.push('status without read keeps real 409 inputs and retries explicit false with original key/body');
 }else if(phase===Phase.READ){
  const p=await directory();await expect(p.getByRole('row')).toHaveCount(5);await expect(p.getByRole('cell',{name:'积分券商品UI',exact:true})).toBeVisible();await expect(p.getByRole('cell',{name:'积分权益商品UI',exact:true})).toBeVisible();await expect(p.getByRole('button',{name:'下一批商品',exact:true})).toBeDisabled();await shot('directory');await narrow('directory');await p.getByLabel('查询门店编号',{exact:true}).fill(f.other);await p.getByRole('button',{name:'查询兑换商品',exact:true}).click();await expect(p.getByRole('cell',{name:'积分券商品UI',exact:true})).toHaveCount(0);await shot('other-store');checks.push('actual two-kind directory uses store filter and stable cursor and clears old results when switching store');
 }else if(phase===Phase.REVOKED){
  for(const title of ['定义兑换商品','停启兑换商品']){await tab(title);await expect(panel(title).getByText(`当前没有${title}权限，可联系管理员申请`,{exact:true})).toBeVisible();}const p=await directory();await expect(p.getByRole('cell',{name:'积分券商品UI',exact:true})).toBeVisible();checks.push('revoked writes leave independent directory read');await page.goto(`${base}/operations/point-offers?tenant_id=00000000-0000-0000-0000-000000000000`);const foreign=await directory();await expect(foreign.getByText('当前身份没有此操作权限。').first()).toBeVisible();await expect(page.getByText('积分券商品UI',{exact:true})).toHaveCount(0);checks.push('foreign tenant denies and clears prior directory');await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();await page.evaluate(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600})),f);await page.goto(url);await directory();await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();await expect(page.getByRole('tab')).toHaveCount(0);checks.push('logout and real 401 unmount offers workspace');
 }else{
  const p=await directory();await expect(p.getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await tab('定义兑换商品');await expect(panel('定义兑换商品').getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await expect(page.getByRole('button',{name:'确认定义兑换商品',exact:true})).toHaveCount(0);checks.push('actual central outage fails closed for directory and definition hint');
 }
 await shot(phase);for(const r of calls){assert.ok(r.headers().authorization?.startsWith('Bearer '));assert.ok([f.tenant,'00000000-0000-0000-0000-000000000000'].includes(r.headers()['x-tenant-id']));assert.match(new URL(r.url()).pathname,/^\/v1\/(admin\/point-offers(\/[A-Za-z0-9_-]{1,100}\/status)?|operations\/point-offers\/(define|status)-access)$/);}fs.writeFileSync(path.join(run,`offers-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
}catch(error){await shot(phase+'-failure');throw error;}finally{await browser.close();}
