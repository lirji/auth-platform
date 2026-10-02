/** 实际Owner目录/34角色浏览验收：真实HUMAN PKCE，只读表单，不创建Grant或Policy。 */
import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const {chromium,expect}=createRequire(import.meta.url)(process.env.CP_PLAYWRIGHT_MODULE)
const attempt=process.env.CP_ATTEMPT;assert.match(attempt,/^[a-f0-9]{32}$/)
const run=process.env.CP_RUN,f=JSON.parse(fs.readFileSync(path.join(run,'browser.json'),'utf8'))
assert.equal(f.origin,'http://127.0.0.1:21665');assert.equal(f.admin,'http://127.0.0.1:21662')
const browser=await chromium.launch({headless:true}),checks=[],shots=[],contexts=[]
const record=name=>{checks.push({check:name,result:'PASS'});fs.writeFileSync(path.join(run,'browser-progress-'+attempt+'.json'),JSON.stringify(checks,null,2),{mode:0o600})}
const route=`/governance/access?tenant=${f.partition.tenant_id}&application=commerce&environment=test`
async function login(kind){
 const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();contexts.push(context)
 const pkce={authorize:false,exchange:false}
 context.on('request',request=>{const u=new URL(request.url());if(u.origin!==f.authority)return
  if(u.pathname==='/login/oauth/authorize'){assert.equal(u.searchParams.get('code_challenge_method'),'S256');assert.ok(u.searchParams.get('state'));pkce.authorize=true}
  if(u.pathname==='/api/login/oauth/access_token'){const body=new URLSearchParams(request.postData()??'');assert.ok(body.get('code_verifier'));assert.ok(!body.has('client_secret'));pkce.exchange=true}})
 await page.goto(f.origin+route);await page.waitForURL(f.authority+'/**');await page.locator('#username').fill(f.users[kind].name);await page.locator('#password').fill(f.users[kind].password);await page.getByRole('button',{name:'Sign In',exact:true}).click();await page.waitForURL(f.origin+'/governance/**',{timeout:30000});await expect.poll(()=>pkce.authorize&&pkce.exchange).toBe(true);record(kind+' actual HUMAN password PKCE');return page
}
async function choose(page,label,value){
 const combo=page.getByRole('combobox',{name:label,exact:true});await expect(combo).toBeEnabled();await combo.click()
 await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({hasText:value}).first().click()
}
async function screenshot(page,name){
 const file=path.join(run,name+'-'+attempt+'.png');await page.screenshot({path:file,fullPage:true,animations:'disabled'});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'page overflow');shots.push({name,file,width:page.viewportSize().width})
}
async function closeEditor(page){await page.getByRole('dialog').getByRole('button',{name:'Close',exact:true}).click();const discard=page.getByRole('button',{name:'放弃编辑',exact:true});await expect(discard).toBeVisible();await discard.click();await expect(page.getByRole('dialog')).toHaveCount(0)}
async function rangeOptions(page,resource){
 await choose(page,'资源类型',resource);await page.getByRole('combobox',{name:'数据范围',exact:true}).click()
 const options=page.locator('.ant-select-dropdown:visible .ant-select-item-option');await expect(options).toHaveCount(1);await expect(options).toHaveText(['当前企业全部资源']);await options.first().click()
}
try{
 const page=await login('owner');await expect(page.getByText('当前已发布目录 · v2',{exact:true})).toBeVisible()
 const token=await page.evaluate(key=>JSON.parse(sessionStorage.getItem(key)).access_token,`oidc.user:${f.authority}:${f.client}`)
 const params=f.partition,headers={Authorization:'Bearer '+token}
 const catalog=await(await page.request.get(f.admin+'/api/governance/v1/access/published-catalog',{headers,params})).json()
 assert.equal(catalog.capabilities.length,122);assert.equal(catalog.resource_types.length,21);assert.equal(catalog.menus.length,f.catalog.menus.length);assert.deepEqual(catalog.menus,f.catalog.menus)
 assert.ok(catalog.resource_types.every(type=>type.scope_supported));assert.ok(catalog.capabilities.every(cap=>cap.grantable&&!cap.disabled))
 const state=await(await page.request.get(f.admin+'/api/governance/v1/access/state',{headers,params})).json()
 assert.equal(state.roles.length,34);assert.equal(state.next_role_cursor,null);assert.equal(state.grants.length,0)
 assert.deepEqual(state.roles.map(role=>role.role_code).sort(),f.roles.map(role=>role.role_code).sort())
 assert.equal(await page.getByRole('button',{name:'下一页角色',exact:true}).count(),0)
 record('actual HTTP 122/21/current menu union;100 page bound displays all34 fixed snapshots;zero grants')
 for(const width of [1440,390,320]){await page.setViewportSize({width,height:1000});await screenshot(page,'published-catalog-'+width)}
 await page.setViewportSize({width:1440,height:1000});await page.getByRole('button',{name:'创建角色版本',exact:true}).click()
 await choose(page,'角色资源类型','product');await page.getByRole('combobox',{name:'菜单浏览上下文',exact:true}).click();await page.getByText('/operations/products',{exact:false}).last().click()
 await expect(page.getByRole('checkbox',{name:'commerce.product.read',exact:true})).toBeVisible();await expect(page.getByRole('checkbox',{name:'commerce.catalog.operate',exact:true})).toHaveCount(0)
 await page.getByRole('checkbox',{name:'commerce.product.read',exact:true}).check();await expect(page.getByText('菜单仅用于查找。已明确选择 1 项能力；搜索与菜单切换保留选择。',{exact:true})).toBeVisible()
 for(const width of [1440,390,320]){await page.setViewportSize({width,height:1000});await screenshot(page,'product-menu-selection-'+width)}await closeEditor(page)
 record('actual product.read menu selection with no catalog.operate alias and no role write')
 const byId=[...state.roles].sort((a,b)=>a.id.localeCompare(b.id)),last=byId.at(-1)
 await page.setViewportSize({width:1440,height:1000});await page.getByRole('button',{name:'授予成员',exact:true}).click();await choose(page,'固定角色版本',last.role_code);await closeEditor(page)
 record('last actual fixed role is selectable in Grant consumer')
 for(const resource of ['journey','ops_page','commerce_tenant']){
  const candidate=state.roles.find(role=>role.capabilities.every(code=>catalog.capabilities.find(cap=>cap.code===code).resource_type===resource))
  // dashboard目前不在既有99能力模板内，只验证角色编辑资源可选，不能暗中增加角色或Grant。
  if(!candidate){assert.equal(resource,'commerce_tenant');await page.getByRole('button',{name:'创建角色版本',exact:true}).click();await choose(page,'角色资源类型',resource);await expect(page.getByRole('checkbox',{name:'commerce.dashboard.read',exact:true})).toBeVisible();await closeEditor(page);continue}
  await page.getByRole('button',{name:'授予成员',exact:true}).click();await choose(page,'固定角色版本',candidate.role_code);await rangeOptions(page,resource);await screenshot(page,'grant-'+resource+'-1440');await closeEditor(page)
  await page.goto(f.origin+route.replace('/access?','/policies?'));await page.getByRole('button',{name:'创建申请策略',exact:true}).click();await choose(page,'策略固定角色版本',candidate.role_code);await rangeOptions(page,resource);for(const width of [1440,390,320]){await page.setViewportSize({width,height:1000});await screenshot(page,'policy-'+resource+'-'+width)}await closeEditor(page);await page.goto(f.origin+route);await page.setViewportSize({width:1440,height:1000})
 }
 record('both shared ScopeFields consumers show actual new finite tenant-only resources;outside-template dashboard remains unassigned')
 const final=await(await page.request.get(f.admin+'/api/governance/v1/access/state',{headers,params})).json();assert.deepEqual(final,state)
 const ordinary=await login('ordinary');await expect(ordinary.getByText('无权访问当前范围，请检查成员关系或管理委派。',{exact:true})).toBeVisible();await expect(ordinary.getByRole('button',{name:'创建角色版本',exact:true})).toHaveCount(0)
 record('ordinary HUMAN cannot read manager catalog;UI browsing writes no role/Grant/Policy')
 fs.writeFileSync(path.join(run,'browser-result-'+attempt+'.json'),JSON.stringify({status:'PASS',checks,shots},null,2),{mode:0o600})
}catch(error){for(let i=0;i<contexts.length;i++)for(const page of contexts[i].pages())if(!page.isClosed())await page.screenshot({path:path.join(run,`failure-${i}-${attempt}.png`),fullPage:true});throw error}
finally{for(const context of contexts)await context.close();await browser.close()}
