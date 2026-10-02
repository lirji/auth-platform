# Commerce 实际 Owner 目录与岗位快照出版

本片是持续完整授权下的独立出版收尾，使用既有 IR 工作树与 test 隔离 PostgreSQL、Auth 21662、Console 21665。不修改其他 Owner 产品，不使用 production，不执行 Grant/Policy 写入，不清空共享数据。候选来源为 COMMERCE_PERMISSION_BINDINGS.json，122 个候选只是待核对集合；不能用 enum、迁移成功或历史 DONE 替代当前 Owner 终验。

`deploy/governance-commerce-publication.py` 读取候选、当前 Owner 菜单映射、终验凭据与显式仓库根。每项能力必须显式归属于凭据的 capability 闭集；每份凭据绑定精确当前 commit、无在途改动的源码 SHA256、真实 PASS 报告 SHA256。任一待证能力输出缺口，manifest 为 null，禁止 HTTP 写入。源码或终验报告变化必须重新准备出版计划。

导航数量从实际 Owner navigation.ts 联集读取；已观测 21 是历史数量，不作为目标常数。父菜单只提供浏览上下文；页面菜单 any_of 由实际 Owner 页面能力构成，不能把 catalog.operate 映射成 product.read。固定 collaboration/products 使用 product.read。每条页面映射绑定真实页面/接线源码，不在 Auth 前端硬编码。manifest 沿用既有 schema_version="1"、最多200能力/100菜单/131072字节，能力字段 code/resource_type/risk_level，菜单字段 code/parent/route/any_of。

34 个角色快照为17个岗位按资源拆分的版本1模板，union99能力；其余23能力不自动加入模板。角色仅通过既有 POST /api/governance/v1/access/roles 创建，校验每个角色能力同资源类型。OWNER_REVIEW_REQUIRED 是后续授予审查要求，不是自动 Grant。旧 createRole、grantScoped 的 mixed/disabled 现有后端语义不因出版工具改变。

执行只允许 http://127.0.0.1:21662、commerce/test UUID 分区、声明的 auth_gov_p1_test_ 独有库。Token 来自0600凭据文件。HTTP允许集仅 published-catalog GET、catalog/publish POST、access/roles POST。先持久化精确命令意图与各角色命令 UUID；断连/坏2xx/校验失败保留原键与输入，恢复不换键。已终验 capability 闭集须先在独立夹具准备 v1 基线，GET 确认所有真实 resource_types.code 的 scope_supported，再发布 v2菜单。发布后检查精确 cap/menu/版本/ceiling/disabled；每次角色写前及末尾重验 view_hash。HTTP PASS 仍需 SQL 与实际 PKCE/UI 验收，不能直接标 DONE。

依赖明确：Auth 共享 c834d389c2f17914e163133d276b420620d43945 → ad092137d90726e27b19084f05be4bd5969c3b02 → 5e7abbf65d3e14f5527b3b9839397cb853091664；它们是其他 Owner 的已验证依赖，本片不冒称独立产品贡献。其余 Journey/CE06 在途能力须等各 Owner 最终提交与真实报告，再绑定并发布。Graph schema、SYSTEM/SourcePolicies 与业务运行成功仍归各 Owner；本片不能以目录注册替代其授权生效/业务验收。
