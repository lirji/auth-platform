/** 标签真实隔离验收；响应丢失发生在服务端实际提交后，保留业务与身份审计验证。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE;
const Phase={DEFINE:'define-only',ASSIGN:'assign',READ:'read',REVOKED:'revoked',OUTAGE:'outage'};
assert(Object.values(Phase).includes(phase));
const f=JSON.parse(fs.readFileSync(path.join(run,'tag-ui.json'),'utf8'));
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000}});
if(!(phase===Phase.DEFINE && f.interactive))await context.addInitScript(f=>{
  const key=`oidc.user:${f.authority}:${f.client}`;if(sessionStorage.getItem(key))return;
  const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
  sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page=await context.newPage(),base='http://127.0.0.1:18665',url=`${base}/operations/member-tags?tenant_id=${f.tenant}`;
const checks=[],calls=[],member='ce04-tag-ui-member',tagId='ce04-ui-tag';
const shot=name=>page.screenshot({path:path.join(run,`tag-${name}.png`),fullPage:true,animations:'disabled'});
const tab=name=>page.getByRole('tab',{name,exact:true}).click();
const panel=name=>page.getByRole('tabpanel',{name,exact:true});
async function assignments(){await tab('会员标签');const p=panel('会员标签');await p.getByLabel('查询会员编号',{exact:true}).fill(member);await p.getByRole('button',{name:'查询会员标签',exact:true}).click();return p;}
async function selectOperation(p,label){await p.getByLabel('标签操作',{exact:true}).click();await page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter({hasText:new RegExp('^'+label+'$')}).click();}
try{
  page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});await page.goto(url);
  if(phase===Phase.DEFINE && f.interactive){
    await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');
    await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);
    await page.getByRole('button',{name:'Sign In',exact:true}).click();await page.waitForURL(base+'/operations/member-tags?**',{timeout:30000});
    assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);assert.ok(!new URL(page.url()).searchParams.has('code'));checks.push('real PKCE returns to tag page without callback code');
  }
  if(phase===Phase.DEFINE){
    await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('定义标签');const p=panel('定义标签');
    const submit=p.getByRole('button',{name:'确认定义标签',exact:true});await expect(submit).toBeVisible();await submit.click();await expect(p.getByText('请输入不超过64字的标签名称',{exact:true})).toBeVisible();
    await p.getByLabel('标签编号',{exact:true}).fill(tagId);await p.getByLabel('标签名称',{exact:true}).fill('页面会员标签');await shot('define-form');
    await page.setViewportSize({width:390,height:844});await shot('390-define-form');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.setViewportSize({width:1440,height:1000});
    await submit.click();await expect(p.getByText('定义标签成功：'+tagId,{exact:true})).toBeVisible();await tab('分配与撤销');await expect(panel('分配与撤销').getByText('当前没有分配与撤销权限，可联系管理员申请',{exact:true})).toBeVisible();checks.push('definition-only validates and creates actual dictionary without read or assignment');
  }else if(phase===Phase.ASSIGN){
    const a=await assignments();await expect(a.getByText('当前身份没有此操作权限。',{exact:true})).toBeVisible();await tab('分配与撤销');const p=panel('分配与撤销');
    const submit=p.getByRole('button',{name:'确认分配与撤销',exact:true});await expect(submit).toBeVisible();await submit.click();await expect(p.getByText('请选择分配或撤销',{exact:true})).toBeVisible();
    await p.getByLabel('会员编号',{exact:true}).fill(member);await p.getByLabel('标签编号',{exact:true}).fill(tagId);await p.getByLabel('当前关联版本',{exact:true}).fill('0');await selectOperation(p,'分配标签');await p.getByLabel('操作原因',{exact:true}).fill('页面标签分配');await shot('assign-form');
    const keys=[],bodies=[];await page.route(`**/v1/admin/member-tags/${member}/assign`,async route=>{
      keys.push(route.request().headers()['idempotency-key']);bodies.push(route.request().postData());
      if(keys.length===1){const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');}else await route.continue();
    });
    await submit.click();await expect(p.getByText('操作结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await expect(p.getByLabel('标签编号',{exact:true})).toBeDisabled();await shot('unknown');
    await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('dialog')).toBeVisible();await page.getByRole('dialog').getByRole('button',{name:'留在当前页',exact:true}).click();await tab('标签字典');await tab('分配与撤销');await expect(p.getByLabel('操作原因',{exact:true})).toHaveValue('页面标签分配');
    await p.getByRole('button',{name:'原样重试',exact:true}).click();await expect(p.getByText('分配与撤销成功：'+tagId,{exact:true})).toBeVisible();assert.equal(keys.length,2);assert.ok(keys[0]);assert.equal(keys[0],keys[1]);assert.equal(bodies[0],bodies[1]);assert.equal(JSON.parse(bodies[0]).active,true);checks.push('assign without read submits real boolean and survives response loss with same body and key');checks.push('frozen intent survives tab changes and logout cancellation');
  }else if(phase===Phase.READ){
    await expect(panel('标签字典').getByText('页面会员标签',{exact:true})).toBeVisible();await expect(panel('标签字典').getByRole('button',{name:'下一批标签',exact:true})).toBeDisabled();await shot('dictionary');
    const a=await assignments();await expect(a.getByText('页面标签分配',{exact:true})).toBeVisible();await expect(a.getByText('已分配',{exact:true})).toBeVisible();await expect(a.getByRole('button',{name:'下一批关联',exact:true})).toBeDisabled();await shot('assignments');
    await tab('分配与撤销');const p=panel('分配与撤销');await expect(p.getByRole('button',{name:'确认分配与撤销',exact:true})).toBeVisible();await p.getByLabel('会员编号',{exact:true}).fill(member);await p.getByLabel('标签编号',{exact:true}).fill(tagId);await p.getByLabel('当前关联版本',{exact:true}).fill('8');await selectOperation(p,'撤销标签');await p.getByLabel('操作原因',{exact:true}).fill('页面撤销标签');await p.getByRole('button',{name:'确认分配与撤销',exact:true}).click();
    await expect(p.getByText('请核对标签是否已存在或关联最新版本，再提交操作',{exact:true})).toBeVisible();await expect(p.getByLabel('操作原因',{exact:true})).toHaveValue('页面撤销标签');await shot('conflict');await page.setViewportSize({width:390,height:844});await shot('390-revoke-form');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.setViewportSize({width:1440,height:1000});
    await p.getByLabel('当前关联版本',{exact:true}).fill('1');await p.getByRole('button',{name:'确认分配与撤销',exact:true}).click();await expect(p.getByText('分配与撤销成功：'+tagId,{exact:true})).toBeVisible();await assignments();await expect(a.getByText('页面撤销标签',{exact:true})).toBeVisible();await expect(a.getByText('已撤销',{exact:true})).toBeVisible();await shot('revoked-association');checks.push('actual dictionary and member associations display bounded cursors and current source reason version');checks.push('stale version 409 retains input then correct deactivation preserves association');
    await page.setViewportSize({width:390,height:844});await shot('390-assignments');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  }else if(phase===Phase.REVOKED){
    await expect(page.getByText('页面会员标签',{exact:true})).toBeVisible();await tab('分配与撤销');await expect(panel('分配与撤销').getByText('当前没有分配与撤销权限，可联系管理员申请',{exact:true})).toBeVisible();await tab('定义标签');await expect(panel('定义标签').getByRole('button',{name:'确认定义标签',exact:true})).toBeVisible();checks.push('assignment revocation preserves independent definition and read');
    await page.goto(`${base}/operations/member-tags?tenant_id=00000000-0000-0000-0000-000000000000`);await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();await expect(page.getByText('页面会员标签',{exact:true})).toHaveCount(0);checks.push('foreign tenant denies dictionary and clears prior data');
    await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();await page.evaluate(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600})),f);await page.goto(url);await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();await expect(page.getByRole('tab')).toHaveCount(0);checks.push('logout and real 401 remove tag panels');
  }else{
    await expect(page.getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await tab('定义标签');await expect(panel('定义标签').getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await expect(page.getByRole('button',{name:'确认定义标签',exact:true})).toHaveCount(0);checks.push('real central outage fails closed for dictionary and definition hint');
  }
  await shot(phase);
  for(const r of calls){assert.ok(r.headers().authorization?.startsWith('Bearer '));assert.ok([f.tenant,'00000000-0000-0000-0000-000000000000'].includes(r.headers()['x-tenant-id']));assert.match(new URL(r.url()).pathname,/^\/v1\/(admin\/member-tags(\/[A-Za-z0-9_-]{1,100}\/(assignments|assign))?|operations\/member-tags\/(define|assign)-access)$/);}
  fs.writeFileSync(path.join(run,`tag-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
}catch(error){await shot(phase+'-failure');throw error;}finally{await browser.close();}
