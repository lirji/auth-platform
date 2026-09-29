/** P5外部真实浏览器：不模拟API，所有可见数据来自真实Owner/治理/审批服务。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P5_PLAYWRIGHT_MODULE);
const root=process.env.P5_COMMERCE_RUN,phase=process.env.P5_COMMERCE_PHASE;
const f=JSON.parse(fs.readFileSync(path.join(root,'commerce-ui.json'),'utf8'));
const Phase={INVITATION:'invitation',REQUEST:'request',PENDING:'pending',ACTIVE:'active',REVOKED:'revoked'};
const HTTP={OK:200,FORBIDDEN:403,ACCEPTED:202,UNAVAILABLE:503};
const portal='http://127.0.0.1:15275',business='http://127.0.0.1:18605';
const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000},acceptDownloads:true});
await context.addInitScript(f=>{
 for(const [client,token]of [[f.management_client,f.management_token],[f.business_client,f.business_token]])if(token){const profile=JSON.parse(atob(token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/')));sessionStorage.setItem(`oidc.user:${f.authority}:${client}`,JSON.stringify({access_token:token,token_type:'Bearer',scope:'openid profile',profile,expires_at:profile.exp}));}
},f);
const page=await context.newPage(),checks=[];const record=check=>checks.push({check,result:'PASS'});
const url=`${business}/collaboration/products?tenant_id=${f.tenant}`;
const requestUrl=()=>`${portal}/governance/requests?tenant=${f.tenant}&application=commerce&environment=test&request=${f.request_id}`;
const save=(name,value)=>fs.writeFileSync(path.join(root,name),JSON.stringify(value,null,2),{mode:0o600});
async function call(route,body){return page.request[body===undefined?'get':'post'](business+'/v1/operations/scoped/product'+route,{headers:{Authorization:'Bearer '+f.business_token,'X-Tenant-Id':f.tenant,'Idempotency-Key':crypto.randomUUID()},...(body===undefined?{}:{data:body})});}
async function shot(name){await page.screenshot({path:path.join(root,name+'.png'),fullPage:true,animations:'disabled'});}
try {
 if(phase===Phase.INVITATION){
  await page.goto(portal+'/invitations/accept');await page.getByLabel('邀请编号',{exact:true}).fill(f.invitation);await page.getByLabel('邀请证明',{exact:true}).fill(f.proof);
  const response=page.waitForResponse(r=>r.url().endsWith('/invitations/accept')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'接受邀请',exact:true}).click();const accepted=await response;assert.equal(accepted.status(),HTTP.OK);save('accepted.json',await accepted.json());
  await expect(page.getByText('已加入邀请对应组织',{exact:true})).toBeVisible();await shot('external-invitation-accepted');
  await page.goto(`${portal}/governance?tenant=${f.tenant}`);await expect(page.getByText('当前没有关联应用',{exact:true})).toBeVisible();record('exact invited identity accepts PARTNER membership in browser without implicit app access');
 } else if(phase===Phase.REQUEST){
  await page.goto(url);await expect(page.getByText('协作门店商品',{exact:true})).toBeVisible();await expect(page.getByText('当前可查询商品；导出需申请独立的限时权限',{exact:true})).toBeVisible();
  await expect(page.getByText('其他门店保密商品',{exact:true})).toHaveCount(0);
  for(const id of ['P002','FOREIGN'])assert.equal((await call('/resources/'+id)).status(),HTTP.FORBIDDEN);
  assert.equal((await call('/exports',{})).status(),HTTP.FORBIDDEN);
  assert.equal((await call('/resources/P001',{expectedVersion:0,title:'forged',category:'C',brand:'B'})).status(),HTTP.FORBIDDEN);
  await shot('external-products-readonly');record('partner sees only assigned-store product; direct other-store/tenant reads, edit and unapproved export denied');
  const popup=context.waitForEvent('page');await page.getByRole('link',{name:'申请限时导出',exact:true}).click();const request=await popup;
  await request.getByRole('button',{name:'申请权限',exact:true}).click();await request.locator('.ant-select').filter({has:request.getByRole('combobox',{name:'可申请策略',exact:true})}).click();await request.getByTitle('exporter · v1 · 策略1',{exact:true}).click();
  await request.getByLabel('申请有效分钟数（从提交时开始）',{exact:true}).fill('10');await request.getByLabel('申请原因',{exact:true}).fill('协作门店商品核对，申请限时导出');
  const response=request.waitForResponse(r=>r.url().endsWith('/requests')&&r.request().method()==='POST');await request.getByRole('button',{name:'提交申请',exact:true}).click();const submitted=await response;assert.equal(submitted.status(),HTTP.ACCEPTED);save('submitted.json',await submitted.json());
  await expect(request.getByText('实际状态：已提交，等待流程启动',{exact:true})).toBeVisible();await request.screenshot({path:path.join(root,'external-request-pending.png'),fullPage:true,animations:'disabled'});record('business application links to real self request form; immutable store export policy submitted through browser');
 } else if(phase===Phase.PENDING){
  await page.goto(requestUrl());await expect(page.getByText(/实际状态：(权限同步失败|等待权限生效)/)).toBeVisible();await shot('external-approved-not-active');
  assert.equal((await call('/exports',{})).status(),HTTP.UNAVAILABLE);record('approved but failed projection remains visibly pending/failed; actual commerce cannot export');
 } else if(phase===Phase.ACTIVE){
  await page.goto(requestUrl());await expect(page.getByText('实际状态：已实际生效',{exact:true})).toBeVisible();await shot('external-request-active');
  await page.goto(url);await expect(page.getByText('协作门店商品',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'新建商品导出',exact:true}).click();await expect(page.getByText('已提交，尚未开始',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'开始导出',exact:true}).click();await expect(page.getByText('处理中',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'继续处理（每次最多50行）',exact:true}).click();await expect(page.getByText('内容已生成',{exact:true})).toBeVisible();
  const download=page.waitForEvent('download');await page.getByRole('button',{name:'下载商品文件',exact:true}).click();const file=await download;const data=JSON.parse(fs.readFileSync(await file.path(),'utf8'));assert.deepEqual(data.rows.map(r=>r.resourceId),['P001']);save('export-job.json',{id:data.jobId});
  await shot('external-export-completed');await page.reload();await expect(page.getByText('内容已生成',{exact:true})).toBeVisible();await page.setViewportSize({width:390,height:844});await shot('external-export-390');
  record('real ACTIVE approval enables durable export submit/start/batch/download; file contains only assigned-store product and URL reload restores job');
 } else if(phase===Phase.REVOKED){
  const job=JSON.parse(fs.readFileSync(path.join(root,'export-job.json'),'utf8'));await page.goto(url+'&export='+job.id);await expect(page.getByText('协作门店商品',{exact:true})).toBeVisible();await expect(page.getByText('当前可查询商品；导出需申请独立的限时权限',{exact:true})).toBeVisible();
  for(const route of ['/exports/'+job.id,'/exports/'+job.id+'/download'])assert.equal((await call(route)).status(),HTTP.FORBIDDEN);
  assert.equal((await call('/exports',{})).status(),HTTP.FORBIDDEN);await shot('external-revoked-read-remains');
  await page.goto(requestUrl());await expect(page.getByText('实际状态：本来源已回收',{exact:true})).toBeVisible();await shot('external-request-revoked');record('actual source revoke denies historical job/download and new export while independent product read remains');
 }
 save('commerce-'+phase+'-result.json',checks);
} catch(error){if(phase!==Phase.INVITATION)await shot('commerce-'+phase+'-failure');throw error;}
finally {await browser.close();}
