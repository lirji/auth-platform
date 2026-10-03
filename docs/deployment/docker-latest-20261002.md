# 本地 Docker 最新版本部署

任务 `DOCKER-LATEST-20261002`，目标为 Docker Desktop `desktop-linux` 的现有本机应用。用户明确授权部署相关项目；复用原数据库、卷、凭据与共享中间件，不扩大到门户中其他项目。

## 最终结果

部署与验证DONE / PASS。五个目标应用容器均运行最新产品源码并healthy；Commerce753cba1的精确远程CI completed/SUCCESS，随后已完成原运行库迁移及8602切换。最终Git文档收尾精确提交/推送结果存入私密deployment-result.json，不为引用本提交而无限追加状态提交。

| 服务 | 入口 | 当前制品 | 状态 |
| --- | --- | --- | --- |
| auth-console | http://localhost:5273/governance | governance-console:rev-353a309 | healthy / 实际浏览器PKCE与授权页PASS |
| governance-admin | 仅console共享回环8201 | governance-admin:rev-353a309 | healthy / 真实管理接口PASS |
| governance-projector | 无公开端口 | 与admin同镜像 | healthy / POLICY与DIRECTORY READY |
| project-portal | http://localhost:5274 | project-portal:rev-353a309 | healthy / HTTP及浏览器PASS |
| commerce app | http://localhost:8602 | commerce-platform:rev-753cba1 | healthy / HTTP、数据、制品及浏览器PASS |

Auth源码基线353a309；后续本任务Auth修改仅为进度/部署文档。Commerce753cba1只修正测试的批次预算预期，产品、SQL、前端与7173990保持一致。

## 验证与证据

- Auth干净Docker多阶段reactor、pnpm/TypeScript/Vite及门户镜像构建PASS。当前运行JAR SHA256 `44896784e39a8c4297d9e8196d4e0de85b001c354c87b4c36cb3be8b8082a8c8`；protocol104/core10/governance217与宿主对应类和资源逐字节MATCH。
- 真实Casdoor授权码+PKCE：管理state、本人应用/组织、授权审计200，匿名401。实际浏览器登录后进入授权管理，显示两类投影均已同步。控制台/门户HTTP入口与对应运行镜像的HTML及入口assets逐字节MATCH，HTML为no-cache。
- Commerce源码构建和制品核对PASS：17内部模块617项类/资源（不含META-INF）、204应用类/资源（不含UI）；61当前前端文件全量MATCH，protocol104与SDK12包括自动配置元数据全量MATCH。JAR SHA256 `1c8297adea9d3f38915c8ca1c5a1aaa1269282c33b7ed1099c30e5ebdcc21dca`。
- 本地全量测试不能冒称全PASS：首轮历史测试库553项中1生命周期扫描失败；独占MySQL的全量553项中该6项生命周期测试PASS，但1条发券测试错误地要求单轮必达20人。修正为合同规定的上限（另有500ms时间预算），保留最终23/22/1效果与并发去重断言，独立库发券7项全PASS。第二轮新schema的固定库名/迁移Owner配置拒绝与-am无匹配测试的失败也保留，未改业务源码、运行预算或迁移历史。
- [Commerce753cba1精确CI](https://github.com/lirji/commerce-platform/actions/runs/37080780416)：completed/SUCCESS。实际74个XML套件553项=548PASS/5既有条件skip/0失败错误；真实浏览器41PASS/20既有skip/0失败/0flaky。完整artifact已下载到私密remote-ci目录，与上列本地失败分开保存。
- 当前8602运行JAR全文件SHA与候选制品一致；HTTP读取全部61个前端文件与当前dist及运行JAR逐字节MATCH。6组原业务数据（订单、库存、权益、售后、退款、旅程实例）前后完全相同；原/me身份字段不变，最新契约增加executionId=null。3条匿名业务请求401/403，原数据库账号/密码/地址密钥保留，运行容器无迁移root凭据。
- 实际浏览器以原管理员会话只读进入经营总览，1440和390的document/body宽度分别为1440和390，0页面错误；最终截图已实际查看。最初尚在加载的截图另留，不作为最终视觉证据。

私密证据与备份：Auth `.local/docker-latest-20261002/`、Commerce同名目录。包括所有失败/成功日志、独立XML、旧容器完整配置、镜像回退标签、SQL/API快照、浏览器截图、JAR及静态文件指纹。凭据、业务响应、数据库备份不入Git。

## 数据、依赖与恢复边界

Auth治理库、原IdP/授权图库和Commerce运行库均已备份。部署期间原18090身份与18544图容器不再存在；按既有私密配置和同一数据库恢复，未重新生成密码、用户、数据库或图模型，并设置unless-stopped；共享旧8000/8543实例保持。

原POLICY已连续5次失败而BLOCKED。通过真实管理Token调用受审计retry-strict，随后真实投影恢复READY、failures=0。没有直接把状态写成READY、清空旧意图或自动创建业务Grant/Policy。

Commerce原本地运行库已由V45升级至V72，独立迁移Owner实际执行26个脚本（仓库无V69）。迁移前后Flyway历史校验PASS；仅在迁移前validate允许尚待执行的pending项，未repair、改校验和或放宽共享MySQL全局权限。应用仍使用原账号/地址密钥，迁移Owner凭据未进入运行容器。

五个旧应用镜像保留 `local-rollback/<container-name>:before-20261002`；旧配置/备份保留。代码回退需检查V72结构兼容，不能将镜像回退当成数据库恢复；没有自动还原或清空数据。

本次沿用当前本地业务配置与分区：auth_governance仍为原local-commerce示例v1（2菜单/3能力/2资源）；已验收122能力/34角色的隔离test出版证据仍保留。8602默认Compose未启用中央鉴权接管，不因部署最新代码自动导入另一测试分区或授予业务权限。

## 后续启动与保留

Auth私密runtime.env已绑定已部署的rev-353a309镜像，Commerce原compose.env的COMMERCE_IMAGE_TAG已更新为rev-753cba1。`bash deploy/governance/run.sh status`与原商城Compose可查看当前状态。新源码到达时仍需按原build/update流程重新构建，不能把只restart当作更新代码。两个本任务专用验证MySQL已停止，原验证数据/失败记录/回退镜像/数据库备份保留；没有创建新worktree或删除既有工作树。
