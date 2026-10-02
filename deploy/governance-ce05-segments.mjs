/** 六个真实单能力岗位、实际PKCE与最终JAR；只丢弃真实200响应，不伪造业务或判权。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
const harnessSha256=createHash('sha256').update(fs.readFileSync(new URL(import.meta.url))).digest('hex');
import {createRequire} from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE;
const Phase={CREATE:'create-only',SCHEDULE:'schedule-only',REFRESH:'refresh-only',PUMP:'pump-only',CONTROL:'control-only',READ:'read-only',REVOKED:'revoked',OUTAGE:'outage'};
// S1保留两个已证明拒绝的旧来源，加上已撤权UI手工任务，均可能先被本轮有界调度选中。
const MAX_CONFIRMED_PUMP_CALLS=4;
const DIALOG_CENTER_TOLERANCE_PX=24;
// 仅测不溢出不足以发现Alert动作挤成竖排；正文应占窄屏大部分可用宽度。
const MIN_NOTICE_WIDTH_RATIO=0.55;
assert(Object.values(Phase).includes(phase));
const f=JSON.parse(fs.readFileSync(path.join(run,'segments-ui.json'),'utf8'));
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'UTC'});
if(!(phase===Phase.CREATE&&f.interactive))await context.addInitScript(f=>{
 const key=`oidc.user:${f.authority}:${f.client}`;
 if(sessionStorage.getItem(key))return;
 const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
 sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page=await context.newPage(),base='http://127.0.0.1:18665',url=`${base}/operations/segments?tenant_id=${f.tenant}`;
const calls=[],checks=[],errors=[];
const tab=name=>page.getByRole('tab',{name,exact:true}).click();
const panel=name=>page.getByRole('tabpanel',{name,exact:true});
const dialog=()=>page.getByRole('dialog').last();
const footer=()=>dialog().locator('.ant-modal-footer');
async function centeredDialog(){
 await expect(dialog()).toBeVisible();
 await expect.poll(async()=>{
  const box=await dialog().boundingBox();
  return box?Math.abs(box.y+box.height/2-page.viewportSize().height/2):Infinity;
 }).toBeLessThanOrEqual(DIALOG_CENTER_TOLERANCE_PX);
}
async function shot(name){
 await page.evaluate(async()=>{await document.fonts.ready;await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));});
 if(await page.getByRole('dialog').count())await centeredDialog();
 await page.screenshot({path:path.join(run,`segments-${phase}-${name}.png`),fullPage:false,animations:'disabled'});
}
async function narrow(name,width=390){
 await page.setViewportSize({width,height:844});
 await expect.poll(()=>page.evaluate(()=>Math.max(document.body.scrollWidth,document.documentElement.scrollWidth))).toBe(width);
 // AntD确认框的按钮在正文内，不能假定存在业务表单的footer容器。
 if(await page.getByRole('dialog').count())await expect(dialog().getByRole('button').last()).toBeInViewport();
 await shot(`${name}-${width}`);await page.setViewportSize({width:1440,height:1000});
}
async function open(name){await tab('独立操作');await tab(name);await panel(name).getByRole('button',{name,exact:true}).click();await expect(dialog()).toBeVisible();}
async function fillDefinition(id){
 await dialog().getByLabel('定义编号',{exact:true}).fill(id);
 await dialog().getByLabel('定义版本（不可变）',{exact:true}).fill('7');
 await dialog().getByLabel('人群名称',{exact:true}).fill('S2真实岗位动态人群');
 await dialog().getByRole('textbox',{name:'比较值',exact:true}).fill('BASIC');
 await dialog().getByLabel('刷新周期（秒，0表示仅手动）',{exact:true}).fill('60');
}
async function lostResponse(pattern,submit,recheck){
 const attempts=[];
 await page.route(pattern,async route=>{
  attempts.push({path:new URL(route.request().url()).pathname,key:route.request().headers()['idempotency-key'],body:route.request().postData()});
  if(attempts.length===1){const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');}
  else await route.continue();
 });
 await footer().getByRole('button',{name:submit,exact:true}).click();
 await expect(dialog().getByText('操作结果尚未确认，请保留原意图重试',{exact:true})).toBeVisible();await shot('lost-committed-response');await narrow('lost-committed-response');await narrow('lost-committed-response',320);
 await footer().getByRole('button',{name:'返回页签（保留原意图）',exact:true}).click();
 await centeredDialog();
 await dialog().getByRole('button',{name:'保留并返回',exact:true}).click();
 await page.getByRole('button',{name:'退出登录',exact:true}).click();
 await centeredDialog();
 await dialog().getByRole('button',{name:'留在当前页',exact:true}).click();
 await panel(recheck).getByRole('button',{name:`重新核验${recheck}权限`,exact:true}).click();
 await panel(recheck).getByRole('button',{name:'恢复原操作意图',exact:true}).click();
 await footer().getByRole('button',{name:'原样重试操作',exact:true}).click();
 await expect.poll(()=>attempts.length).toBe(2);assert.deepEqual(attempts[1],attempts[0]);assert.ok(attempts[0].key);
 await page.unroute(pattern);checks.push('real committed response lost and exact original key/body/target replay');
}
try{
 page.on('pageerror',e=>errors.push(e.message));
 page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});
 await page.goto(url);
 if(phase===Phase.CREATE&&f.interactive){
  await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');
  await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);await page.getByRole('button',{name:'Sign In',exact:true}).click();
  await page.waitForURL(`${base}/operations/segments?**`,{timeout:30000});assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);assert.ok(!new URL(page.url()).searchParams.has('code'));checks.push('real PKCE fixed segment return');
 }
 if(phase!==Phase.READ&&phase!==Phase.OUTAGE)await expect(panel('定义与运行记录').getByText('当前身份没有此操作权限。').first()).toBeVisible();
 if(phase===Phase.CREATE){
  await tab('创建定义');await panel('创建定义').getByRole('button',{name:'创建定义',exact:true}).click();
  await footer().getByRole('button',{name:'确认创建定义',exact:true}).click();await expect(dialog().getByText('请输入实际编号',{exact:true})).toBeVisible();
  await fillDefinition(f.segment);await shot('form-1440');await narrow('form');await narrow('form',320);
  await page.keyboard.press('Escape');await expect(dialog().locator('.ant-modal-confirm-title')).toHaveText('放弃未提交的动态人群输入？');await shot('dirty-close');await narrow('dirty-close');await narrow('dirty-close',320);
  await dialog().getByRole('button',{name:'继续编辑',exact:true}).click();
  await lostResponse('**/v1/admin/segments','确认创建定义','创建定义');
  await expect(panel('创建定义').getByText('定义或调度结果已确认',{exact:true})).toBeVisible();
  await panel('创建定义').getByRole('button',{name:'创建定义',exact:true}).click();await fillDefinition(f.manual);
  await footer().getByRole('button',{name:'确认创建定义',exact:true}).click();await expect(page.getByRole('dialog')).toHaveCount(0);
  checks.push('two actual immutable definitions created without read/member/rule permission');
 }else if(phase===Phase.SCHEDULE){
  await open('调整调度');await dialog().getByLabel('实际定义编号',{exact:true}).fill(f.segment);await dialog().getByLabel('调度锁版本',{exact:true}).fill('1');await dialog().getByRole('switch').click();
  await shot('form-1440');await narrow('form');await narrow('form',320);
  await footer().getByRole('button',{name:'确认调整调度',exact:true}).click();await expect(panel('调整调度').getByText('目标版本或任务状态冲突',{exact:true})).toBeVisible();await shot('real409');await narrow('real409');await narrow('real409',320);
  await footer().getByRole('button',{name:'重新核验权限',exact:true}).click();await expect(dialog().getByLabel('调度锁版本',{exact:true})).toBeEnabled();await dialog().getByLabel('调度锁版本',{exact:true}).fill('0');
  await footer().getByRole('button',{name:'确认调整调度',exact:true}).click();await expect(panel('调整调度').getByText('定义或调度结果已确认',{exact:true})).toBeVisible();
  checks.push('real schedule CAS conflict corrected to actual lock without read');
 }else if(phase===Phase.REFRESH){
  await open('发起刷新');await dialog().getByLabel('实际定义编号',{exact:true}).fill(f.manual);await shot('form-1440');await narrow('form');await narrow('form',320);
  await lostResponse(`**/v1/admin/segments/${f.manual}/refresh`,'确认发起刷新','发起刷新');
  await expect(panel('发起刷新').getByText('任务回执已确认',{exact:true})).toBeVisible();await expect(panel('发起刷新').getByText('RUNNING',{exact:true})).toBeVisible();await shot('actual-running-receipt');await narrow('actual-running-receipt');await narrow('actual-running-receipt',320);
  checks.push('actual refresh receipt does not claim completed snapshot');
 }else if(phase===Phase.PUMP){
  await tab('独立操作');await tab('单次推进');const attempts=[];
  const pattern='**/v1/admin/segments/pump';await page.route(pattern,async route=>{
   attempts.push(route.request().headers());const response=await route.fetch();assert.equal(response.status(),200);assert.equal(await response.json(),0);await route.abort('failed');
  });
  await panel('单次推进').getByRole('button',{name:'执行单次推进',exact:true}).click();await expect(panel('单次推进').getByText('本次推进结果未知，可能已提交部分批次',{exact:true})).toBeVisible();
  await panel('单次推进').getByRole('button',{name:'重新核验单次推进权限',exact:true}).click();await expect(panel('单次推进').getByRole('button',{name:'执行单次推进',exact:true})).toHaveCount(0);assert.equal(attempts.length,1);assert.ok(!attempts[0]['idempotency-key']);
  for(const width of [390,320]){
   await page.setViewportSize({width,height:844});
   const notice=panel('单次推进').getByText('本次推进结果未知，可能已提交部分批次',{exact:true});
   const confirm=panel('单次推进').getByRole('button',{name:'核对后允许下一次推进',exact:true});
   await notice.scrollIntoViewIfNeeded();await confirm.scrollIntoViewIfNeeded();
   await expect(notice).toBeInViewport();await expect(confirm).toBeInViewport();
   await expect.poll(async()=>(await notice.boundingBox())?.width??0).toBeGreaterThanOrEqual(width*MIN_NOTICE_WIDTH_RATIO);
   await shot(`unknown-notice-${width}`);assert.equal(attempts.length,1);
  }
  await page.setViewportSize({width:1440,height:1000});
  await panel('单次推进').getByRole('button',{name:'核对后允许下一次推进',exact:true}).click();await shot('unknown-confirm');await narrow('unknown-confirm');await narrow('unknown-confirm',320);
  await dialog().getByRole('button',{name:'暂不推进',exact:true}).click();assert.equal(attempts.length,1);
  await panel('单次推进').getByRole('button',{name:'核对后允许下一次推进',exact:true}).click();await dialog().getByRole('button',{name:'已核对，允许下一次',exact:true}).click();
  await expect(panel('单次推进').getByRole('button',{name:'执行单次推进',exact:true})).toBeVisible();assert.equal(attempts.length,1);await page.unroute(pattern);
  // 未知结果仅在显式确认后进入新调用；其后每次都有实际200回执，不能把返回0当作来源放行。
  // 有界调度还会访问S1保留的未知/过期来源；父进程仍用SQL核对SYSTEM恰100条、MANUAL仍0条。
  const receipts=[];
  for(let call=0;call<MAX_CONFIRMED_PUMP_CALLS;call++){
   const button=panel('单次推进').getByRole('button',{name:'执行单次推进',exact:true});
   await expect(button).not.toHaveClass(/ant-btn-loading/);
   const nextResponse=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/v1/admin/segments/pump');
   await button.click();const confirmed=await nextResponse;assert.equal(confirmed.status(),200);
   const count=await confirmed.json();assert.ok(count===0||count===1);assert.ok(!confirmed.request().headers()['idempotency-key']);receipts.push(count);
   await expect(button).not.toHaveClass(/ant-btn-loading/);
   await expect(panel('单次推进').getByText(`本次推进回执已确认：${count}`,{exact:true})).toBeVisible();
   if(count===1)break;
  }
  assert.equal(receipts.at(-1),1,'bounded confirmed calls did not process the approved system task');
  checks.push(`actual confirmed bounded pump receipts: ${receipts.join(',')}`);
  await shot('actual-next-bounded-receipt');await narrow('actual-next-bounded-receipt');await narrow('actual-next-bounded-receipt',320);
  checks.push('real bounded pump response lost; explicit confirmation then new bounded call, no automatic retry or key');
 }else if(phase===Phase.CONTROL){
  const targets=JSON.parse(fs.readFileSync(path.join(run,'segments-ui-targets.json'),'utf8'));
  await open('控制任务');await dialog().getByLabel('实际运行编号',{exact:true}).fill(targets.system);await shot('form-1440');await narrow('form');await narrow('form',320);
  await lostResponse(`**/v1/admin/segment-runs/${targets.system}/cancel`,'确认控制任务','控制任务');
  await expect(panel('控制任务').getByText('CANCELLED',{exact:true})).toBeVisible();await shot('actual-system-cancel');await narrow('actual-system-cancel');await narrow('actual-system-cancel',320);
  await panel('控制任务').getByRole('button',{name:'控制任务',exact:true}).click();await dialog().getByLabel('实际运行编号',{exact:true}).fill(targets.manual);
  await footer().getByRole('button',{name:'确认控制任务',exact:true}).click();await expect(dialog().getByText('当前身份没有此操作权限。').first()).toBeVisible();await shot('actual-original-source-denial');await narrow('actual-original-source-denial');await narrow('actual-original-source-denial',320);
  await footer().getByRole('button',{name:'取消',exact:true}).click();await dialog().getByRole('button',{name:'放弃输入',exact:true}).click();
  checks.push('control only cancels actual policy task; revoked manual creator fails closed');
 }else if(phase===Phase.READ){
  await expect(panel('定义与运行记录').getByText(f.known,{exact:true})).toBeVisible();await shot('directory-1440');await narrow('directory');await narrow('directory',320);
  const row=panel('定义与运行记录').getByRole('row').filter({hasText:f.known});await row.getByRole('button',{name:'详情与记录',exact:true}).click();
  await expect(dialog().getByRole('button',{name:'查看',exact:true}).first()).toBeVisible();await shot('actual-definition-1440');await narrow('actual-definition');await narrow('actual-definition',320);
  await dialog().getByRole('row').filter({hasText:'COMPLETED'}).getByRole('button',{name:'查看',exact:true}).click();await expect(dialog().getByText('固定定义版本',{exact:true})).toBeVisible();await expect(dialog().getByText('COMPLETED',{exact:true})).toBeVisible();await shot('actual-run-1440');await narrow('actual-run');await narrow('actual-run',320);
  await page.keyboard.press('Escape');await page.keyboard.press('Escape');await expect(row.getByRole('button',{name:'详情与记录',exact:true})).toBeFocused();
  // 无效会话进入真实HTTP401；不能用授权夹具隐藏失效。
  await page.evaluate(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',token_type:'Bearer',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+1000})),f);
  await page.reload();await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();await expect(page.getByText(f.known,{exact:true})).toHaveCount(0);await shot('actual401-private-data-hidden');
  checks.push('actual directory and runs versions, keyboard focus and real401 sensitive view unmount');
 }else{
  for(const [name,label] of [['创建定义','创建定义'],['调整调度','调整调度'],['发起刷新','发起刷新'],['控制任务','控制任务'],['单次推进','执行单次推进']]){
   if(name==='创建定义')await tab(name);else{await tab('独立操作');await tab(name);}
   await expect(panel(name).getByRole('button',{name:label,exact:true})).toHaveCount(0);
  }
  await shot('all-actions-fail-closed');await narrow('all-actions-fail-closed');await narrow('all-actions-fail-closed',320);checks.push('all independent actions closed after real revocation or actual Auth outage');
 }
 assert.deepEqual(errors,[]);
 assert.ok(!calls.some(r=>/\/v1\/(?:admin\/members|admin\/rules|admin\/audiences|admin\/skus)/.test(new URL(r.url()).pathname)));
 fs.writeFileSync(path.join(run,`segments-ui-browser-${phase}.json`),JSON.stringify({phase,harnessSha256,result:'PASS',checks,requests:calls.length,backend:'REAL',runtime:'PACKAGED_JAR'},null,2),{mode:0o600,flag:'wx'});
}finally{await browser.close();}
