/** 活动真实角色/PKCE/原键恢复；只丢弃实际200响应，所有业务和判权均调用隔离后端。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE;
const Phase={CREATE:'create-only',SUBMIT:'submit-only',PREVIEW:'preview-only',REVIEW:'reviewer',PUBLISH:'publisher',READ:'read-only',BUDGET:'budget-only',REVOKED:'revoked',OUTAGE:'outage'};
assert(Object.values(Phase).includes(phase));
const f=JSON.parse(fs.readFileSync(path.join(run,'campaigns-ui.json'),'utf8'));
const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'UTC'});
if(!(phase===Phase.CREATE&&f.interactive))await context.addInitScript(f=>{
 const key=`oidc.user:${f.authority}:${f.client}`;
 // 保留测试主动替换的无效会话，避免导航初始化掩盖真实401拒绝。
 if(sessionStorage.getItem(key))return;
 const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
 sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page=await context.newPage(),base='http://127.0.0.1:18665',url=`${base}/operations/campaigns?tenant_id=${f.tenant}`;
const checks=[],calls=[],errors=[];
const tab=name=>page.getByRole('tab',{name,exact:true}).click(),panel=name=>page.getByRole('tabpanel',{name,exact:true});
const dialog=()=>page.getByRole('dialog').last(),footer=()=>dialog().locator('.ant-modal-footer');
const shot=async name=>{
 await page.evaluate(async()=>{await document.fonts.ready;await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));});
 await page.waitForTimeout(200);
 return page.screenshot({path:path.join(run,`campaigns-${name}.png`),fullPage:(await page.getByRole('dialog').count())===0,animations:'disabled'});
};
async function narrow(name,width=390,focus){
 await page.setViewportSize({width,height:844});
 // 视口切换会重排长正文；截图前定位实际结果行，不能用文件名冒充手机来源可见。
 if(focus)await dialog().getByText(focus,{exact:true}).scrollIntoViewIfNeeded();
 await expect.poll(()=>page.evaluate(()=>Math.max(document.body.scrollWidth,document.documentElement.scrollWidth))).toBe(width);
 await shot(`${width}-${name}`);await page.setViewportSize({width:1440,height:1000});
}
async function choose(action){
 const p=panel('版本操作'),select=p.getByLabel('本次操作',{exact:true});await select.click();
 await page.locator('.ant-select-item-option-content').getByText(action,{exact:true}).click();
}
async function target(id,lock){
 const p=panel('版本操作');await p.getByLabel('实际活动编号',{exact:true}).fill(id);await p.getByLabel('内容版本（不可变）',{exact:true}).fill('1');
 if(lock!==undefined)await p.getByLabel('状态锁版本（expectedVersion）',{exact:true}).fill(String(lock));
}
async function action(name,id,lock,status,next){
 await choose(name);await target(id,lock);const p=panel('版本操作');await p.getByRole('button',{name,exact:true}).click();await expect(dialog()).toBeVisible();await shot(`${phase}-${name}-confirm`);await narrow(`${phase}-${name}-confirm`);
 await footer().getByRole('button',{name:`确认${name}`,exact:true}).click();
 await expect(p.getByText('操作结果已确认',{exact:true})).toBeVisible();await expect(p.getByText(status,{exact:true})).toBeVisible();
 await expect(p.getByLabel('状态锁版本（expectedVersion）',{exact:true})).toHaveValue(String(next));
}
async function fillDraft(id,audience=false){
 const d=dialog();await d.getByLabel('活动编号',{exact:true}).fill(id);await d.getByLabel('内容版本（不可变）',{exact:true}).fill('1');
 await d.getByLabel('实际门店编号',{exact:true}).fill(f.store);await d.getByLabel('活动名称',{exact:true}).fill('CAM2真实角色活动');
 await d.getByLabel('开始时间',{exact:true}).fill(f.from.slice(0,16));await d.getByLabel('结束时间',{exact:true}).fill(f.to.slice(0,16));
 await d.getByLabel('消费门槛（元）',{exact:true}).fill('1.00');await d.getByLabel('优惠金额 / 比例折扣上限（元）',{exact:true}).fill('1.00');
 await d.getByRole('checkbox',{name:'仅使用固定已发布规则',exact:true}).check();await d.getByLabel('固定规则编号（可选）',{exact:true}).fill('ce05-rule:a');await d.getByLabel('固定规则版本',{exact:true}).fill('1');
 if(audience){await d.getByLabel('固定人群编号（可选）',{exact:true}).fill('ce05-audience:a');await d.getByLabel('固定人群版本',{exact:true}).fill('1');}
 await d.getByLabel('平台资方比例（万分比）',{exact:true}).fill('2500');await d.getByLabel('预算上限（元，可留空）',{exact:true}).fill('20.00');
}
try{
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});
 await page.goto(phase===Phase.BUDGET||phase===Phase.OUTAGE?`${base}/operations/campaign-budgets?tenant_id=${f.tenant}`:url);
 if(phase===Phase.CREATE&&f.interactive){
  await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');
  await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);await page.getByRole('button',{name:'Sign In',exact:true}).click();
  await page.waitForURL(`${base}/operations/campaigns?**`,{timeout:30000});assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);assert.ok(!new URL(page.url()).searchParams.has('code'));checks.push('real PKCE fixed campaign return');
 }
 if(phase===Phase.CREATE){
  await expect(panel('活动目录').getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('创建草稿');
  await panel('创建草稿').getByRole('button',{name:'创建活动草稿',exact:true}).click();await expect(dialog()).toBeVisible();
  await footer().getByRole('button',{name:'保存活动草稿',exact:true}).click();await expect(dialog().getByText('请输入1至64位字母、数字、下划线、点、冒号或连字符').first()).toBeVisible();
  await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).toHaveCount(0);await expect(panel('创建草稿').getByRole('button',{name:'创建活动草稿',exact:true})).toBeFocused();
  await panel('创建草稿').getByRole('button',{name:'创建活动草稿',exact:true}).click();
  await fillDraft('ce05-campaign:a');await dialog().getByLabel('内容版本（不可变）',{exact:true}).fill('7');await shot('create-structured');await narrow('create-structured');await narrow('create-structured',320);
  await footer().getByRole('button',{name:'取消',exact:true}).click();await expect(dialog().locator('.ant-modal-confirm-title').filter({hasText:'放弃未保存的活动草稿？'})).toBeVisible();await shot('dirty-confirm');await dialog().getByRole('button',{name:'继续编辑',exact:true}).click();
  await footer().getByRole('button',{name:'保存活动草稿',exact:true}).click();await expect(dialog().getByText('状态或幂等冲突，请刷新核对后重试。').first()).toBeVisible();await shot('create-real409');
  await footer().getByRole('button',{name:'重新核验创建权限',exact:true}).click();await expect(dialog().getByLabel('活动编号',{exact:true})).toBeEnabled();await fillDraft('ce05-campaign:ui-a');
  const attempts=[],pattern='**/v1/admin/campaigns';
  await page.route(pattern,async route=>{attempts.push({key:route.request().headers()['idempotency-key'],body:route.request().postData()});if(attempts.length===1){const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');}else await route.continue();});
  await footer().getByRole('button',{name:'保存活动草稿',exact:true}).click();await expect(dialog().getByText('操作结果尚未确认，请保留原意图重试',{exact:true})).toBeVisible();await shot('create-lost-committed-response');
  await footer().getByRole('button',{name:'返回页签（保留原意图）',exact:true}).click();await dialog().getByRole('button',{name:'保留并返回',exact:true}).click();await tab('活动目录');await tab('创建草稿');
  await page.getByRole('button',{name:'退出登录',exact:true}).click();await shot('unknown-leave-confirm');await dialog().getByRole('button',{name:'留在当前页',exact:true}).click();
  await panel('创建草稿').getByRole('button',{name:'重新核验创建草稿权限',exact:true}).click();await panel('创建草稿').getByRole('button',{name:'恢复原创建意图',exact:true}).click();
  await expect(dialog().getByLabel('活动编号',{exact:true})).toBeDisabled();await footer().getByRole('button',{name:'原样重试创建',exact:true}).click();await expect(panel('创建草稿').getByText('操作结果已确认',{exact:true})).toBeVisible();
  assert.equal(attempts.length,2);assert.ok(attempts[0].key);assert.deepEqual(attempts[0],attempts[1]);await page.unroute(pattern);await shot('create-real-success');
  await panel('创建草稿').getByRole('button',{name:'创建活动草稿',exact:true}).click();await fillDraft('ce05-campaign:ui-b',true);await footer().getByRole('button',{name:'保存活动草稿',exact:true}).click();await expect(panel('创建草稿').getByText('CAM2真实角色活动 · ce05-campaign:ui-b',{exact:true})).toBeVisible();
  checks.push('create-only lacks directory and preview; required fields, Escape close and focus restore, actual fixed assets, real409, lost committed response exact key body across tabs canceled logout, two immutable drafts');
 }else if(phase===Phase.SUBMIT){
  await expect(panel('活动目录').getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('版本操作');
  await action('提交审批','ce05-campaign:ui-a',0,'IN_REVIEW',1);await action('提交审批','ce05-campaign:ui-b',0,'IN_REVIEW',1);checks.push('submit-only actual DRAFT to IN_REVIEW without read');
 }else if(phase===Phase.PREVIEW){
  await expect(panel('活动目录').getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('版本操作');await target('ce05-campaign:ui-a');await panel('版本操作').getByRole('button',{name:'只读预览',exact:true}).click();
  await dialog().getByLabel('实际会员编号',{exact:true}).fill(f.member);await dialog().getByLabel('实际SKU编号',{exact:true}).fill('p6-sku');await dialog().getByLabel('数量',{exact:true}).fill('1');
  await footer().getByRole('button',{name:'运行只读预览',exact:true}).click();await expect(dialog().getByText('实际预览结果',{exact:true})).toBeVisible();
  await dialog().getByText('实际预览结果',{exact:true}).scrollIntoViewIfNeeded();await shot('preview-actual-result');await narrow('preview-result');await footer().getByRole('button',{name:'关闭',exact:true}).click();
  await target('ce05-campaign:ui-b');await panel('版本操作').getByRole('button',{name:'只读预览',exact:true}).click();await dialog().getByLabel('实际会员编号',{exact:true}).fill('ce04-cycle-member');
  await footer().getByRole('button',{name:'运行只读预览',exact:true}).click();await expect(dialog().getByText('ce05-audience:a',{exact:true})).toBeVisible();await dialog().getByText('固定人群来源与新鲜度',{exact:true}).scrollIntoViewIfNeeded();await shot('preview-actual-sources');await narrow('preview-sources',390,'ce05-audience:a');checks.push('preview-only actual member SKU facts trace and immutable audience source, no read');
 }else if(phase===Phase.REVIEW){
  await tab('版本操作');await choose('审批通过');await target('ce05-campaign:ui-a',0);await panel('版本操作').getByRole('button',{name:'审批通过',exact:true}).click();await footer().getByRole('button',{name:'确认审批通过',exact:true}).click();
  await expect(dialog().getByText('状态或幂等冲突，请刷新核对后重试。').first()).toBeVisible();await shot('review-real409');await footer().getByRole('button',{name:'关闭',exact:true}).click();
  await panel('版本操作').getByRole('button',{name:'重新核验审批通过权限',exact:true}).click();await action('审批通过','ce05-campaign:ui-a',1,'APPROVED',2);await action('审批拒绝','ce05-campaign:ui-b',1,'REJECTED',2);checks.push('reviewer approve and reject independently, real409 editable target and actual lock receipts');
 }else if(phase===Phase.PUBLISH){
  await tab('版本操作');await action('发布活动','ce05-campaign:ui-a',2,'PUBLISHED',3);await action('暂停活动','ce05-campaign:ui-a',3,'PAUSED',4);checks.push('publisher actual publication then pause immutable content1 lock3/4');
 }else if(phase===Phase.READ){
  await expect(panel('活动目录').getByText('ce05-campaign:ui-a',{exact:true})).toBeVisible();await expect(panel('活动目录').getByText('PAUSED',{exact:true})).toBeVisible();await expect(panel('活动目录').getByRole('button',{name:'下一页',exact:true})).toBeDisabled();await shot('directory-actual');await narrow('directory-actual');
  await tab('创建草稿');await expect(panel('创建草稿').getByRole('button',{name:'创建活动草稿',exact:true})).toHaveCount(0);checks.push('read-only latest actual directory without creation or budget');
 }else if(phase===Phase.BUDGET){
  await expect(page.getByRole('cell',{name:'ce05-campaign:ui-a',exact:true})).toBeVisible();await shot('budget-actual');await narrow('budget-actual');assert.ok(calls.every(r=>new URL(r.url()).pathname==='/v1/admin/campaign-budgets'));checks.push('budget-only actual all versions independent of directory and no other endpoint');
 }else if(phase===Phase.REVOKED){
  await expect(panel('活动目录').getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('创建草稿');await expect(panel('创建草稿').getByRole('button',{name:'创建活动草稿',exact:true})).toHaveCount(0);await shot('all-campaign-grants-revoked');
  await page.goto(`${base}/operations/campaigns?tenant_id=00000000-0000-0000-0000-000000000000`);await expect(panel('活动目录').getByText('当前身份没有此操作权限。').first()).toBeVisible();await expect(page.getByText('ce05-campaign:ui-a',{exact:true})).toHaveCount(0);
  await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();
  await page.evaluate(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600})),f);await page.goto(url);await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();await expect(page.getByRole('tab')).toHaveCount(0);await shot('actual401-unloaded');checks.push('actual write revoke directory deny foreign tenant and401 remove sensitive workspace');
 }else{
  await expect(page.getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await shot('budget-real503');await page.goto(url);await expect(panel('活动目录').getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await tab('创建草稿');await expect(panel('创建草稿').getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await expect(panel('创建草稿').getByRole('button',{name:'创建活动草稿',exact:true})).toHaveCount(0);checks.push('actual stopped central503 budget directory creation fail closed');
 }
 assert.deepEqual(errors,[]);
 for(const r of calls){assert.ok(r.headers().authorization?.startsWith('Bearer '));assert.ok([f.tenant,'00000000-0000-0000-0000-000000000000'].includes(r.headers()['x-tenant-id']));assert.match(new URL(r.url()).pathname,/^\/v1\/(admin\/(campaigns(\/[^/]+\/[1-9][0-9]*\/(preview|submit|approve|reject|publish|pause))?|campaign-budgets)|operations\/campaigns\/(create|preview|submit|approve|reject|publish|pause)-access)$/);}
 await shot(phase);fs.writeFileSync(path.join(run,`campaigns-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
}catch(error){await shot(phase+'-failure');throw error;}finally{await browser.close();}
