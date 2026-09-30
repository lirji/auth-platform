/** 两个权益页真实验收；仅丢弃真实成功响应，不伪造业务数据或授权结果。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P6_PLAYWRIGHT_MODULE);
const run=process.env.P6_RUN,phase=process.env.P6_PHASE,kind=process.env.P6_KIND;
const Kind={DEFINITIONS:'definitions',INSTANCES:'instances'},Phase={WRITE:'write-only',READ:'read',REVOKED:'revoked',OUTAGE:'outage'};
assert(Object.values(Kind).includes(kind));assert(Object.values(Phase).includes(phase));
const f=JSON.parse(fs.readFileSync(path.join(run,'entitlements-ui.json'),'utf8')),defs=kind===Kind.DEFINITIONS;
const slug=defs?'entitlement-definitions':'entitlements',directoryTab=defs?'权益定义目录':'权益实例目录',writeTab=defs?'创建权益定义':'处理权益补偿';
const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'UTC'});
if(!(phase===Phase.WRITE && f.interactive))await context.addInitScript(f=>{
 const key=`oidc.user:${f.authority}:${f.client}`;if(sessionStorage.getItem(key))return;
 const profile=JSON.parse(atob(f.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));
 sessionStorage.setItem(key,JSON.stringify({access_token:f.token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));
},f);
const page=await context.newPage(),base='http://127.0.0.1:18665',url=`${base}/operations/${slug}?tenant_id=${f.tenant}`;
const checks=[],calls=[];
const tab=name=>page.getByRole('tab',{name,exact:true}).click(),panel=name=>page.getByRole('tabpanel',{name,exact:true});
const shot=name=>page.screenshot({path:path.join(run,`entitlements-${kind}-${name}.png`),fullPage:true,animations:'disabled'});
async function narrow(name){await page.setViewportSize({width:390,height:844});await shot('390-'+name);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.setViewportSize({width:1440,height:1000});}
async function directory(){await tab(directoryTab);const p=panel(directoryTab);if(defs){await p.getByLabel('查询门店编号',{exact:true}).fill(f.store);await p.getByRole('button',{name:'查询权益定义',exact:true}).click();}return p;}
const local=value=>new Date(value).toISOString().slice(0,16);
async function fillDefinition(p,id){for(const [label,value] of [['权益定义编号',id],['定义版本','1'],['门店编号',f.store],['权益名称','权益定义UI'],['每份单位数','7'],['发行额度','15'],['生效后有效天数','30'],['开始时间',local(f.from)],['截止时间',local(f.to)]])await p.getByLabel(label,{exact:true}).fill(value);}
async function fillResolution(p,id,conclusion){await p.getByLabel('权益实例编号',{exact:true}).fill(id);await p.getByRole('radio',{name:conclusion,exact:true}).check();await p.getByLabel('补偿凭据',{exact:true}).fill('actual-ui-proof-'+id);}
try{
 page.on('request',r=>{if(r.url().includes('/v1/'))calls.push(r);});await page.goto(url);
 if(phase===Phase.WRITE && f.interactive){await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');await page.locator('#username').fill(f.user.name);await page.locator('#password').fill(f.user.password);await page.getByRole('button',{name:'Sign In',exact:true}).click();await page.waitForURL(`${base}/operations/${slug}?**`,{timeout:30000});assert.equal(new URL(page.url()).searchParams.get('tenant_id'),f.tenant);assert.ok(!new URL(page.url()).searchParams.has('code'));checks.push('real PKCE returns to fixed entitlement page');}
 if(phase===Phase.WRITE){
  const d=await directory();await expect(d.getByText('当前身份没有此操作权限。').first()).toBeVisible();await tab(writeTab);const p=panel(writeTab),submit=p.getByRole('button',{name:'确认'+writeTab,exact:true});await expect(submit).toBeVisible();await submit.click();
  if(defs){
   await expect(p.getByText('请输入1至10000的整数',{exact:true})).toBeVisible();await fillDefinition(p,'ce05-entitlement-a');await p.getByLabel('截止时间',{exact:true}).fill(local(f.from));await submit.click();await expect(p.getByText('截止时间必须晚于开始时间',{exact:true})).toBeVisible();await p.getByLabel('截止时间',{exact:true}).fill(local(f.to));
  }else{
   await expect(p.getByText('请选择补偿结论',{exact:true})).toBeVisible();await expect(p.getByText('请输入不超过128字的补偿凭据',{exact:true})).toBeVisible();await fillResolution(p,'ce05-grant:a','已恢复');
  }
  await shot('form');await narrow('form');checks.push('independent write without read validates required fields and original business bounds');
  await submit.click();await expect(p.getByText(defs?'请核对权益定义编号和版本后，再提交操作':'请核对实例状态与补偿凭据后，再提交操作',{exact:true})).toBeVisible();await shot('conflict');
  if(defs){await expect(p.getByLabel('权益名称',{exact:true})).toHaveValue('权益定义UI');await p.getByLabel('权益定义编号',{exact:true}).fill('ce05-entitlement-ui-c');}
  else{await expect(p.getByLabel('补偿凭据',{exact:true})).toHaveValue('actual-ui-proof-ce05-grant:a');await fillResolution(p,'ce05-ui-grant:c','已恢复');}
  checks.push('actual immutable version or compensated-state conflict retains editable input');
  const pattern=defs?'**/v1/admin/entitlement-definitions':'**/v1/admin/entitlements/*/resolve',attempts=[];
  await page.route(pattern,async route=>{attempts.push({key:route.request().headers()['idempotency-key'],body:route.request().postData(),url:route.request().url()});if(attempts.length===1){const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');}else await route.continue();});
  await submit.click();await expect(p.getByText('操作结果尚未确认，请保留原输入重试',{exact:true})).toBeVisible();await expect(p.getByLabel(defs?'权益定义编号':'权益实例编号',{exact:true})).toBeDisabled();await shot('unknown');await page.getByRole('button',{name:'退出登录',exact:true}).click();await shot('leave-confirmation');await page.getByRole('dialog').getByRole('button',{name:'留在当前页',exact:true}).click();await tab(directoryTab);await tab(writeTab);await p.getByRole('button',{name:'原样重试',exact:true}).click();await expect(p.getByText(writeTab+'成功',{exact:true})).toBeVisible();assert.equal(attempts.length,2);assert.ok(attempts[0].key);assert.deepEqual(attempts[0],attempts[1]);
  const body=JSON.parse(attempts[0].body);if(defs){assert.equal(body.units,7);assert.equal(body.quota,15);assert.equal(body.validityDays,30);}else{assert.equal(body.resolution,'RECOVERED');assert.equal(body.reference,'actual-ui-proof-ce05-ui-grant:c');assert.ok(attempts[0].url.includes('ce05-ui-grant%3Ac'));}
  checks.push('committed response loss retains original key/body/encoded target across tabs and canceled logout');await page.unroute(pattern);await shot('success');
  if(!defs){await fillResolution(p,'ce05-ui-grant:d','确认损失');await submit.click();await expect(p.getByText('处理权益补偿成功',{exact:true})).toBeVisible();await expect(p.getByText('ce05-ui-grant:d',{exact:true})).toBeVisible();checks.push('both explicit compensation conclusions submit without instance read');}
 }else if(phase===Phase.READ){
  const p=await directory();await expect(p.getByRole('cell',{name:defs?'权益定义UI':'ce05-ui-grant:c',exact:true})).toBeVisible();
  if(defs){await expect(p.getByRole('row')).toHaveCount(5);await expect(p.getByRole('cell',{name:'ce05-entitlement-a',exact:true})).toHaveCount(0);}else{await expect(p.getByRole('cell',{name:'ce05-ui-grant:d',exact:true})).toBeVisible();await expect(p.getByRole('row').filter({has:page.getByRole('cell',{name:'ce05-ui-grant:c',exact:true})}).getByRole('cell',{name:'COMPENSATED',exact:true})).toBeVisible();}
  await expect(p.getByRole('button',{name:defs?'下一批定义':'下一批实例',exact:true})).toBeDisabled();await shot('directory');await narrow('directory');checks.push('actual directory displays latest definition or actual instance state, units, source and stable terminal cursor');
  if(defs){await p.getByLabel('查询门店编号',{exact:true}).fill(f.other);await p.getByRole('button',{name:'查询权益定义',exact:true}).click();await expect(p.getByRole('cell',{name:'ce05-entitlement-a',exact:true})).toBeVisible();await expect(p.getByRole('cell',{name:'权益定义UI',exact:true})).toHaveCount(0);checks.push('store change clears prior definition data and does not leak older version');}
 }else if(phase===Phase.REVOKED){
  await tab(writeTab);await expect(panel(writeTab).getByText('当前没有'+writeTab+'权限，可联系管理员申请',{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'确认'+writeTab,exact:true})).toHaveCount(0);const p=await directory();await expect(p.getByRole('cell',{name:defs?'权益定义UI':'ce05-ui-grant:c',exact:true})).toBeVisible();checks.push('revoked write leaves independent directory read');
  await page.goto(`${base}/operations/${slug}?tenant_id=00000000-0000-0000-0000-000000000000`);const foreign=await directory();await expect(foreign.getByText('当前身份没有此操作权限。').first()).toBeVisible();await expect(page.getByText(defs?'权益定义UI':'ce05-ui-grant:c',{exact:true})).toHaveCount(0);checks.push('foreign tenant denies and clears prior data');await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();await page.evaluate(f=>sessionStorage.setItem(`oidc.user:${f.authority}:${f.client}`,JSON.stringify({access_token:'invalid',profile:{sub:'invalid'},expires_at:Math.floor(Date.now()/1000)+3600})),f);await page.goto(url);if(defs)await directory();await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();await expect(page.getByRole('tab')).toHaveCount(0);checks.push('logout and actual 401 unmount entitlement workspace');
 }else{
  const p=await directory();await expect(p.getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await tab(writeTab);await expect(panel(writeTab).getByText('授权或业务服务暂不可用，请稍后重试').first()).toBeVisible();await expect(page.getByRole('button',{name:'确认'+writeTab,exact:true})).toHaveCount(0);checks.push('actual central outage fails closed for directory and independent action hint');
 }
 await shot(phase);for(const r of calls){assert.ok(r.headers().authorization?.startsWith('Bearer '));assert.ok([f.tenant,'00000000-0000-0000-0000-000000000000'].includes(r.headers()['x-tenant-id']));assert.match(new URL(r.url()).pathname,defs?/^\/v1\/(admin\/entitlement-definitions|operations\/entitlement-definitions\/create-access)$/:/^\/v1\/(admin\/entitlements(?:\/[^/]+\/resolve)?|operations\/entitlements\/resolve-access)$/);}fs.writeFileSync(path.join(run,`entitlements-${kind}-${phase}-result.json`),JSON.stringify({checks},null,2),{mode:0o600});
}catch(error){await shot(phase+'-failure');throw error;}finally{await browser.close();}
