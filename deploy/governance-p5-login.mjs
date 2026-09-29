/** P5真实浏览器登录：输入隔离账户密码，仅使用应用自己的OIDC回调获取Token，禁止注入会话或Mock接口。 */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P5_PLAYWRIGHT_MODULE);
const root=process.env.P5_LOGIN_RUN,f=JSON.parse(fs.readFileSync(path.join(root,'login.json'),'utf8'));
const origin={management:'http://127.0.0.1:15275',business:'http://127.0.0.1:18605',oa:'http://127.0.0.1:15276'};
const HTTP={OK:200,UNAUTHORIZED:401,FORBIDDEN:403,UNAVAILABLE:503};
const checks=[],record=check=>{checks.push({check,result:'PASS'});fs.writeFileSync(path.join(root,'login-result.json'),JSON.stringify(checks,null,2));};
const browser=await chromium.launch(),context=await browser.newContext({viewport:{width:1440,height:1000}});
const authorizations=[],exchanges=[];
function observe(c){c.on('request',r=>{
 const u=new URL(r.url());if(u.origin!==f.authority)return;
 if(u.pathname==='/login/oauth/authorize'){assert.equal(u.searchParams.get('response_type'),'code');assert.equal(u.searchParams.get('code_challenge_method'),'S256');assert.ok(u.searchParams.get('state'));authorizations.push(u.searchParams.get('client_id'));}
 if(u.pathname==='/api/login/oauth/access_token'){const body=new URLSearchParams(r.postData()??'');assert.ok(body.get('code_verifier'));assert.equal(body.has('client_secret'),false);exchanges.push(body.get('client_id'));}
});}
observe(context);
async function password(p,kind){await p.waitForURL(f.authority+'/**');await p.locator('#username').fill(f.users[kind].name);await p.locator('#password').fill(f.users[kind].password);await p.getByRole('button',{name:'Sign In',exact:true}).click();}
async function session(p,kind){await expect.poll(()=>p.evaluate(key=>!!sessionStorage.getItem(key),`oidc.user:${f.authority}:${f.clients[kind]}`),{timeout:20000}).toBe(true);return p.evaluate(key=>JSON.parse(sessionStorage.getItem(key)),`oidc.user:${f.authority}:${f.clients[kind]}`);}
async function clean(p){await expect.poll(()=>{const u=new URL(p.url());return ['code','state','access_token','id_token'].every(k=>!u.searchParams.has(k));}).toBe(true);}
async function sso(p,kind){
 // Casdoor允许已有账户确认，但本函数绝不填写密码。
 for(let i=0;i<80;i++){
  if(p.url().startsWith(origin[kind])&&await p.evaluate(key=>!!sessionStorage.getItem(key),`oidc.user:${f.authority}:${f.clients[kind]}`).catch(()=>false))return;
  if(p.url().startsWith(f.authority)){
   const confirm=p.getByText('p1-external (P1 external)',{exact:true});if(await confirm.count())await confirm.first().click();
  }
  await p.waitForTimeout(250);
 }
 throw new Error('SSO callback did not finish');
}
async function shot(p,name){assert.ok(!p.url().startsWith(f.authority));await p.screenshot({path:path.join(root,name+'.png'),fullPage:true,animations:'disabled'});}
try{
 const portal=await context.newPage();await portal.goto(origin.management+'/governance?tenant='+f.tenant);await password(portal,'external');const management=await session(portal,'management');await clean(portal);
 await expect(portal.getByRole('link',{name:'进入 products',exact:true})).toBeVisible();await shot(portal,'interactive-portal');record('external password login completes actual portal PKCE callback and restores selected tenant');
 const popup=context.waitForEvent('page');await portal.getByRole('link',{name:'进入 products',exact:true}).click();const commerce=await popup;
 assert.equal(new URL(commerce.url()).searchParams.get('tenant_id'),f.tenant);await commerce.getByRole('button',{name:'企业登录',exact:true}).click();await sso(commerce,'business');const business=await session(commerce,'business');await clean(commerce);
 await expect(commerce.getByText('协作门店商品',{exact:true})).toBeVisible();assert.notEqual(management.access_token,business.access_token);
 const claim=t=>JSON.parse(Buffer.from(t.split('.')[1],'base64url').toString());assert.notDeepEqual(claim(management.access_token).aud,claim(business.access_token).aud);
 await shot(commerce,'interactive-commerce');record('portal application link carries context only; commerce SSO obtains its own audience without second password');
 await commerce.getByRole('button',{name:'查看资料',exact:true}).click();await expect(commerce.getByText('商品资料',{exact:true})).toBeVisible();await commerce.reload();await expect(commerce.getByText('资料版本',{exact:true})).toBeVisible();await shot(commerce,'interactive-detail-reload');
 await commerce.setViewportSize({width:390,height:844});await shot(commerce,'interactive-detail-390');await commerce.setViewportSize({width:1440,height:1000});record('commerce deep-linked drawer survives browser reload at desktop and mobile widths');
 await commerce.waitForLoadState('networkidle');
 const api=origin.business+'/v1/operations/scoped/product';
 for(const token of [null,management.access_token]){const response=await commerce.request.get(api,{headers:{'X-Tenant-Id':f.tenant,...(token?{Authorization:'Bearer '+token}:{})}});assert.equal(response.status(),HTTP.UNAUTHORIZED);}
 const oa=await context.newPage();await oa.goto(origin.oa+'/workbench');await oa.getByRole('button',{name:/使用 Casdoor 登录/}).click();await sso(oa,'oa');const oaSession=await session(oa,'oa');await clean(oa);
 await expect(oa.getByText('需要权限: oa:flow:todo:view',{exact:true})).toBeVisible();await shot(oa,'interactive-oa-external-denied');
 const denied=await oa.request.get(origin.oa+'/api/v1/flow/todos',{headers:{Authorization:'Bearer '+business.access_token}});assert.equal(denied.status(),HTTP.UNAUTHORIZED);assert.notDeepEqual(claim(oaSession.access_token).aud,claim(business.access_token).aud);
 record('same IdP session reaches OA under separate audience; partner remains denied employee todo; Cookie-only and cross-audience business API calls return 401');
 const other=await context.newPage();await other.goto(origin.management+'/governance?tenant=00000000-0000-0000-0000-000000000001');await sso(other,'management');await expect(other.getByRole('link',{name:'进入 products',exact:true})).toHaveCount(0);assert.equal(new URL(portal.url()).searchParams.get('tenant'),f.tenant);record('second portal tab cannot change first tab tenant or enumerate unassociated tenant applications');
 const invalid=await browser.newContext();const invalidPage=await invalid.newPage();await invalidPage.goto(origin.business+'/iam/callback?code=invalid-code&state=invalid-state');await expect(invalidPage.getByText('登录回调未完成，请重新登录',{exact:true})).toBeVisible();await clean(invalidPage);await expect(invalidPage.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();await shot(invalidPage,'invalid-state-cleaned');await invalid.close();record('unmatched callback state is rejected and code/state cleared with explicit login recovery');
 const internalContext=await browser.newContext({viewport:{width:1440,height:1000}});observe(internalContext);const internal=await internalContext.newPage();await internal.goto(origin.oa+'/workbench');await internal.getByRole('button',{name:/使用 Casdoor 登录/}).click();await password(internal,'internal');await session(internal,'oa');await clean(internal);await expect(internal.getByRole('heading',{name:'工作台',exact:true})).toBeVisible();await expect(internal.getByText('需要权限: oa:flow:todo:view',{exact:true})).toHaveCount(0);await shot(internal,'interactive-oa-internal');record('fresh internal account authenticates interactively into its legitimate OA workbench');
 if(!process.env.P5_SKIP_OUTAGE){
 fs.writeFileSync(path.join(root,'stop-server'),'');await expect.poll(()=>fs.existsSync(path.join(root,'server-stopped')),{timeout:15000}).toBe(true);
 await commerce.goto(origin.business+'/collaboration/products?tenant_id='+f.tenant);await expect(commerce.getByText('授权或业务服务暂不可用，请稍后重试',{exact:true}).first()).toBeVisible();await expect(commerce.getByText('协作门店商品',{exact:true})).toHaveCount(0);await shot(commerce,'authorization-outage');record('real auth-server outage shows explicit 503 and clears business data without fallback');
 fs.writeFileSync(path.join(root,'restart-server'),'');await expect.poll(()=>fs.existsSync(path.join(root,'server-restarted')),{timeout:60000}).toBe(true);await commerce.getByRole('button',{name:'刷新商品',exact:true}).click();await expect(commerce.getByText('协作门店商品',{exact:true})).toBeVisible();record('real dependency restoration and explicit refresh recover scoped products');
 }
 assert.ok(exchanges.includes(f.clients.business));assert.ok(exchanges.includes(f.clients.management));assert.ok(exchanges.includes(f.clients.oa));record('all three browser token exchanges use PKCE verifier and no client secret; exact-origin cross-origin token exchange succeeds');
 // 保存仅供后续同源打包验收使用的真实已登录会话；权限0600，绝不发布凭据。
 fs.writeFileSync(path.join(root,'business-session.json'),JSON.stringify(business),{mode:0o600});
 await internalContext.close();
}catch(error){console.error('P5 login assertion failed:',error.message?.split('\n').slice(0,5).join('\n'));process.exitCode=1;}
finally{await browser.close();}
