# P5-07 TEST_RESULT

状态：PASS。本片覆盖真实交互OIDC/SSO、运行和错误边界，生产部署N/A。

- auth全量`-Pgovernance-it verify`：本次生成的231单测、101 PostgreSQL测试，零失败。仅统计本轮报告mtime，不混入历史其他profile报告。
- commerce全量`mvn test`：本次388项，383执行通过、5条件跳过，零失败。5项为3个BackgroundRuntimeBenchmark、1个EventDispatchProfile、1个EventSchedulingBenchmark，未启用对应性能profile；不把跳过计为PASS，不宣称性能目标达标。
- `login-e78a39232d26`：实际外部用户名/密码登录门户；商城与OA通过IdP已有账户确认SSO，各自获取不同audience Token；全部授权码+S256 PKCE且浏览器没有client_secret。Cookie-only、跨受众API401；OA外部待办拒绝，内部独立登录可查看真实待办。标签页组织独立，错误state回调清码并提供重新登录。
- 真实停auth-server后商城503且清除数据；恢复进程后人工刷新恢复商品。未修改授权判定、未使用API Mock或Token注入。初轮10项完成，最终UI修正后8项重跑完成；两项停机验证保留在initial-result。
- `login-pack-f2c3131a`：5项真实打包验证PASS，JAR同源静态壳/资源API、真实PKCE回调、深链重载、1440/390详情、OA非法Origin无CORS许可、浏览器时钟推进触发登录到期清除、双试点开关关闭重启后旧合法Token401。时间推进用于浏览器会话过期，不冒充IdP真实时钟推进；服务端Token期限拒绝沿用P1/P3已有测试。
- `http-3a8ef5cd25e6`内部最终25 HTTP和7浏览器检查PASS：未保存关闭需确认，继续编辑保留原值，真实提交版本0→1、冲突/越范围拒绝、撤权后读保留写拒绝。确认弹层动画完成后截图已实际查看。
- 两前端最终构建、4条auth上下文Node测试、Python/JS语法、diff检查通过。Code Hygiene通过，保留既有全仓formatter不可用限制；商城本片前端Prettier通过。
- 截图逐张打开核对：门户、商品列表、详情、OA内部/外部、错误state、503、过期、打包1440/390。详情是实际字段加载完成后的截图；移动抽屉在视口内、字段纵向可读，主次按钮和错误反馈清楚。

## 失败与修正记录

1. 原商城ItemRetryIsolationTest默认两秒退避小于共享测试库五轮扫描耗时，首次全量失败。先断言生产策略生成未来retryAt，再将测试行退避窗口固定300秒，继续用真实SQL和真实车道验证；生产策略未改。两次定向命令因上游无匹配测试退出，不能计为测试通过；最终完整reactor通过。
2. 浏览器脚本原先错误要求授权码流程默认nonce、遗漏Casdoor已有账户确认、OA按钮图标参与accessible name，已按实际库/界面修正，仍验证state和PKCE，不降低跨受众校验。
3. 开发模式StrictMode重复挂载发出废弃请求，引发实时鉴权并发槽503。前端在微任务中检查已取消effect再发送，保留真实请求和fail-closed；重载必须等到实际资料字段完成。先前错误截图不作为成功证据。
4. 打包运行器COMMERCE_PORT重复参数、重启时TIME_WAIT被误判占用均修复；新运行目录全流程通过，活动端口仍不能被覆盖。
5. 内部未保存测试首次使用英文Close找不到中文界面关闭控件；改为既有Drawer关闭控件；随后确认标题同时包含辅助标题/显示标题导致strict locator歧义，使用明确的可见confirm-title重跑。

证据：`evidence/p5-07/`；运行边界/复现见[P5_RUNTIME](P5_RUNTIME.md)。私有凭据和原始日志不入库。不存在生产性能达标声明，P6未执行。

SKILL_HANDOFF: slice=P5-07, status=COMPLETED, gate=PASS_WITH_ASSUMPTIONS（仅既有formatter限制）, next=task-git-delivery/ci-cd-gate；P6前停止。
