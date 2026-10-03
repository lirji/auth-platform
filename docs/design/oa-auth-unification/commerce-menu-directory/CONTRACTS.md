# 电商菜单显示元数据兼容契约

在原P2清单schema_version=1的menus项兼容新增可选字段label/position。两者同时提供或同时省略；label为1–80 Unicode字符、无首尾空白/控制字符/HTML尖括号，position为0–99整数、同快照不可重复。名称仅供展示，不参与授权。旧code/parent/route/any_of及原能力引用/上限规则不变。

原POST /catalog/preview|publish接收新字段。发布时原application_manifest只存原v1字段，旧SHA算法及历史JSON逐字节不变；新application_manifest_presentation以(application_id,version)固定绑定，存严格有界规范化显示列表及其SHA256，在同一Owner事务内追加。重复当前版本必须两个摘要都匹配，省略/改变已发布名称同样VERSION_CONFLICT；必须以新manifest_version演进。审计失败两个快照及指针全部回滚。

GET /access/published-catalog的menus兼容新增label:string|null与position:number|null；旧目录为null，消费者回退code。全量目录读取仍要求当前应用管理委派；普通用户403。显示快照与身份/委派/原清单/紧急状态来自同一次SQL读取，返回前再读比对，view_hash包含显示快照哈希。GET /catalog/current的Owner当前快照会合并显示元数据，技术清单编辑/导出不丢名称。

回滚旧后端可忽略新增表并继续读取原v1权限清单；旧前端忽略响应新增字段。数据库增量迁移不删旧字段/表，不回退迁移。发布新菜单不扩大委派或创建/更新角色、Grant。
