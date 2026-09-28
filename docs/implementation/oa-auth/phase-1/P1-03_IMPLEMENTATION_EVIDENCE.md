# P1-03 实施证据

范围：本人当前有效成员列表，以及内部服务与最终用户双身份上下文。依赖 P1-02 已完成，精确 CI 36388476420 SUCCESS，3db3f3b 已合并推送 main。使用 Claude backend-implementation、runtime-and-deploy、implementation-validation、project-documentation、update-progress-docs 与 task-git-delivery 技能。

## 实现

- protocol 新增纯 Java DTO；局部 Jackson 输出 snake_case，不改变旧宿主协议。
- admin 的 `/api/governance/v1/**` 使用独立高优先级 SecurityFilterChain，统一验证 Access Token 和显式当前主体绑定，再允许 Controller 访问。线程身份不保存 Token，无 roles/isAdmin 授权，完成后清理，无 Session。
- server 的内部 resolve 先验证服务摘要，再读有界 JSON 和独立用户 Token。服务固定绑定应用、环境、操作、用户发行方；不能通过 body 更改身份。最大 32 个服务配置，不保存明文服务凭据，固定长度摘要比较，无正向认证缓存。
- CurrentContext 单条 Mapper SQL 联合检查精确 issuer/sub、HUMAN 主体、企业和成员的当前状态、有效期；返回同一快照的版本和代际。成功仅表示身份事实，不产生应用准入或业务 ALLOW。
- 两个宿主默认关闭新能力，开启必须配置 0600 私密文件，独立治理 Runtime 只 validate。排除默认 DataSource/Flyway 自动装配以避免接管审计库；V1 迁移未修改。
- 身份枚举新增显式稳定 code，专用 MyBatis TypeHandler 只在治理 Runtime 注册；错误响应使用稳定 value。未知数据库枚举拒绝。
- `deploy/governance-context-smoke.py` 用既有两个真实 JAR、专用治理库、固定隔离 Casdoor，随机创建自有成员，验证后只停止自行启动的进程。私密日志及配置留在忽略目录，未写共享 IdP/业务库。

## 已执行

167 单元测试、14 PostgreSQL IT、5 固定 Casdoor IT、29 真实 HTTP 检查 PASS，无失败或跳过。Hygiene 为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，仅缺仓库 formatter；未配置独立静态分析器。测试明细及源码绑定见 TEST_RESULT 与 evidence-index。

初次新增 PG 测试调用 CAS 方法少传旧状态，编译失败后修正；首次 HTTP 工具读取列表字段 `id`，与协议 `membership_id` 不一致，工具修正后两次真实运行成功。首次 Hygiene 检出探测重试吞异常和硬编码状态，改为保留最后错误类别与类型化状态后通过。保留初次失败日志，不修改协议或降低断言。

## 边界与后续

应用准入/角色/数据范围仍由 P2/P3 建设。共享 Casdoor 升级因 redirect_uri 缺陷继续 HOLD；本片只启用隔离实例。服务凭据需高熵生成、按文件静态治理并通过进程重启轮换；正式部署需 TLS、最小权限运维探针。未承诺生产容量或整条请求延迟，未执行生产部署。没有可见 UI 改动，浏览器视觉检查 N/A。
