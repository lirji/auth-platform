# MG04／MG05 发布历史与固定预览契约

状态：依已批准阶段A规则细化；MG04可实施，MG05按依赖继续。真实HUMAN Owner仅发布本应用，生产部署不在本次授权中。

## MG04 来源与历史

候选Candidate独立于旧Manifest：`{manifest,source?,reason?,decision?}`。source为 `{commit,artifact_hash}`，commit必须40／64位小写十六进制，artifact_hash为64位SHA256；二者同时提供，未知时source=null，不把Owner手填声明当运行证明。decision为KEEP_CURRENT_GRANTS／SEPARATE_AUTHORIZATION_REVIEW，reason可选非空最多500字符。所有输入严格拒绝未知／重复键、转换、枚举序号、超限；信封141312字节，紧凑清单仍128KiB。

MG04新增来源发布Java／受控CLI入口 `publish-source`，输入Candidate并使用配置中的原command。旧HTTP／CLI Manifest入口仍可用，新发布自动写入UNKNOWN来源记录；已有旧版本不回填来源或新命令。没有临时绕过门禁的HTTP写入口。MG05在同一用例上执行目标策略与预览门禁。

新增不可变catalog_release，(application,version)唯一且引用旧不可变快照，(application,command)唯一。原snapshot／presentation／pointer／catalog_audit和release记录在同一事务；记录固定双摘要、真实发布主体、原始command及命令体摘要、来源／原因／处理决定、基础版本及双摘要、PG发布时间、原始Preview回执。新增表／每列中文注释，旧v1 JSON／摘要／已执行迁移不修改。

当前Owner仍须认证后才能读取成功回执。相同应用命令＋同归一化Candidate返回原回执（之后已有更高版本也不回退指针），不同体COMMAND_CONFLICT；来源变化也视为不同体。来源入口同版新命令返回VERSION_CONFLICT，不能给已存在发布追加伪来源。旧原Manifest同版不同键保持原返回／无重复审计行为，但不会补造该键的发布来源；同版旧历史亦不补造。

`GET /api/governance/v1/catalog/releases?application_id&before_version?` Owner限定、本应用固定版本降序，101取100；响应 `{items,next_before_version}`。旧快照无release记录仍列出：来源／原因／决定／命令／原预览为null。列表包含application/version/content_hash/presentation_hash/published_by/published_at/source/reason/decision/command_id/base_version/base_content_hash/base_presentation_hash/preview。

`GET /catalog/releases/{version}?application_id` 返回 `{release,manifest}`；固定版本详情重新校验旧JSON和展示双摘要。未知应用Owner返回403；已授权不存在版本404由受控响应处理，不泄漏其他应用。读历史不读取人员信息，Owner资格不能替代MG03。

## MG05 固定预览与启用

新 `POST /catalog/release-preview` 输入Candidate和可选 `impact:{tenant_id,application_id,environment,basis_hash}`。响应 `{preview_id,expires_at,base_version,base_content_hash,base_presentation_hash,candidate,preview,impact}`；预览有效期10分钟，服务端PG持久化固定候选／来源和依据，ID不是权限凭据。旧结构预览仍只读且无票据。

新 `POST /catalog/release-publish` 输入 `{command_id,preview_id}`，从服务端固定票据取内容，不接受临时换体。返回MG04原回执。来源／内容／基础双摘要／当前Owner、有效期重新验证，冲突409；带影响确认则重新校验当前管理和诊断权并重新计算COMPLETE basis（不在REPEATABLE_READ旧快照自证）。报告只是固定分区，不能声称其他企业无影响。

route／any_of变化及新入口复用已发布能力要求非空业务reason和decision；展示调整不强制决定，不新增Grant。提交后未知结果用原command＋preview重试；已成功的同体重试在新当前版本存在或票据过期后仍读取原回执，不重新执行发布。当前Owner失权仍拒绝，不能仅凭命令ID读取。

应用策略缺省LEGACY；当前Owner受控启用GUARDED前需明确已退出旧写节点并完成调用方升级，记录原因／幂等命令／版本审计。所有写入口（旧HTTP、原CLI、source CLI和未来机器）在同一事务的应用锁下检查。GUARDED旧路径返回VERSION_CONFLICT，不可在UI藏按钮后允许旧API绕过；已启用不提供自动解除开关。生产启用需明确目标授权，本轮只隔离夹具。

## 验证与恢复

真实PG覆盖原回执重试／改体／后续版本、来源可选／旧历史、两Owner并发、审计失败完全回滚／历史不可更新；MG05补预览过期、基础双摘要变化、当前身份撤销、影响依据变化、旧HTTP／CLI拒绝与网络未知结果。历史UI当前截图和实际翻页／详情在1440/390/320检查，失败不可显示为空历史。

新增表保留，程序回退不删除历史。启用GUARDED后旧程序不能作为写节点重新上线；代码回退、目录更高版本修正与业务授权补偿分开。没有生产发布、原Commerce v2或Grant变更。
