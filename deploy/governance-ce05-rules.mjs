/** 真实规则页恢复验收：丢弃已提交响应，不伪造授权或业务结果。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE;
const Phase={WRITE:'write-only',READ:'read',CREATE_REVOKED:'create-revoked',REVOKED:'revoked',OUTAGE:'outage'};
assert(Object.values(Phase).includes(phase));
const f=JSON.parse(fs.readFileSync(path.join(run,'catalog-ui.json'),'utf8'));
const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'UTC'});
if(!(phase===Phase.WRITE&&f.interactive))await context.addInitScript(f=>{
 const key=`oidc.user:${f.authority}:${f.client}`;if(sessionStorage.getItem(key))return;
 const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
 sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page=await context.newPage(),base='http://127.0.0.1:18665',url=`${base}/operations/rules?tenant_id=${f.tenant}`;
const checks=[],calls=[],uiId='ce05-rule:c';
const tab=name=>page.getByRole('tab',{name,exact:true}).click(),panel=name=>page.getByRole('tabpanel',{name,exact:true});
const shot=name=>page.screenshot({path:path.join(run,`rules-${name}.png`),fullPage:true,animations:'disabled'});
async function narrow(name){
 await page.setViewportSize({width:390,height:844});
 // 响应式Descriptions依赖ResizeObserver/React提交；等字体与布局帧后再取整页宽度。
 await page.evaluate(async()=>{await document.fonts.ready;await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));});
 await expect.poll(()=>page.evaluate(()=>Math.max(document.body.scrollWidth,document.body.offsetWidth,document.body.clientWidth,document.documentElement.scrollWidth,document.documentElement.offsetWidth,document.documentElement.clientWidth))).toBe(390);
 await shot('390-'+name);
 const width=await page.evaluate(()=>({viewport:innerWidth,document:document.documentElement.scrollWidth,body:document.body.scrollWidth,overflow:[...document.querySelectorAll('*')].filter(e=>e.getBoundingClientRect().right>innerWidth+1).map(e=>({tag:e.tagName,class:e.className,right:e.getBoundingClientRect().right})).slice(0,30)}));
 fs.writeFileSync(path.join(run,`rules-390-${name}-width.json`),JSON.stringify(width,null,2),{mode:0o600});
 assert.ok(width.document<=width.viewport&&width.body<=width.viewport,'rule page body must fit viewport');
 assert.equal(fs.readFileSync(path.join(run,`rules-390-${name}.png`)).readUInt32BE(16),390,'full screenshot must fit viewport');
 await page.setViewportSize({width:1440,height:1000});
}
async function target(p,id,version='1'){await p.getByLabel('规则编号',{exact:true}).fill(id);await p.getByLabel('规则版本',{exact:true}).fill(version);}
async function cancelLogout(){await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('dialog')).toBeVisible();await shot('leave-confirmation');await page.getByRole('dialog').getByRole('button',{name:'留在当前页',exact:true}).click();}
async function loseFirst(pattern){const attempts=[];await page.route(pattern,async route=>{attempts.push({key:route.request().headers()['idempotency-key'],body:route.request().postData(),url:route.request().url()});if(attempts.length===1){const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');}else await route.continue();});return attempts;}
try{
 page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});await page.goto(url);
 if(phase===Phase.WRITE&&f.interactive){await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);await page.getByRole('button',{name:'Sign In',exact:true}).click();await page.waitForURL(`${base}/operations/rules?**`,{timeout:30000});assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);assert.ok(!new URL(page.url()).searchParams.has('code'));checks.push('real PKCE returns to fixed rule page');}
 if(phase===Phase.WRITE){
  await expect(panel('规则目录').getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('创建规则');const c=panel('创建规则'),submit=c.getByRole('button',{name:'确认创建规则',exact:true});await expect(submit).toBeVisible();await submit.click();await expect(c.getByText('请输入正整数版本',{exact:true})).toBeVisible();await expect(c.getByText('请填写规则条件',{exact:true})).toBeVisible();
  await target(c,'ce05-rule:a');await c.getByLabel('规则名称',{exact:true}).fill('规则UI');await c.getByLabel('比较值',{exact:true}).fill('BASIC');await shot('create-form');await narrow('create-form');await submit.click();await expect(c.getByText('请核对规则编号和版本后，再提交操作',{exact:true})).toBeVisible();await expect(c.getByLabel('规则名称',{exact:true})).toHaveValue('规则UI');await shot('conflict');checks.push('create without read validates required fields and preserves editable input on real duplicate409');
  await target(c,uiId);await c.getByLabel('规则类型',{exact:true}).click();await page.locator('.ant-select-dropdown:visible').getByText('全部满足',{exact:true}).click();await c.getByLabel('比较值',{exact:true}).fill('BASIC');await c.getByRole('button',{name:'添加条件',exact:true}).click();await c.getByLabel('比较值',{exact:true}).nth(1).fill('BASIC');
  const invalid=page.waitForResponse(r=>r.url().endsWith('/v1/admin/rules')&&r.request().method()==='POST');await submit.click();assert.equal((await invalid).status(),400);await expect(c.getByText('请求未完成，请检查输入',{exact:true})).toBeVisible();await c.getByRole('button',{name:'移除此条件',exact:true}).last().click();checks.push('real trusted tree rejects duplicate conditions and allows correction');
  const createPattern='**/v1/admin/rules',created=await loseFirst(createPattern);await submit.click();await expect(c.getByText('操作结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await expect(c.getByLabel('规则编号',{exact:true})).toBeDisabled();assert.equal(await c.locator('[inert]').count(),1);await shot('create-unknown');
  await tab('发布规则');const p=panel('发布规则'),publish=p.getByRole('button',{name:'确认发布规则',exact:true});await expect(publish).toBeVisible();await publish.click();await expect(p.getByText('请输入正整数版本',{exact:true})).toBeVisible();await target(p,'ce05-rule:a');await shot('publish-form');await narrow('publish-form');await publish.click();await expect(p.getByText('发布规则成功',{exact:true})).toBeVisible();await expect(p.getByText('PUBLISHED',{exact:true})).toBeVisible();
  await cancelLogout();checks.push('independent publish of actual old version succeeds without read and preserves other unknown create intent');
  await tab('规则目录');await tab('创建规则');await c.getByRole('button',{name:'原样重试',exact:true}).click();await expect(c.getByText('创建规则成功',{exact:true})).toBeVisible();assert.equal(created.length,2);assert.ok(created[0].key);assert.deepEqual(created[0],created[1]);assert.equal(JSON.parse(created[0].body).rule.kind,'ALL');await page.unroute(createPattern);await shot('create-success');checks.push('create response loss retries exact key/body after tabs and canceled logout');
  await tab('发布规则');await target(p,uiId);const publishPattern='**/v1/admin/rules/*/*/publish',published=await loseFirst(publishPattern);await publish.click();await expect(p.getByText('操作结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await expect(p.getByLabel('规则编号',{exact:true})).toBeDisabled();await shot('publish-unknown');await cancelLogout();await tab('创建规则');await tab('发布规则');await p.getByRole('button',{name:'原样重试',exact:true}).click();await expect(p.getByText('发布规则成功',{exact:true})).toBeVisible();assert.equal(published.length,2);assert.ok(published[0].key);assert.deepEqual(published[0],published[1]);assert.equal(published[0].body,null);assert.ok(published[0].url.includes('ce05-rule%3Ac/1/publish'));await page.unroute(publishPattern);await shot('publish-success');checks.push('publish response loss retains original key/no-body/encoded actual version across tabs and canceled logout');
 }else if(phase===Phase.READ){
  const p=panel('规则目录');await expect(p.getByRole('cell',{name:uiId,exact:true})).toBeVisible();const a=p.getByRole('row').filter({has:page.getByRole('cell',{name:'ce05-rule:a',exact:true})});await expect(a.getByRole('cell',{name:'2',exact:true})).toBeVisible();await expect(a.getByRole('cell',{name:'DRAFT',exact:true})).toBeVisible();await expect(a.getByText('VIP',{exact:true})).toBeVisible();await expect(p.getByText('memberLevel',{exact:true})).toBeVisible();await expect(p.getByText('orderAmount',{exact:true})).toBeVisible();await expect(p.getByRole('button',{name:'下一批规则',exact:true})).toBeDisabled();await shot('directory');await narrow('directory');checks.push('directory displays latest draft and rule summary with protected trusted fields and terminal cursor');
 }else if(phase===Phase.CREATE_REVOKED||phase===Phase.REVOKED){
  await tab('创建规则');await expect(panel('创建规则').getByText('当前没有创建规则权限，可联系管理员申请',{exact:true})).toBeVisible();await tab('发布规则');const p=panel('发布规则');
  if(phase===Phase.CREATE_REVOKED){await expect(p.getByRole('button',{name:'确认发布规则',exact:true})).toBeVisible();checks.push('revoking create does not revoke independent publish hint');}
  else{
   await expect(p.getByText('当前没有发布规则权限，可联系管理员申请',{exact:true})).toBeVisible();await tab('规则目录');await expect(panel('规则目录').getByRole('cell',{name:uiId,exact:true})).toBeVisible();checks.push('both revoked write hints leave directory read intact');
   await page.goto(`${base}/operations/rules?tenant_id=00000000-0000-0000-0000-000000000000`);await expect(panel('规则目录').getByText('当前身份没有此操作权限。').first()).toBeVisible();await expect(page.getByText(uiId,{exact:true})).toHaveCount(0);checks.push('foreign tenant denied without retaining previous directory');await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();await page.evaluate(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600})),f);await page.goto(url);await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();await expect(page.getByRole('tab')).toHaveCount(0);checks.push('logout and actual401 unmount entire rule workspace');
  }
 }else{
  await expect(panel('规则目录').getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();for(const label of ['创建规则','发布规则']){await tab(label);await expect(panel(label).getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await expect(panel(label).getByRole('button',{name:'确认'+label,exact:true})).toHaveCount(0);}checks.push('actual central outage closes read and both action forms');
 }
 await shot(phase);for(const r of calls){assert.ok(r.headers().authorization?.startsWith('Bearer '));assert.ok([f.tenant,'00000000-0000-0000-0000-000000000000'].includes(r.headers()['x-tenant-id']));assert.match(new URL(r.url()).pathname,/^\/v1\/(admin\/(rules(?:\/[^/]+\/[1-9][0-9]*\/publish)?|rule-fields)|operations\/rules\/(create|publish)-access)$/);}
 fs.writeFileSync(path.join(run,`rules-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
}catch(error){await shot(phase+'-failure');throw error;}finally{await browser.close();}
