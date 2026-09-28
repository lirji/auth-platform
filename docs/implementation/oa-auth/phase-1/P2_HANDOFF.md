# P1 → P2 交接（不执行）

用户要求完成 P1 后暂停；当前只交接已有契约、依赖和风险。DAG 原63个稳定ID保留，P2—P7 execution_authorized_this_task=false。

## 可复用基础

- auth-platform-governance：Principal/LoginIdentity/Tenant/Membership、当前状态与代际、旧来源绑定、精确身份、邀请和生命周期审计。
- 管理入口读取本人有效成员；内部 context/resolve 同时验证固定服务身份和用户 Access Token。application/environment 由服务配置决定；请求体不能指定主体或提升应用范围。
- OA 是员工/组织事实唯一来源。源 V8 与目标 V5 分别由所属服务写；受控 register/pull/status CLI、不可变事件、检查点、冲突隔离及确认恢复已验证。
- AccessContext 包含 principal/membership/generation/tenant/app/environment/caller/actor_type 及版本。当前有效成员只证明身份上下文，不能据此默认允许业务能力。
- 版本和源码证据见 P1-07_TEST_RESULT、P1-04_TEST_RESULT；现有 auth SDK/工作区接口保留。

## 恢复后的首个任务

取得后续阶段授权后，从 P2-01 的应用/能力/菜单清单解析与预览继续，不重做 P1。使用既有 IMPLEMENTATION_SLICES 与 EXECUTION_DAG，不另起项目。
P2-02/03 继续角色不可变版本、AccessGrant 和受保护管理 API；P2-04 做单执行者投影；P2-05a 先验证 Boot4 消费当前 SDK 的真实兼容，再接首个内部只读入口。

P2 必须保持：没有应用准入即拒绝；成员代际/当前停用始终有效；相同角色名跨应用不串权；精确授权来源与时间窗；图成功且水位持久化之后才 ACTIVE；故障不自动 ALLOW；旧 SDK 行为回归。
组事实只用于目录投影，组驱动业务授权等待 P3 栅栏。不能把 P1 的成员关系直接投成全局管理员。commerce 保留 Actor、memberId、幂等回执与业务库所有权。

## 需保留的风险与环境

- Casdoor 完整升级仍 HOLD（redirect_uri）；不替换共享8000。当前验证固定隔离18090，私密配置仅 .local，不能提交或发给浏览器/SDK。
- Q-EXT 仅阻塞后续真实外部业务试点；不编造供应商资源。正式审计保留期限/容量目标/生产发布需其所属阶段明确。
- auth 与 OA 专用数据库及夹具保留供恢复。再次运行真实 OA/IdP 专属测试须创建新夹具目录，不能清库或改写历史 employee.user_id 来复用 run-01。
- OA 原用户进度文件和 tmp 保留，不能并入本任务提交。auth 仅原主目录工作树；.local 下 P0 基线是其他仓库只读历史，不是重复 auth 项目。

## 回退边界

关闭新治理入口或停止导入均保留生命周期与审计事实，不回滚已执行迁移、不复活停用成员。保持源事务捕获可供恢复；冲突需查保留事件和固定映射，不能清空检查点伪装恢复。
生产目录接管、共享认证升级、正式切流和部署均未执行，Git 推送不代表部署授权。
