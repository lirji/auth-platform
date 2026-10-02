/** 人群页真实授权与恢复：仅丢弃已成功提交的响应，不伪造业务/授权结果。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE;
const Phase={WRITE:'write-only',READ:'read',REVOKED:'revoked',OUTAGE:'outage'};
assert(Object.values(Phase).includes(phase));
const f=JSON.parse(fs.readFileSync(path.join(run,'audiences-ui.json'),'utf8'));
const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'UTC'});
if(!(phase===Phase.WRITE&&f.interactive))await context.addInitScript(f=>{
 const key=`oidc.user:${f.authority}:${f.client}`;if(sessionStorage.getItem(key))return;
 const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
 sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page=await context.newPage(),base='http://127.0.0.1:18665',url=`${base}/operations/audiences?tenant_id=${f.tenant}`;
const checks=[],calls=[];
const tab=name=>page.getByRole('tab',{name,exact:true}).click(),panel=name=>page.getByRole('tabpanel',{name,exact:true});
const shot=async name=>{await page.evaluate(async()=>{window.scrollTo(0,0);await document.fonts.ready;await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));});return page.screenshot({path:path.join(run,`audiences-${name}.png`),fullPage:(await page.getByRole('dialog').count())===0,animations:'disabled'});};
async function narrow(name){
 await page.setViewportSize({width:390,height:844});
 await page.evaluate(async()=>{await document.fonts.ready;await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));});
 await expect.poll(()=>page.evaluate(()=>Math.max(document.body.scrollWidth,document.documentElement.scrollWidth))).toBe(390);
 await shot('390-'+name);assert.equal(fs.readFileSync(path.join(run,`audiences-390-${name}.png`)).readUInt32BE(16),390);
 await page.setViewportSize({width:1440,height:1000});
}
async function fill(p,id,members='ce05-ui-member\nce05-ui-not-member'){
 await p.getByLabel('人群编号',{exact:true}).fill(id);await p.getByLabel('快照版本',{exact:true}).fill('1');
 await p.getByLabel('人群名称',{exact:true}).fill('人群UI');await p.getByLabel('数据来源',{exact:true}).fill('isolated-ui-import');
 await p.getByLabel('数据时点',{exact:true}).fill(f.from.slice(0,16));await p.getByLabel('有效截止时间',{exact:true}).fill(f.to.slice(0,16));
 await p.getByLabel('成员编号（每行一个，可留空）',{exact:true}).fill(members);
}
try{
 page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});await page.goto(url);
 if(phase===Phase.WRITE&&f.interactive){await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);await page.getByRole('button',{name:'Sign In',exact:true}).click();await page.waitForURL(`${base}/operations/audiences?**`,{timeout:30000});assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);assert.ok(!new URL(page.url()).searchParams.has('code'));checks.push('real PKCE returns to fixed audience route');}
 if(phase===Phase.WRITE){
  await expect(panel('人群目录').getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('创建人群');const c=panel('创建人群'),submit=c.getByRole('button',{name:'确认创建人群',exact:true});await expect(submit).toBeVisible();await submit.click();await expect(c.getByText('请输入正安全整数版本',{exact:true})).toBeVisible();checks.push('independent create without read and required fields');
  await fill(c,'ce05-audience:a','M1\nM1');await submit.click();await expect(c.getByText('成员编号重复，请纠正输入',{exact:true})).toBeVisible();
  await c.getByLabel('成员编号（每行一个，可留空）',{exact:true}).fill(Array.from({length:501},(_,i)=>'M'+i).join('\n'));await submit.click();await expect(c.getByText('每个快照最多500个成员编号',{exact:true})).toBeVisible();
  await c.getByLabel('成员编号（每行一个，可留空）',{exact:true}).fill('M1');await c.getByLabel('数据时点',{exact:true}).fill(f.future.slice(0,16));await submit.click();await expect(c.getByText('数据时点不能晚于当前时间',{exact:true})).toBeVisible();
  await c.getByLabel('数据时点',{exact:true}).fill(f.from.slice(0,16));await c.getByLabel('有效截止时间',{exact:true}).fill(f.wide.slice(0,16));await submit.click();await expect(c.getByText('截止时间须晚于数据时点且窗口不超过24小时',{exact:true})).toBeVisible();checks.push('duplicates member bound and original freshness window validated without deduplication');
  await c.getByLabel('有效截止时间',{exact:true}).fill(f.to.slice(0,16));await shot('create-form');await narrow('create-form');await submit.click();await expect(c.getByText('该人群版本已存在，请核对编号和版本后纠正输入',{exact:true})).toBeVisible();await expect(c.getByLabel('人群名称',{exact:true})).toHaveValue('人群UI');await shot('conflict');checks.push('real immutable409 preserves editable input');
  await fill(c,'ce05-audience:c');const attempts=[],pattern='**/v1/admin/audiences';await page.route(pattern,async route=>{attempts.push({key:route.request().headers()['idempotency-key'],body:route.request().postData()});if(attempts.length===1){const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');}else await route.continue();});
  await submit.click();await expect(c.getByText('操作结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await expect(c.getByLabel('人群编号',{exact:true})).toBeDisabled();await shot('unknown');await tab('人群目录');await tab('创建人群');
  await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('dialog')).toBeVisible();await shot('leave-confirmation');await narrow('leave-confirmation');await page.getByRole('dialog').getByRole('button',{name:'留在当前页',exact:true}).click();await c.getByRole('button',{name:'原样重试创建',exact:true}).click();await expect(c.getByText('人群快照已创建',{exact:true})).toBeVisible();assert.equal(attempts.length,2);assert.ok(attempts[0].key);assert.deepEqual(attempts[0],attempts[1]);await page.unroute(pattern);await shot('success');checks.push('exact key body survives lost committed response tabs and canceled logout');
  await fill(c,'ce05-audience:d','');await c.getByRole('button',{name:'确认创建人群',exact:true}).click();await expect(c.getByText('ce05-audience:d',{exact:true})).toBeVisible();await expect(c.getByText('0',{exact:true})).toBeVisible();checks.push('empty import confirms actual zero members');
 }else if(phase===Phase.READ){
  const p=panel('人群目录');await expect(p.getByText('ce05-audience:c',{exact:true})).toBeVisible();await expect(p.getByText('ce05-audience:d',{exact:true})).toBeVisible();await expect(p.getByRole('button',{name:'下一页',exact:true})).toBeDisabled();await shot('directory');await narrow('directory');checks.push('latest actual summaries terminal cursor and 1440 390 layout');
 }else if(phase===Phase.REVOKED){
  await expect(panel('人群目录').getByText('ce05-audience:c',{exact:true})).toBeVisible();await tab('创建人群');await expect(panel('创建人群').getByText('当前未获人群创建权限；目录读取权限独立授予。',{exact:true})).toBeVisible();await expect(panel('创建人群').getByRole('button',{name:'确认创建人群',exact:true})).toHaveCount(0);checks.push('revoked creation does not revoke directory and hides write controls');
  await page.goto(`${base}/operations/audiences?tenant_id=00000000-0000-0000-0000-000000000000`);await expect(panel('人群目录').getByText('当前身份没有此操作权限。').first()).toBeVisible();await expect(page.getByText('ce05-audience:c',{exact:true})).toHaveCount(0);checks.push('foreign tenant unloads previous summaries');
  await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();await page.evaluate(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600})),f);await page.goto(url);await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();await expect(page.getByRole('tab')).toHaveCount(0);checks.push('actual logout and401 unload workspace');
 }else{
  await expect(panel('人群目录').getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await tab('创建人群');await expect(panel('创建人群').getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await expect(panel('创建人群').getByRole('button',{name:'确认创建人群',exact:true})).toHaveCount(0);checks.push('actual central503 hides directory and creation controls');
 }
 await shot(phase);for(const r of calls){assert.ok(r.headers().authorization?.startsWith('Bearer '));assert.ok([f.tenant,'00000000-0000-0000-0000-000000000000'].includes(r.headers()['x-tenant-id']));assert.match(new URL(r.url()).pathname,/^\/v1\/(admin\/audiences|operations\/audiences\/create-access)$/);}
 fs.writeFileSync(path.join(run,`audiences-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
}catch(error){await shot(phase+'-failure');throw error;}finally{await browser.close();}
