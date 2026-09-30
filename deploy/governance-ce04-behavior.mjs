/** 真实行为页面验收；响应丢失在服务端提交后发生，最终API/SQL另核对次数和版本。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE;
const Phase={UPDATE:'update-only',REBUILD:'rebuild',READ:'read',REVOKED:'revoked',OUTAGE:'outage'};
assert(Object.values(Phase).includes(phase));
const f=JSON.parse(fs.readFileSync(path.join(run,'behavior-ui.json'),'utf8'));
const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000}});
if(!(phase===Phase.UPDATE && f.interactive))await context.addInitScript(f=>{
 const key=`oidc.user:${f.authority}:${f.client}`;if(sessionStorage.getItem(key))return;
 const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
 sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page=await context.newPage(),base='http://127.0.0.1:18665',url=`${base}/operations/member-behavior?tenant_id=${f.tenant}`;
const checks=[],calls=[],member='ce04-behavior-ui-member',source='ce04-behavior-member';
const tab=name=>page.getByRole('tab',{name,exact:true}).click(),panel=name=>page.getByRole('tabpanel',{name,exact:true});
const shot=name=>page.screenshot({path:path.join(run,`behavior-${name}.png`),fullPage:true,animations:'disabled'});
async function query(id=source){await tab('行为查询');const p=panel('行为查询');await p.getByLabel('查询会员编号',{exact:true}).fill(id);await p.getByRole('button',{name:'查询会员行为',exact:true}).click();return p;}
async function preference(p,label){await p.getByLabel('站内营销旅程',{exact:true}).click();await page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter({hasText:new RegExp('^'+label+'$')}).click();}
async function narrow(name){await page.setViewportSize({width:390,height:844});await shot('390-'+name);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.setViewportSize({width:1440,height:1000});}
async function loseOnce(pattern){const keys=[],bodies=[];await page.route(pattern,async route=>{keys.push(route.request().headers()['idempotency-key']);bodies.push(route.request().postData());if(keys.length===1){const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');}else await route.continue();});return ()=>{assert.equal(keys.length,2);assert.ok(keys[0]);assert.equal(keys[0],keys[1]);assert.equal(bodies[0],bodies[1]);return JSON.parse(bodies[0]);};}
try{
 page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});await page.goto(url);
 if(phase===Phase.UPDATE && f.interactive){
  await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);await page.getByRole('button',{name:'Sign In',exact:true}).click();await page.waitForURL(base+'/operations/member-behavior?**',{timeout:30000});assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);assert.ok(!new URL(page.url()).searchParams.has('code'));checks.push('real PKCE returns to behavior page and removes callback code');
 }
 if(phase===Phase.UPDATE){
  const read=await query();await expect(read.getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('修改偏好');const p=panel('修改偏好'),submit=p.getByRole('button',{name:'确认修改偏好',exact:true});await expect(submit).toBeVisible();await submit.click();await expect(p.getByText('请选择接收或关闭',{exact:true})).toBeVisible();
  await p.getByLabel('会员编号',{exact:true}).fill(member);await p.getByLabel('当前偏好版本',{exact:true}).fill('0');await p.getByLabel('生日月日',{exact:true}).fill('02-30');await preference(p,'关闭旅程');await p.getByLabel('变更原因',{exact:true}).fill('页面偏好维护');await submit.click();await expect(p.getByText('请输入有效月日，如02-29；也可留空清除',{exact:true})).toBeVisible();await p.getByLabel('生日月日',{exact:true}).fill('02-29');await expect(p.getByText('请输入有效月日，如02-29；也可留空清除',{exact:true})).toHaveCount(0);await shot('update-form');await narrow('update-form');
  const verify=await loseOnce(`**/v1/admin/member-behavior/${member}/profile`);await submit.click();await expect(p.getByText('操作结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await expect(p.getByLabel('生日月日',{exact:true})).toBeDisabled();await shot('unknown');
  await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('dialog')).toBeVisible();await page.getByRole('dialog').getByRole('button',{name:'留在当前页',exact:true}).click();await tab('行为查询');await tab('修改偏好');await expect(p.getByLabel('变更原因',{exact:true})).toHaveValue('页面偏好维护');await p.getByRole('button',{name:'原样重试',exact:true}).click();await expect(p.getByText('修改偏好成功',{exact:true})).toBeVisible();await expect(submit).toBeVisible();assert.equal(verify().journeyEnabled,false);checks.push('update without read validates leap birthday and retries original body key after real response loss');checks.push('frozen update intent survives tab change and logout cancellation');
  await tab('历史成交补建');await expect(panel('历史成交补建').getByText('当前没有历史成交补建权限，可联系管理员申请',{exact:true})).toBeVisible();checks.push('profile update does not imply rebuild');
 }else if(phase===Phase.REBUILD){
  const read=await query();await expect(read.getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('历史成交补建');const p=panel('历史成交补建'),submit=p.getByRole('button',{name:'确认历史成交补建',exact:true});await expect(submit).toBeVisible();await submit.click();await expect(p.getByText('请输入1至50的整数',{exact:true})).toBeVisible();await p.getByLabel('本批最多扫描订单数',{exact:true}).fill('1');await p.getByLabel('补建游标',{exact:true}).fill('');await expect(p.getByText('请输入1至50的整数',{exact:true})).toHaveCount(0);await shot('rebuild-form');await narrow('rebuild-form');
  const verify=await loseOnce('**/v1/admin/member-behavior/rebuild');await submit.click();await expect(p.getByText('操作结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await expect(p.getByLabel('补建游标',{exact:true})).toBeDisabled();await shot('rebuild-unknown');await p.getByRole('button',{name:'原样重试',exact:true}).click();await expect(p.getByText('历史成交补建成功',{exact:true})).toBeVisible();await expect(p.getByText(/本批扫描 1 单；可继续下一批；下一批游标：ce04-behavior-order-a/)).toBeVisible();await expect(submit).toBeVisible();assert.deepEqual(verify(),{after:'',limit:1});checks.push('bounded rebuild works without read and preserves actual cursor through same-key retry');
 }else if(phase===Phase.READ){
  const p=await query();await expect(p.getByText('Behavior fixture · 行为资料',{exact:true})).toBeVisible();await expect(p.getByText('¥12.00',{exact:true})).toBeVisible();await expect(p.getByText('ce04-behavior-event',{exact:true})).toBeVisible();await expect(p.getByRole('button',{name:'下一批交互',exact:true})).toBeDisabled();await shot('detail');await narrow('detail');checks.push('real source facts and bounded event cursor render independently');
  await query(member);await expect(p.getByText('暂无成交事实',{exact:true})).toBeVisible();await shot('no-order-facts');
  await tab('修改偏好');const form=panel('修改偏好'),submit=form.getByRole('button',{name:'确认修改偏好',exact:true});await expect(submit).toBeVisible();await form.getByLabel('会员编号',{exact:true}).fill(member);await form.getByLabel('当前偏好版本',{exact:true}).fill('8');await form.getByLabel('生日月日',{exact:true}).fill('03-15');await preference(form,'接收旅程');await form.getByLabel('变更原因',{exact:true}).fill('页面偏好纠正');await submit.click();await expect(form.getByText('请核对会员状态与最新偏好版本，再提交操作',{exact:true})).toBeVisible();await expect(form.getByLabel('变更原因',{exact:true})).toHaveValue('页面偏好纠正');await shot('conflict');await form.getByLabel('当前偏好版本',{exact:true}).fill('1');await submit.click();await expect(form.getByText('修改偏好成功',{exact:true})).toBeVisible();await expect(form.getByText(/偏好版本：2；生日：03-15；站内营销旅程：接收/)).toBeVisible();await query(member);await expect(p.getByText('03-15',{exact:true})).toBeVisible();await shot('updated-detail');checks.push('unknown order facts remain unknown and 409 retains input before corrected version succeeds');
 }else if(phase===Phase.REVOKED){
  const p=await query();await expect(p.getByText('¥12.00',{exact:true})).toBeVisible();for(const title of ['修改偏好','历史成交补建']){await tab(title);await expect(panel(title).getByText(`当前没有${title}权限，可联系管理员申请`,{exact:true})).toBeVisible();}checks.push('revoked write capabilities preserve independent behavior read');
  await page.goto(`${base}/operations/member-behavior?tenant_id=00000000-0000-0000-0000-000000000000`);const foreign=await query();await expect(foreign.getByText('当前身份没有此操作权限。').first()).toBeVisible();await expect(foreign.getByText('¥12.00',{exact:true})).toHaveCount(0);checks.push('foreign tenant denies known member and clears prior facts');
  await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();await page.evaluate(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600})),f);await page.goto(url);await tab('修改偏好');await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();await expect(page.getByRole('tab')).toHaveCount(0);checks.push('logout and real 401 remove behavior panels');
 }else{
  const p=await query();await expect(p.getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await tab('修改偏好');await expect(panel('修改偏好').getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await expect(page.getByRole('button',{name:'确认修改偏好',exact:true})).toHaveCount(0);checks.push('real central outage fails closed for facts and action hint');
 }
 await shot(phase);
 for(const r of calls){assert.ok(r.headers().authorization?.startsWith('Bearer '));assert.ok([f.tenant,'00000000-0000-0000-0000-000000000000'].includes(r.headers()['x-tenant-id']));assert.match(new URL(r.url()).pathname,/^\/v1\/(admin\/member-behavior\/[A-Za-z0-9_-]{1,100}(\/(events|profile))?|operations\/member-behavior\/(update|rebuild)-access)$/);}
 fs.writeFileSync(path.join(run,`behavior-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
}catch(error){await shot(phase+'-failure');throw error;}finally{await browser.close();}
