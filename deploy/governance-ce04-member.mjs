/** 真实会员隔离浏览器验收；仅在真实提交后丢弃响应，不伪造服务端成功。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE;
const Phase={CREATE:'create-only',PROFILE:'profile',READ:'read',STATUS:'status',REVOKED:'revoked',OUTAGE:'outage'};
assert(Object.values(Phase).includes(phase));
const f=JSON.parse(fs.readFileSync(path.join(run,'member-ui.json'),'utf8'));
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000}});
if(!(phase===Phase.CREATE && f.interactive))await context.addInitScript(f=>{
  const key=`oidc.user:${f.authority}:${f.client}`;if(sessionStorage.getItem(key))return;
  const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
  sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page=await context.newPage(),base='http://127.0.0.1:18665',url=`${base}/operations/members?tenant_id=${f.tenant}`;
const checks=[],calls=[];
const shot=name=>page.screenshot({path:path.join(run,`member-${name}.png`),fullPage:true,animations:'disabled'});
const tab=name=>page.getByRole('tab',{name,exact:true}).click();
const panel=name=>page.getByRole('tabpanel',{name,exact:true});
try{
  page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});
  await page.goto(url);
  if(phase===Phase.CREATE && f.interactive){
    await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');
    await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);
    await page.getByRole('button',{name:'Sign In',exact:true}).click();await page.waitForURL(base+'/operations/members?**',{timeout:30000});
    assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);assert.ok(!new URL(page.url()).searchParams.has('code'));checks.push('real password PKCE returns to member page without callback code');
  }
  if(phase===Phase.CREATE){
    await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('新建会员');
    const p=panel('新建会员'),submit=p.getByRole('button',{name:'确认新建会员',exact:true});await expect(submit).toBeVisible();await submit.click();await expect(p.getByText('请输入会员姓名',{exact:true})).toBeVisible();
    await p.getByLabel('会员编号',{exact:true}).fill('ce04-ui-member');await p.getByLabel('客户账号编号',{exact:true}).fill('ce04-ui-customer');await p.getByLabel('会员姓名',{exact:true}).fill('会员页面验收');await p.getByLabel('会员等级',{exact:true}).fill('BASIC');
    await expect(p.getByText('请输入会员姓名',{exact:true})).toHaveCount(0);await shot('create-form');const keys=[];
    await page.route('**/v1/admin/members',async route=>{
      if(route.request().method()!=='POST')return route.continue();keys.push(route.request().headers()['idempotency-key']);
      if(keys.length===1){const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');}else await route.continue();
    });
    await submit.click();await expect(p.getByText('操作结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await expect(p.getByLabel('会员编号',{exact:true})).toBeDisabled();await shot('unknown');
    await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('dialog')).toBeVisible();await page.getByRole('dialog').getByRole('button',{name:'留在当前页',exact:true}).click();
    await tab('会员档案');await tab('新建会员');await expect(p.getByLabel('会员编号',{exact:true})).toHaveValue('ce04-ui-member');
    await p.getByRole('button',{name:'原样重试',exact:true}).click();await expect(p.getByText('新建会员成功：ce04-ui-member',{exact:true})).toBeVisible();assert.equal(keys.length,2);assert.ok(keys[0]);assert.equal(keys[0],keys[1]);
    checks.push('create-only real write succeeds after response loss with same key and frozen intent');checks.push('logout cancellation and tabs preserve unknown command');
    await tab('修改资料');await expect(panel('修改资料').getByText('当前没有修改资料权限，可联系管理员申请',{exact:true})).toBeVisible();await tab('修改状态');await expect(panel('修改状态').getByText('当前没有修改状态权限，可联系管理员申请',{exact:true})).toBeVisible();checks.push('create capability does not imply profile or status');
  }else if(phase===Phase.PROFILE){
    await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('修改资料');const p=panel('修改资料');
    await expect(p.getByRole('button',{name:'确认修改资料',exact:true})).toBeVisible();await p.getByLabel('会员编号',{exact:true}).fill('ce04-ui-member');await p.getByLabel('当前版本',{exact:true}).fill('8');await p.getByLabel('新的会员姓名',{exact:true}).fill('会员姓名已更新');await p.getByLabel('变更原因',{exact:true}).fill('会员资料隔离验收');
    await p.getByRole('button',{name:'确认修改资料',exact:true}).click();await expect(p.getByText('请核对会员最新版本和状态后，再提交修改',{exact:true})).toBeVisible();await expect(p.getByLabel('会员编号',{exact:true})).toHaveValue('ce04-ui-member');await shot('conflict');
    await p.getByLabel('当前版本',{exact:true}).fill('0');await p.getByRole('button',{name:'确认修改资料',exact:true}).click();await expect(p.getByText('修改资料成功：ce04-ui-member',{exact:true})).toBeVisible();checks.push('profile-only real owner write without read; 409 retains input then corrected version succeeds');
  }else if(phase===Phase.READ){
    await expect(page.getByText('会员姓名已更新',{exact:true})).toBeVisible();await shot('list');
    const row=page.getByRole('row').filter({hasText:'ce04-ui-member'});await row.getByRole('button',{name:'查看变更记录',exact:true}).click();
    const dialog=page.getByRole('dialog');await expect(dialog.getByText('会员资料隔离验收',{exact:true})).toBeVisible();await expect(dialog.getByText('会员姓名已更新',{exact:true})).toBeVisible();await shot('history');
    await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await expect(row).toBeVisible();checks.push('real history displays before after reason actor version and closes back to list');
    await page.setViewportSize({width:390,height:844});await shot('390-list');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await row.getByRole('button',{name:'查看变更记录',exact:true}).click();await expect(page.getByRole('dialog').getByText('会员资料隔离验收',{exact:true})).toBeVisible();await shot('390-history');await page.keyboard.press('Escape');checks.push('390px list and history stay bounded with internally scrolling tables');
  }else if(phase===Phase.STATUS){
    await tab('修改状态');const p=panel('修改状态');await expect(p.getByRole('button',{name:'确认修改状态',exact:true})).toBeVisible();
    await p.getByLabel('会员编号',{exact:true}).fill('ce04-ui-member');await p.getByLabel('当前版本',{exact:true}).fill('1');await p.getByLabel('目标状态',{exact:true}).click();await page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter({hasText:/^已注销$/}).click();await p.getByLabel('变更原因',{exact:true}).fill('注销隔离测试会员');await shot('status-form');
    await p.getByRole('button',{name:'确认修改状态',exact:true}).click();await expect(p.getByText('修改状态成功：ce04-ui-member',{exact:true})).toBeVisible();
    await p.getByLabel('会员编号',{exact:true}).fill('ce04-ui-member');await p.getByLabel('当前版本',{exact:true}).fill('2');await p.getByLabel('目标状态',{exact:true}).click();await page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter({hasText:/^正常$/}).click();await p.getByLabel('变更原因',{exact:true}).fill('拒绝恢复');await p.getByRole('button',{name:'确认修改状态',exact:true}).click();await expect(p.getByText('请核对会员最新版本和状态后，再提交修改',{exact:true})).toBeVisible();checks.push('independent status capability closes actual member and reopening conflicts');
    await page.setViewportSize({width:390,height:844});await shot('390-status');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  }else if(phase===Phase.REVOKED){
    await expect(page.getByText('会员姓名已更新',{exact:true})).toBeVisible();await tab('修改资料');await expect(panel('修改资料').getByText('当前没有修改资料权限，可联系管理员申请',{exact:true})).toBeVisible();await tab('修改状态');await expect(panel('修改状态').getByRole('button',{name:'确认修改状态',exact:true})).toBeVisible();checks.push('profile revocation preserves independently granted read and status');
    await page.goto(`${base}/operations/members?tenant_id=00000000-0000-0000-0000-000000000000`);await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();await expect(page.getByText('会员姓名已更新',{exact:true})).toHaveCount(0);checks.push('foreign tenant denies member list and clears prior rows');
    await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();
    await page.evaluate(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600})),f);await page.goto(url);await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();await expect(page.getByRole('tab')).toHaveCount(0);checks.push('logout and actual 401 remove member panels');
  }else{
    await expect(page.getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await expect(page.getByText('会员姓名已更新',{exact:true})).toHaveCount(0);await tab('新建会员');await expect(panel('新建会员').getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await expect(page.getByRole('button',{name:'确认新建会员',exact:true})).toHaveCount(0);checks.push('real central outage fails closed for list and write hints');
  }
  await shot(phase);
  for(const r of calls){assert.ok(r.headers().authorization?.startsWith('Bearer '));assert.ok([f.tenant,'00000000-0000-0000-0000-000000000000'].includes(r.headers()['x-tenant-id']));assert.match(new URL(r.url()).pathname,/^\/v1\/(admin\/members(\/[A-Za-z0-9_-]{1,100}\/(history|profile|status))?|operations\/members\/(create|profile|status)-access)$/);}
  fs.writeFileSync(path.join(run,`member-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
}catch(error){await shot(phase+'-failure');throw error;}finally{await browser.close();}
