/** 四独立HUMAN岗位与实际最终JAR。只丢弃真实200响应，不伪造判权或业务JSON。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE;
const phases=['create-only','pump-only','control-only','read-only','revoked','outage'];
assert.ok(phases.includes(phase));
const scriptHash=createHash('sha256').update(fs.readFileSync(new URL(import.meta.url))).digest('hex');
const f=JSON.parse(fs.readFileSync(path.join(run,'coupon-deliveries-ui.json'),'utf8'));
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'UTC'});
if(!(phase==='create-only'&&f.interactive))await context.addInitScript(f=>{
 const key=`oidc.user:${f.authority}:${f.client}`;
 if(sessionStorage.getItem(key))return;
 const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
 sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
// 当前SSO制品与已注册回调同属18665；18661只承载演练主API，不能作为浏览器入口。
const page=await context.newPage(),base='http://127.0.0.1:18665';
const artifact=JSON.parse(fs.readFileSync(path.join(run,'browser-artifact-fence.json'),'utf8'));
assert.equal(artifact.mode,'PACKAGED_JAR');assert.equal(artifact.browser_origin,base);assert.equal(artifact.browser_client,f.client);
assert.equal(createHash('sha256').update(fs.readFileSync(path.join(run,'commerce-browser.jar'))).digest('hex'),artifact.jar_sha256);
const query=`?tenant_id=${encodeURIComponent(f.tenant)}&Delivery.store=${encodeURIComponent(f.store)}`;
const url=base+'/operations/coupon-deliveries'+query;
const errors=[],calls=[],checks=[],screenshots=[];
const panel=name=>page.getByRole('tabpanel',{name,exact:true});
const tab=name=>page.getByRole('tab',{name,exact:true}).click();
const dialog=()=>page.getByRole('dialog').last();
const footer=()=>dialog().locator('.ant-modal-footer');
const TOLERANCE=24;
async function shot(name){
 await page.evaluate(async()=>{await document.fonts.ready;await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));});
 // 输入末字段会触发浏览器自动滚动；从正文顶部截图，避免把滚动边界截断标签当成完整表单证据。
 if(await page.getByRole('dialog').count())await dialog().locator('.ant-modal-body').evaluate(body=>{body.scrollTop=0;});
 if(await page.getByRole('dialog').count())await expect.poll(async()=>{
  const box=await dialog().boundingBox(),viewport=page.viewportSize();
  return box?Math.abs(box.y+box.height/2-viewport.height/2):Infinity;
 }).toBeLessThanOrEqual(TOLERANCE);
 const filename=`coupon-deliveries-${phase}-${name}.png`;
 await page.screenshot({path:path.join(run,filename),fullPage:false,animations:'disabled'});
 screenshots.push({name:filename,sha256:createHash('sha256').update(fs.readFileSync(path.join(run,filename))).digest('hex'),viewport:page.viewportSize()});
}
async function narrow(name,width=390){
 await page.setViewportSize({width,height:844});
 await expect.poll(()=>page.evaluate(()=>Math.max(document.body.scrollWidth,document.documentElement.scrollWidth))).toBe(width);
 if(await page.getByRole('dialog').count())await expect(dialog().getByRole('button').last()).toBeInViewport();
 if(await page.getByRole('dialog').count()){
  const tags=dialog().locator('.ant-descriptions .ant-tag');
  for(let index=0;index<await tags.count();index++)await expect.poll(()=>tags.nth(index).evaluate(element=>{
   const own=element.getBoundingClientRect(),parent=element.parentElement.getBoundingClientRect();
   return element.scrollWidth<=element.clientWidth+1&&own.right<=parent.right+1;
  })).toBe(true);
 }
 await shot(`${name}-${width}`);await page.setViewportSize({width:1440,height:1000});
}
async function open(name){await tab(name);await panel(name).getByRole('button',{name,exact:true}).click();await expect(dialog()).toBeVisible();}
async function controls(id,version,action,reason){
 await open('控制批次');await dialog().getByLabel('实际批次编号',{exact:true}).fill(id);
 await dialog().getByLabel('进度 CAS 版本',{exact:true}).fill(String(version));
 await dialog().getByLabel('控制动作',{exact:true}).click();
 await page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter({hasText:action}).click();
 await dialog().getByLabel('操作原因',{exact:true}).fill(reason);
}
async function lostResponse(pattern,submit,label){
 const attempts=[];
 let release;
 const pending=new Promise(resolve=>{release=resolve;});
 await page.route(pattern,async route=>{
  attempts.push({path:new URL(route.request().url()).pathname,key:route.request().headers()['idempotency-key'],body:route.request().postData()});
  if(attempts.length===1){
   const response=await route.fetch();assert.equal(response.status(),200);
   await pending;await route.abort('failed');
  }else await route.continue();
 });
 await footer().getByRole('button',{name:submit,exact:true}).click();
 await expect.poll(()=>attempts.length).toBe(1);
 await expect(footer().getByRole('button',{name:'取消',exact:true})).toBeDisabled();
 await expect(dialog().locator('.ant-modal-close')).toHaveCount(0);
 await page.keyboard.press('Escape');await expect(dialog()).toBeVisible();
 await shot('busy');release();
 await expect(dialog().getByText('操作结果尚未确认，请保留原意图重试',{exact:true})).toBeVisible();
 await shot('lost-committed-response');await narrow('lost-committed-response');await narrow('lost-committed-response',320);
 await footer().getByRole('button',{name:'返回页签（保留原意图）',exact:true}).click();
 await dialog().getByRole('button',{name:'保留并返回',exact:true}).click();
 await page.getByRole('button',{name:'退出登录',exact:true}).click();
 await dialog().getByRole('button',{name:'留在当前页',exact:true}).click();
 await panel(label).getByRole('button',{name:`重新核验${label}权限`,exact:true}).click();
 await panel(label).getByRole('button',{name:'恢复原操作意图',exact:true}).click();
 await footer().getByRole('button',{name:'原样重试操作',exact:true}).click();
 await expect.poll(()=>attempts.length).toBe(2);assert.deepEqual(attempts[1],attempts[0]);assert.ok(attempts[0].key);
 await expect(page.getByRole('dialog')).toHaveCount(0);
 await page.unroute(pattern);checks.push('real response lost after commit and exact original key/body/path replay; busy and unknown close protection');
}
try{
 // 以实际HTTP逐字节核验服务制品，避免连到旧SSO包后把脚本声明当成运行事实。
 const assets=Object.entries(artifact.frontend_assets_sha256);assert.equal(assets.length,artifact.frontend_files);
 await Promise.all(assets.map(async([asset,sha])=>{
  assert.ok(asset.startsWith('/')&&!asset.startsWith('//')&&!asset.includes('..'));
  const response=await context.request.get(base+asset);assert.equal(response.status(),200);
  assert.equal(createHash('sha256').update(await response.body()).digest('hex'),sha);
 }));
 checks.push('actual browser origin serves all current SSO JAR assets byte-for-byte');
 page.on('pageerror',error=>errors.push(error.message));
 page.on('request',request=>{if(new URL(request.url()).pathname.startsWith('/v1/'))calls.push({path:new URL(request.url()).pathname,method:request.method()});});
 const shell=await page.goto(url);assert.equal(shell.status(),200);assert.ok(shell.headers()['content-type'].includes('text/html'));
 if(phase==='create-only'&&f.interactive){
  await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');
  await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);
  await page.getByRole('button',{name:'Sign In',exact:true}).click();
  await page.waitForURL(base+'/operations/coupon-deliveries?**',{timeout:30000});
  assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);
  assert.ok(!new URL(page.url()).searchParams.has('code'));checks.push('actual internal HUMAN PKCE fixed callback and tenant return');
  // 回跳只保留既有允许的组织上下文；登录后恢复非授权性目录查询，不把它混入OIDC状态。
  await page.goto(url);
 }
 await expect(page.getByRole('heading',{name:'定向发券',exact:true})).toBeVisible();
 if(!['read-only','outage'].includes(phase))await expect(panel('批次与收件人').getByText('当前身份没有此操作权限。').first()).toBeVisible();
 if(phase==='create-only'){
  await open('创建发券批次');
  for(const [label,value] of [['实际批次编号',f.created],['批次名称','真实独立创建岗位'],['实际门店编号',f.store],['固定券定义编号',f.definition],['券定义版本',String(f.definitionVersion)],['固定人群编号',f.created],['人群快照版本',String(f.audienceVersion)],['发放截止（本地时间）',f.deadline.slice(0,16)]])await dialog().getByLabel(label,{exact:true}).fill(value);
  await shot('form-1440');await narrow('form');await narrow('form',320);
  await page.keyboard.press('Escape');await expect(page.getByRole('dialog',{name:'放弃未提交的发券输入？',exact:true})).toBeVisible();
  await dialog().getByRole('button',{name:'继续编辑',exact:true}).click();
  await expect(dialog().getByLabel('实际批次编号',{exact:true})).toHaveValue(f.created);
  await lostResponse('**/v1/admin/coupon-deliveries','确认创建发券批次','创建发券批次');
  await expect(panel('创建发券批次').getByText('批次回执已确认',{exact:true})).toBeVisible();
  await shot('confirmed-receipt');await narrow('confirmed-receipt');await narrow('confirmed-receipt',320);
  checks.push('only CREATE writes actual immutable batch without directory/member/source read');
 }else if(phase==='pump-only'){
  await tab('单次推进');
  const pattern='**/v1/admin/coupon-deliveries/pump',attempts=[];
  let lostCount;
  await page.route(pattern,async route=>{
   attempts.push(route.request().headers());const response=await route.fetch();assert.equal(response.status(),200);
   lostCount=await response.json();assert.ok(Number.isInteger(lostCount)&&lostCount>=0&&lostCount<=20);await route.abort('failed');
  });
  await panel('单次推进').getByRole('button',{name:'执行单次推进',exact:true}).click();
  await expect(panel('单次推进').getByText('本次推进结果未知，可能已提交部分效果',{exact:true})).toBeVisible();
  await panel('单次推进').getByRole('button',{name:'重新核验单次推进权限',exact:true}).click();
  await expect(panel('单次推进').getByRole('button',{name:'执行单次推进',exact:true})).toHaveCount(0);
  assert.equal(attempts.length,1);assert.ok(!attempts[0]['idempotency-key']);
  await shot('unknown-notice');await narrow('unknown-notice');await narrow('unknown-notice',320);
  await panel('单次推进').getByRole('button',{name:'核对后允许下一次推进',exact:true}).click();
  await shot('unknown-confirm');await narrow('unknown-confirm');await narrow('unknown-confirm',320);
  await dialog().getByRole('button',{name:'暂不推进',exact:true}).click();assert.equal(attempts.length,1);
  await panel('单次推进').getByRole('button',{name:'核对后允许下一次推进',exact:true}).click();
  await dialog().getByRole('button',{name:'已核对，允许下一次',exact:true}).click();await page.unroute(pattern);
  const receipts=[lostCount];
  for(let index=0;index<f.maxCalls&&receipts.reduce((sum,value)=>sum+value,0)<f.audienceSize;index++){
   const button=panel('单次推进').getByRole('button',{name:'执行单次推进',exact:true});await expect(button).not.toHaveClass(/ant-btn-loading/);
   const ready=page.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname==='/v1/admin/coupon-deliveries/pump');
   await button.click();const response=await ready;assert.equal(response.status(),200);
   const count=await response.json();assert.ok(Number.isInteger(count)&&count>=0&&count<=20);receipts.push(count);
   await expect(panel('单次推进').getByText(`本次推进回执已确认：${count}`,{exact:true})).toBeVisible();
  }
  assert.equal(receipts.reduce((sum,value)=>sum+value,0),f.audienceSize);
  // 最后一名提交可能用尽时间预算，明确再调用一次并由父SQL核对真正COMPLETED。
  const ready=page.waitForResponse(response=>response.request().method()==='POST'&&new URL(response.url()).pathname==='/v1/admin/coupon-deliveries/pump');
  await expect(panel('单次推进').getByRole('button',{name:'执行单次推进',exact:true})).not.toHaveClass(/ant-btn-loading/);
  await panel('单次推进').getByRole('button',{name:'执行单次推进',exact:true}).click();assert.equal((await ready).status(),200);
  checks.push('only PUMP, no automatic repeat or key; explicit new bounded calls finish actual twenty-one effects');
 }else if(phase==='control-only'){
  await controls(f.cancelled,1,'取消后续发放','真实取消未开始的批次');
  await footer().getByRole('button',{name:'确认控制批次',exact:true}).click();
  await expect(panel('控制批次').getByText('批次版本或状态冲突',{exact:true})).toBeVisible();
  await shot('real409');await narrow('real409');await narrow('real409',320);
  await footer().getByRole('button',{name:'重新核验权限',exact:true}).click();
  await expect(dialog().getByLabel('进度 CAS 版本',{exact:true})).toBeEnabled();await dialog().getByLabel('进度 CAS 版本',{exact:true}).fill('0');
  await footer().getByRole('button',{name:'确认控制批次',exact:true}).click();await expect(page.getByRole('dialog')).toHaveCount(0);
  const target=JSON.parse(fs.readFileSync(path.join(run,'coupon-deliveries-ui-target.json'),'utf8'));
  await controls(f.target,target.targetVersion,'撤回可用券','独立岗位首次补偿');
  await shot('revoke-form');await narrow('revoke-form');await narrow('revoke-form',320);
  await lostResponse('**/v1/admin/coupon-deliveries/'+encodeURIComponent(f.target)+'/control','确认控制批次','控制批次');
  await expect(panel('控制批次').getByText('撤回中 · REVOKING',{exact:true})).toBeVisible();
  await controls(f.created,0,'恢复隔离任务','核对原来源拒绝');
  await footer().getByRole('button',{name:'确认控制批次',exact:true}).click();
  await expect(dialog().getByText('当前身份没有此操作权限。').first()).toBeVisible();
  await shot('original-source-denied');await narrow('original-source-denied');await narrow('original-source-denied',320);
  checks.push('only CONTROL actual CAS409/cancel/first revoke; original revoked issuance retry remains denied');
 }else if(phase==='read-only'){
  const row=panel('批次与收件人').getByRole('row').filter({hasText:f.target});
  const trigger=row.getByRole('button',{name:'详情与收件人',exact:true});await trigger.click();
  await expect(dialog().getByText('撤回处理完成 · REVOCATION_DONE',{exact:true})).toBeVisible();
  await expect(dialog().getByText('已撤回 / 已保留',{exact:true})).toBeVisible();
  await expect(dialog().getByText('本页 21 条，每页最多 50 条',{exact:true})).toBeVisible();
  await expect(dialog().getByRole('cell',{name:'已撤回 · REVOKED',exact:true})).toHaveCount(f.audienceSize);
  await shot('actual-completed-receipts');await narrow('actual-completed-receipts');await narrow('actual-completed-receipts',320);
  await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).toHaveCount(0);await expect(trigger).toBeFocused();
  for(const [name,label] of [['创建发券批次','创建发券批次'],['控制批次','控制批次'],['单次推进','执行单次推进']]){await tab(name);await expect(panel(name).getByRole('button',{name:label,exact:true})).toHaveCount(0);}
  await page.evaluate(f=>{
   const key=`oidc.user:${f.authority}:${f.client}`,stored=JSON.parse(sessionStorage.getItem(key));stored.access_token='actual-invalid';sessionStorage.setItem(key,JSON.stringify(stored));
  },f);
  await page.goto(url);await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);await expect(page.getByText(f.target,{exact:true})).toHaveCount(0);
  await shot('actual401-hidden');checks.push('only READ actual fixed versions and exact revoked receipts; Esc/focus and actual401 partition hidden');
 }else{
  for(const [name,label] of [['创建发券批次','创建发券批次'],['控制批次','控制批次'],['单次推进','执行单次推进']]){await tab(name);await expect(panel(name).getByRole('button',{name:label,exact:true})).toHaveCount(0);}
  await shot('all-actions-closed');await narrow('all-actions-closed');await narrow('all-actions-closed',320);
  checks.push('all independent actions closed after actual revocation or actual Auth process outage');
 }
 assert.deepEqual(errors,[]);
 assert.ok(!calls.some(call=>/\/v1\/(?:admin\/members|admin\/rules|admin\/audiences|admin\/coupon-definitions|admin\/skus)/.test(call.path)));
 fs.writeFileSync(path.join(run,`coupon-deliveries-ui-browser-${phase}.json`),JSON.stringify({phase,result:'PASS',harnessSha256:scriptHash,checks,requests:calls.length,backend:'REAL',runtime:'PACKAGED_JAR',browserOrigin:base,artifactSha256:artifact.jar_sha256,assetCount:artifact.frontend_files,screenshots,visual_review:'UNVERIFIED_UNTIL_IMAGES_OPENED'},null,2),{mode:0o600,flag:'wx'});
}finally{await browser.close();}
