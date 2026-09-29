/** 在真实JAR静态壳/同源API上交互登录及深链/关闭开关验收；没有Vite和注入Token。 */
import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';import {createRequire} from 'node:module';
const {chromium,expect}=createRequire(import.meta.url)(process.env.P5_PLAYWRIGHT_MODULE);
const root=process.env.P5_LOGIN_RUN,f=JSON.parse(fs.readFileSync(path.join(root,'login.json'),'utf8')),origin='http://127.0.0.1:18606',checks=[];
const browser=await chromium.launch(),context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage();
const record=check=>{checks.push({check,result:'PASS'});fs.writeFileSync(path.join(root,'packaged-result.json'),JSON.stringify(checks,null,2));};
try{
 await expect.poll(()=>fs.existsSync(path.join(root,'packaged-ready')),{timeout:120000}).toBe(true);
 await page.clock.install();
 const response=await page.goto(origin+'/collaboration/products?tenant_id='+f.tenant);assert.equal(response.status(),200);await page.getByRole('button',{name:'企业登录',exact:true}).click();await page.waitForURL(f.authority+'/**');await page.locator('#username').fill(f.users.external.name);await page.locator('#password').fill(f.users.external.password);await page.getByRole('button',{name:'Sign In',exact:true}).click();await expect(page.getByText('协作门店商品',{exact:true})).toBeVisible({timeout:20000});
 assert.equal(new URL(page.url()).pathname,'/collaboration/products');assert.equal(new URL(page.url()).searchParams.has('code'),false);record('packaged Spring Boot serves SPA deep link, real OIDC callback and authenticated same-origin resource API');
 await page.getByRole('button',{name:'查看资料',exact:true}).click();await expect(page.getByText('资料版本',{exact:true})).toBeVisible();await page.reload();await expect(page.getByText('资料版本',{exact:true})).toBeVisible();await page.screenshot({path:path.join(root,'packaged-detail-1440.png'),fullPage:true,animations:'disabled'});await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(root,'packaged-detail-390.png'),fullPage:true,animations:'disabled'});record('packaged desktop/mobile detail reload returns real data; no development proxy required');
 const current=await page.evaluate(key=>JSON.parse(sessionStorage.getItem(key)),`oidc.user:${f.authority}:${f.clients.business}`);
 const cross=await context.request.fetch('http://127.0.0.1:18420/api/v1/flow/todos',{method:'OPTIONS',headers:{Origin:'https://unregistered.invalid','Access-Control-Request-Method':'GET','Access-Control-Request-Headers':'authorization'}});assert.equal(cross.headers()['access-control-allow-origin'],undefined);record('OA rejects unregistered cross-origin preflight; no wildcard credential CORS');
 await page.clock.fastForward('01:01:00');await expect(page.getByRole('button',{name:'企业登录',exact:true})).toBeVisible();await expect(page.getByText('协作门店商品',{exact:true})).toHaveCount(0);await page.screenshot({path:path.join(root,'session-expired.png'),fullPage:true});record('browser clock expiry removes data and returns to explicit login without automatic write retry');
 fs.writeFileSync(path.join(root,'disable-pilot'),'');await expect.poll(()=>fs.existsSync(path.join(root,'pilot-disabled')),{timeout:30000}).toBe(true);
 await expect.poll(async()=>{try{return (await context.request.get(origin+'/actuator/health')).status();}catch{return 0;}},{timeout:20000}).toBe(200);
 const denied=await context.request.get(origin+'/v1/operations/scoped/product',{headers:{Authorization:'Bearer '+current.access_token,'X-Tenant-Id':f.tenant}});assert.equal(denied.status(),401);record('restart with both central pilot flags disabled removes business entry authorization; former valid central token cannot fall back to legacy ACL');
}catch(error){console.error('Packaged assertion failed:',error.message?.split('\n').slice(0,6).join('\n'));process.exitCode=1;}
finally{await browser.close();}
