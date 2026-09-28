# P1-01 实现交接

Claude backend-implementation；slice=P1-01；needs=P1-00 DONE；gate=PASS，进入独立验证。

Allowed Change Set：新增 governance 库/main/test/迁移、根 Maven、隔离测试库工具与既有 CI；必要文档同步。旧 protocol/core/sdk/server/admin 产品源码未改，OA/commerce 未改；没有正式导入、图写、生产部署或新服务。

用例：受控 EMPLOYEE 初始化→显式 issuer/sub 与旧键绑定→当前有效成员读取。幂等占位/读锁/摘要核验、业务记录、审计、结果完成同事务；不根据邮箱合并，不恢复停用。SQL 全部在 Mapper XML；模型无持久化依赖；状态底层 CAS 承担数据库并发保护，受保护生命周期命令归 P1-06。

已触发规范：数据库约束/迁移/池、审计、配置、租户/身份、增长记录保留。未触发缓存/MQ/图投影/调度器。审计和命令不自动清理，正式保留期/容量为上线前输入。

窄验证：126 单测（124 原有+2 新增）、11 真实 PG 集成、独立 CLI 初始化/重放/读取、测试库重复执行 PASS。首次编译发现 mybatis-spring 需要 spring-context，补充同 Boot BOM 依赖后最终 verify PASS；未删除失败日志。

代码卫生：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，仅缺仓库原本未配置的 formatter；未新增格式化器。事务及通用 catch 已人工核对：无远程调用或图写占用事务，异常回滚/资源关闭/秘密不回显；列表/连接池/SQL等待有界。

下游：P1-02 实测安装 Casdoor Access Token 用途/issuer/audience/签名/期限；P1-03双身份上下文。当前 CLI 不是在线登录或业务权限已接入的证明。
