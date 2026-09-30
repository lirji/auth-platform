# 商城真实迁移审核准备

状态：REVIEW_REQUIRED，不允许自动导入/切换。OA为员工权威；首批沿用已选commerce_local租户。资料仅包含白名单来源元数据，不导出Bearer/hash/姓名等认证机密。

新增`deploy/governance-p6-migration.py prepare-review`：输入0600只读快照及SHA256、所选tenant、带时区evaluation-at；可再传previous/previous-sha256比较同库同容器快照。输出0600且不可覆盖，返回2表示人工审核待决；非法输入/IO返回1。输出包含旧actor及凭据到期状态、真实门店/商家引用、源Grant版本、映射待填写项、有限有效期决定和增量差异。OA principal/member/generation/evidence始终为空，绝不同名猜测。输出不是导入格式；现有dry-run仍要求明确签字映射。

差异覆盖Grant、门店、商家、凭据集合。记录消失标MISSING_REQUIRES_TOMBSTONE_REVIEW，完整删除所有Grant仍报告差异；不能把无行当成成功或自动Grant撤销。旧版本回退/同版本改体标VERSION_CONFLICT。快照没有冻结源写入，因此只能证明这两个观测点，不证明连续CDC水位或生产追平。

本地执行证据（不提交私密文件）：

- `.local/governance/commerce-readiness/current-source.json`，SHA256 `397f4f34c9a14a06b8432bb9d783e5dfad19c844c52e4fb6146fe449eea0f773`；数据库内单个repeatable-read只读事务。
- 对照P6_SELECTED_UNIT.source_snapshot。所选租户1 actor、1 grant；四类元数据差异0；OPERATOR凭据已到期。
- `current-review.json`：真实OA映射、目标分区、岗位和Owner签字待填写，ready_for_import=false。
- `unreviewed-local-mapping.json`只使用既有**本地隔离**目标分区、空actors/permissions；对应dry-run为1 QUARANTINED_MAPPING、0 IMPORT_CANDIDATE，原到期约束仍在deny_constraints；未对真实人员创建中央身份或续期。
- 22项Python行为测试通过，包括跨租户同名资源不join、过期身份不猜测、删除全部授权、同版本修改、撤权及倒序快照拒绝；原有私有文件/大小/摘要/映射与影子差异测试继续通过。

后续审核必须填：精确OA来源subject与证据、principal/membership/current generation、中央tenant/application/environment、固定角色/能力范围、旧永久授权有限期限、真实资源Owner及审核人。动态MERCHANT后代语义继续隔离，不能拍平门店静态集合。源删除须有可追溯墓碑/序列和最终冻结对账。

扩展模块仍需分别盘点它们的旧角色/业务归属及任务快照；本CATALOG工具不把旧ADMIN自动变成其他模块能力，也不声称已覆盖全商城数据迁移。
