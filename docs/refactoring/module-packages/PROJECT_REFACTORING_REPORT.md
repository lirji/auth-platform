# Auth 模块包结构、格式与代码规范重构

用户已明确同时覆盖 Auth 与 WMS。WMS 在独立任务树完成 R00–R11，R12 正在核对完整 CI；本报告负责 Auth，不能以 WMS 完成代替两个项目全部完成。采用同一组 Claude project-refactoring、backend-implementation 与开发规范，连续实施。

## 基线与保护

起点 `main b644dcd01a68c6fa1cb5ab5ce28b57ba531ccb27`，原目录工作树干净。当前任务分支 `refactor/module-packages-code-quality`，不新建工作树。340 个 Java 源/测试、39 份 SQL、图模式、已发布协议/SDK、挂载门户配置及全部跟踪文件的摘要保存于忽略目录 `.local/refactoring-module-packages-auth/`。

本次基线 Maven reactor test 实际运行 331 项，失败、错误、跳过均为 0，基线与测试保护 Gate PASS。原精确提交的 Auth/Portal CI 已成功；新增源码的真实 PostgreSQL/图/投影/身份等验收仍需重新执行，不能继承旧结论。

保留现有私密配置、预算、备份、旧工作树及 WMS 原目录的 55 个用户文件和未提交 Driver。只改当前任务源与验证入口，不重部署、不操作共享数据、不自动续发 Token 或 Grant。门户 `public/config/catalog.json` 是现有运行挂载，按字节保护，格式器只处理人工源文件。

## 问题与目标

| 证据 | 问题 | 实施方向 |
| --- | --- | --- |
| governance 共 128 类，application 59 类、persistence 31 类、domain 19 类 | 目录、成员、授权、请求审批、投影、角色迁移和门户责任混在少数大包 | 按实际业务能力细分；保留包内协作簇；SQL 权威及事务不迁移 |
| admin 共 53 类，根包 13 类、governance 25 类 | HTTP、过滤器、组合根、CLI、审计、身份同步混合 | 管理能力再按入口、应用协作、集成、配置与持久化组织 |
| server 共 19 类，governance 11 类 | 多类判权/导航/范围/执行与对应配置混合 | 明确适配边界和组合根，根应用入口保持 |
| SDK 9 类、protocol 32 类有外部消费者 | 无条件移动公开类会破坏 WMS 及其他宿主 | 公开门面全名与契约保留，细分实际内部实现，不机械添加透传包装 |
| core 仅 2 类 | 小型紧密协作模块不需要空层 | 保持凝聚，审查依赖、错误、资源和注释 |
| auth-console 89 源文件，project-portal 20 源文件 | 管理页面较多；两个前端没有固定格式检查 | 按真实功能/共享/壳层审查组织；保留路由、API、现有 UI 和真实配置 |

包与责任拆分服务于可维护性，不重新决定服务边界，不增加中间件或新的配置/错误体系。数量、权限撤销、成员代际、图 marker、租约/READY、严格范围、固定机器主体、错误码和容量/时间预算都按当前契约保持。

## 实施切片

| ID | 范围 | 状态 |
| --- | --- | --- |
| A00 | 源码/运行/契约基线、范围、测试保护及可恢复计划 | DONE |
| A01 | 固定 Java/XML/两前端格式入口与 CI，保护生成副本和迁移 | TODO |
| A02 | governance 按业务能力细分模型、应用、持久化、适配与配置；保留私有协作 | TODO |
| A03 | admin 管理、身份同步、审计、治理 HTTP/CLI/配置职责 | TODO |
| A04 | server 判权/导航/范围/执行适配与配置；core 规则/资源审查 | TODO |
| A05 | SDK 内部 HTTP/校验/组合职责和公开门面、protocol 契约审查 | TODO |
| A06 | auth-console 与 portal 功能、共享和壳层结构审查与有界调整 | TODO |
| A07 | 全模块中文原因注释、类型/状态、配置、错误/日志、SQL/事务与有证据优化 | TODO |
| A08 | 必要真实 PostgreSQL/图/身份/投影/legacy/Boot4/门户 CI、兼容/卫生终审与正常 main 交付 | TODO |

每批先给精确类/消费者映射与影响，再编译、相关测试、资源/namespace、差异检查。生产逻辑优化先补特征/回归测试；失败不推进下一批。分批正常提交，完整验收后按持续授权正常合入推 main；不强推、不混入其他工作、不执行本次未授权的部署。

## 验收和兼容

- HTTP/JSON、公开 protocol/SDK 门面、SQL 迁移与图模式、权限/时刻/TTL/预算：UNCHANGED。
- 必要内部 Java/前端文件路径：CHANGED，须同步全部已知消费者、Mapper、自动配置、CLI、测试/profile、部署工具中的源码引用和当前文档。
- 若发现实际缺陷，先复现，再在报告明确 CHANGED 的触发条件与结果；不能把业务行为变化冒充纯格式或拆包。
- 格式、静态边界、全单测、真实集成/兼容 profile、两个前端检查、技能卫生 Gate 和精确提交 CI 都需要实际证据。未执行或不适用逐项说明，不伪造 PASS。

## 当前进度与恢复

A00 基线和测试保护 PASS，尚未修改产品源码。用户已确认两个项目，无需再次确认范围；先完成 WMS R12 再连续执行 Auth A01–A08。根 `CODEX_PROGRESS.md` 是忽略的本地恢复记录；基线、原始失败及后续每批结果保存于 `.local/refactoring-module-packages-auth/`。

当前整体目标 IN_PROGRESS，不能报告两个项目已全部完成。
