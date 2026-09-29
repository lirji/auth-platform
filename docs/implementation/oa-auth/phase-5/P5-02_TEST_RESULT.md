# P5-02 TEST_RESULT

结果：PASS。基线74a39c1；受保护角色版本、成员精确授予、版本影响、Owner清单预览发布及投影状态已连真实接口。原数据库/协议不迁移，不自动升级旧Grant。新增展示契约见CONTRACTS_P5_PORTAL。

## 验证

- `GOVERNANCE_TEST_CONFIG=... ./mvnw -q -pl auth-platform-admin -am -Pgovernance-it -Dit.test=PortalPostgresIT -Dfailsafe.failIfNoSpecifiedTests=false verify`：8项真实PG测试及该reactor单测PASS。含无委派拒绝、跨租户成员/角色ID拒绝、固定版本引用、禁用能力、投影未就绪关闭业务入口同时保留进度。私密日志p5-02-pg-final3.log。
- `npm --prefix auth-console run build` PASS；3项governance-context Node测试PASS。最终命名常量调整仅等价替换，重新构建通过。
- `python3 deploy/governance-p5-smoke.py --playwright-module <现有commerce Playwright>`：独立PG数据库、真实Casdoor Token/邀请成员、真实图投影，11项浏览器检查PASS，run=shell-6b5a536030。记录见[evidence](evidence/p5-02/ui-result.json)。授权写后有意停止投影执行器，202保持待生效；整页刷新也可查看进度。
- 未知结果试验用Playwright转发真实scoped-grant请求、丢弃已提交的响应，再放行浏览器原命令重试；不伪造API响应。验证两次命令/payload完全一致、真实角色引用仅一条Grant。
- 1440角色表单/精确授予/版本详情/清单预览及390工作台截图均实际查看：中文标签、范围、期限、按钮和抽屉内容可读；关闭动画后无中间态截图。操作覆盖必填校验、脏表单关闭确认、Esc、提交、重试、202与刷新。

## 修复与限制

真实浏览器发现UPDATING时AUTHZ_STATE_NOT_READY阻断工作台进度；已按展示契约返回UNAVAILABLE、清空业务入口，结束重验成员身份，业务鉴权仍拒绝。重试测试曾因复用夹具的固定source_id产生真实冲突，改用每次唯一来源并以全新隔离库完成验收，没有放宽约束。

Code Hygiene：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，仅FORMAT_TOOL_NOT_AVAILABLE；沿用周围风格，不新增格式化器。其余提示为隔离测试超时和跨仓历史枚举误匹配，无产品阻塞。完整交互OIDC登录、真实商城与OA审批仍属于后续P5，不冒充本片已完成。令牌、数据库配置、原始失败HTML均留.local，不入Git。

SKILL_HANDOFF: slice=P5-02, status=COMPLETED, gate=PASS_WITH_ASSUMPTIONS, next=P5-03。阶段P5尚未完成。
