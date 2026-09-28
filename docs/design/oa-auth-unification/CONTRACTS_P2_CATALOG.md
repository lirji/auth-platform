# P2-01 应用目录契约（冻结 v1）

本契约细化已批准 P2，复用 P1 身份和治理库，不改变原 DAG。后续角色、授权、投影与 SDK 在各自片冻结。

## 权威与边界

- Application 用稳定 app code，全局唯一，owner 为明确主体 ID；P1 登录身份映射后才能发布该应用。首次登记经受控 CLI，记录操作员和命令；不从 isAdmin 或首次登录推导权威。
- application/environment/tenant 的开通独立持久化；P2-01 不产生业务授权。应用入口只接收登记时的 HTTPS origin（隔离 localhost HTTP 允许），manifest 菜单仅同站绝对路径，不含协议、双斜线、反斜线、query/fragment。
- 清单 v1 字段：schema_version（"1"）、application、manifest_version（正整数）、capabilities[{code,resource_type,risk_level}]、menus[{code,parent,route,any_of}]；未知/重复字段、重复 code、未登记引用、环路拒绝。最多 200 能力、100 菜单、128KiB。
- capability code 必须以 application + '.' 开头，风险为 NORMAL/HIGH；已发布 code 的 resource_type/risk_level 不可改变。删除旧能力本片拒绝（以后走有引用检查的停用生命周期），新增能力不会修改任何角色。
- 版本严格递增；同版本同摘要幂等返回；同版本改体或旧版本返回 VERSION_CONFLICT。摘要基于排序归一化内容，而非 JSON 排版。
- 发布在应用行锁内提交 manifest、当前版本与审计；失败全部回滚。预览无副作用，输出 added/retained 与摘要；有界清单返回当前清单。

## 接口归属

本片首先交付受控 CLI 的 register/preview/publish 路径；P2-03 的已认证管理 API 复用同一应用服务，不重新发明写权威。注册 CLI 不暴露 HTTP，私密配置文件继承 P1 的0600和路径规则。

## 验收

真实 PG验证：跨应用冒领拒绝、当前停用owner拒绝、发布和审计原子、并发同版唯一、同版冲突拒绝、非法菜单/未知字段拒绝；预览不改数据、新增能力仅更新清单。不修改 V1–V5。
