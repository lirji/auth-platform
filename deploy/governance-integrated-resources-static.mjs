/** 本片独有制品预览只服务当前dist；治理API同源代理到专属管理端口。 */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
const root=path.resolve('auth-console/dist'),port=Number(process.env.IR_UI),admin=Number(process.env.IR_ADMIN)
if(port!==21665||admin!==21662)throw new Error('IR-01 owned ports required')
http.createServer((req,res)=>{
 if(req.url.startsWith('/api/governance/')){const proxy=http.request({hostname:'127.0.0.1',port:admin,path:req.url,method:req.method,headers:req.headers},up=>{res.writeHead(up.statusCode,up.headers);up.pipe(res)});proxy.on('error',()=>{res.writeHead(503);res.end()});req.pipe(proxy);return}
 if(req.url==='/healthz'){res.writeHead(200,{'Content-Type':'text/plain'});res.end('ok');return}
 const relative=decodeURIComponent(new URL(req.url,'http://localhost').pathname).replace(/^\//,'');let file=path.resolve(root,relative)
 if(!file.startsWith(root+path.sep)){res.writeHead(400);res.end();return}
 if(!fs.existsSync(file)||fs.statSync(file).isDirectory())file=path.join(root,'index.html')
 const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png'}
 res.writeHead(200,{'Content-Type':mime[path.extname(file)]??'application/octet-stream','Cache-Control':'no-store'});fs.createReadStream(file).pipe(res)
}).listen(port,'127.0.0.1')
