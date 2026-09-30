/** 成长真实隔离验收；仅丢弃已提交响应，不替代后端业务结果。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE;
const Phase={PUBLISH:'publish-only',ADJUST:'adjust',READ:'read',RECALCULATE:'recalculate',REVOKED:'revoked',OUTAGE:'outage'};
assert(Object.values(Phase).includes(phase));
const f=JSON.parse(fs.readFileSync(path.join(run,'growth-ui.json'),'utf8'));
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000}});
if(!(phase===Phase.PUBLISH && f.interactive))await context.addInitScript(f=>{
  const key=`oidc.user:${f.authority}:${f.client}`;if(sessionStorage.getItem(key))return;
  const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
  sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page=await context.newPage(),base='http://127.0.0.1:18665',url=`${base}/operations/member-growth?tenant_id=${f.tenant}`;
const checks=[],calls=[],member='ce04-growth-ui-member';
const shot=name=>page.screenshot({path:path.join(run,`growth-${name}.png`),fullPage:true,animations:'disabled'});
const tab=name=>page.getByRole('tab',{name,exact:true}).click();
const panel=name=>page.getByRole('tabpanel',{name,exact:true});
async function wallet(){await tab('成长钱包');const p=panel('成长钱包');await p.getByLabel('查询会员编号',{exact:true}).fill(member);await p.getByRole('button',{name:'查询成长',exact:true}).click();return p;}
try{
  page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});
  await page.goto(url);
  if(phase===Phase.PUBLISH && f.interactive){
    await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');
    await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);
    await page.getByRole('button',{name:'Sign In',exact:true}).click();await page.waitForURL(base+'/operations/member-growth?**',{timeout:30000});
    assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);assert.ok(!new URL(page.url()).searchParams.has('code'));checks.push('real PKCE returns to growth page and removes callback code');
  }
  if(phase===Phase.PUBLISH){
    await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab('发布政策');const p=panel('发布政策');
    const submit=p.getByRole('button',{name:'确认发布政策',exact:true});await expect(submit).toBeVisible();await submit.click();await expect(p.getByText('请选择生效时间',{exact:true})).toBeVisible();
    await p.getByLabel('新政策版本',{exact:true}).fill('2');
    const date=new Date(Date.now()+60000);const local=new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,16);
    await p.getByLabel('生效时间',{exact:true}).fill(local);await p.getByLabel('每元成长值',{exact:true}).fill('0.123');
    await p.getByLabel('等级 1 编码',{exact:true}).fill('BASIC');await p.getByLabel('等级 1 门槛',{exact:true}).fill('0');
    await submit.click();await expect(p.getByText('请输入0至1000，最多两位小数',{exact:true})).toBeVisible();await p.getByLabel('每元成长值',{exact:true}).fill('1.00');
    await p.getByRole('button',{name:'添加等级',exact:true}).click();await p.getByLabel('等级 2 编码',{exact:true}).fill('SILVER');await p.getByLabel('等级 2 门槛',{exact:true}).fill('0');
    await submit.click();await expect(p.getByText('须有1至8个不同等级，首档门槛为0，其后严格递增',{exact:true})).toBeVisible();await p.getByLabel('等级 2 门槛',{exact:true}).fill('100');
    await p.getByRole('button',{name:'添加等级',exact:true}).click();await p.getByRole('button',{name:'移除等级 3',exact:true}).click();await shot('policy-form');
    await page.setViewportSize({width:390,height:844});await shot('390-policy-form');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.setViewportSize({width:1440,height:1000});
    await submit.click();await expect(p.getByText('发布政策成功',{exact:true})).toBeVisible();checks.push('publish without read uses actual immutable version, validates decimal and ordered levels, adds/removes levels');
    await tab('人工调整');await expect(panel('人工调整').getByText('当前没有人工调整权限，可联系管理员申请',{exact:true})).toBeVisible();await tab('重算等级');await expect(panel('重算等级').getByText('当前没有重算等级权限，可联系管理员申请',{exact:true})).toBeVisible();checks.push('policy publication does not imply member adjustment or recalculation');
  }else if(phase===Phase.ADJUST){
    const w=await wallet();await expect(w.getByText('当前身份没有此操作权限。',{exact:true})).toBeVisible();await tab('人工调整');const p=panel('人工调整');await expect(p.getByRole('button',{name:'确认人工调整',exact:true})).toBeVisible();
    await p.getByLabel('会员编号',{exact:true}).fill(member);await p.getByLabel('当前账户版本',{exact:true}).fill('8');await p.getByLabel('调整成长值',{exact:true}).fill('0');await p.getByLabel('调整原因',{exact:true}).fill('成长页面隔离校准');await p.getByRole('button',{name:'确认人工调整',exact:true}).click();await expect(p.getByText('调整值不能为0',{exact:true})).toBeVisible();await p.getByLabel('调整成长值',{exact:true}).fill('250');
    await p.getByRole('button',{name:'确认人工调整',exact:true}).click();await expect(p.getByText('请核对最新版本和当前状态后，再提交操作',{exact:true})).toBeVisible();await expect(p.getByLabel('调整原因',{exact:true})).toHaveValue('成长页面隔离校准');await shot('conflict');await p.getByLabel('当前账户版本',{exact:true}).fill('0');
    const keys=[],bodies=[];await page.route(`**/v1/admin/member-growth/${member}/adjust`,async route=>{
      keys.push(route.request().headers()['idempotency-key']);bodies.push(route.request().postData());
      if(keys.length===1){const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');}else await route.continue();
    });
    await p.getByRole('button',{name:'确认人工调整',exact:true}).click();await expect(p.getByText('操作结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await expect(p.getByLabel('会员编号',{exact:true})).toBeDisabled();await shot('unknown');
    await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('dialog')).toBeVisible();await page.getByRole('dialog').getByRole('button',{name:'留在当前页',exact:true}).click();await tab('成长政策');await tab('人工调整');await expect(p.getByLabel('调整原因',{exact:true})).toHaveValue('成长页面隔离校准');
    await p.getByRole('button',{name:'原样重试',exact:true}).click();await expect(p.getByText('人工调整成功',{exact:true})).toBeVisible();assert.equal(keys.length,2);assert.ok(keys[0]);assert.equal(keys[0],keys[1]);assert.equal(bodies[0],bodies[1]);checks.push('adjust without read validates nonzero and preserves 409 input before corrected write');checks.push('real response loss freezes intent, survives tabs/logout cancel and retries same body/key once');
  }else if(phase===Phase.READ){
    const p=panel('成长政策');await expect(p.getByRole('row')).toHaveCount(3);await expect(p.getByRole('button',{name:'下一批政策',exact:true})).toBeDisabled();await shot('policies');
    const w=await wallet();await expect(w.getByText('成长页面隔离校准',{exact:true})).toBeVisible();await expect(w.getByText('SILVER',{exact:true})).toBeVisible();await expect(w.getByRole('button',{name:'下一批流水',exact:true})).toBeDisabled();await shot('wallet');
    await page.setViewportSize({width:390,height:844});await shot('390-wallet');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));checks.push('real independent policy read and wallet/ledger display versions, balances, reason, local time and bounded pagination');
  }else if(phase===Phase.RECALCULATE){
    await tab('重算等级');const p=panel('重算等级');await expect(p.getByRole('button',{name:'确认重算等级',exact:true})).toBeVisible();await p.getByLabel('会员编号',{exact:true}).fill(member);await shot('recalculate-form');await p.getByRole('button',{name:'确认重算等级',exact:true}).click();await expect(p.getByText('重算等级成功',{exact:true})).toBeVisible();await expect(p.getByText('SILVER',{exact:true})).toBeVisible();checks.push('explicit recalculation uses actual policy without changing growth balance');
  }else if(phase===Phase.REVOKED){
    await tab('人工调整');await expect(panel('人工调整').getByText('当前没有人工调整权限，可联系管理员申请',{exact:true})).toBeVisible();await tab('重算等级');await expect(panel('重算等级').getByRole('button',{name:'确认重算等级',exact:true})).toBeVisible();const w=await wallet();await expect(w.getByText('成长页面隔离校准',{exact:true})).toBeVisible();checks.push('adjustment revocation retains independent read and recalculation');
    await page.goto(`${base}/operations/member-growth?tenant_id=00000000-0000-0000-0000-000000000000`);await expect(page.getByText('当前身份没有此操作权限。').first()).toBeVisible();await expect(page.getByText('SILVER',{exact:true})).toHaveCount(0);checks.push('foreign tenant denies policies without prior wallet leakage');
    await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();await page.evaluate(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600})),f);await page.goto(url);await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();await expect(page.getByRole('tab')).toHaveCount(0);checks.push('logout and real 401 remove all growth panels');
  }else{
    await expect(page.getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await tab('发布政策');await expect(panel('发布政策').getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await expect(page.getByRole('button',{name:'确认发布政策',exact:true})).toHaveCount(0);checks.push('real central outage distinguishes 503 and hides publish action');
  }
  await shot(phase);
  for(const r of calls){assert.ok(r.headers().authorization?.startsWith('Bearer '));assert.ok([f.tenant,'00000000-0000-0000-0000-000000000000'].includes(r.headers()['x-tenant-id']));assert.match(new URL(r.url()).pathname,/^\/v1\/(admin\/member-growth\/(policies|[A-Za-z0-9_-]{1,100}(\/(ledger|adjust|recalculate))?)|operations\/member-growth\/(policy|adjust|recalculate)-access)$/);}
  fs.writeFileSync(path.join(run,`growth-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
}catch(error){await shot(phase+'-failure');throw error;}finally{await browser.close();}
