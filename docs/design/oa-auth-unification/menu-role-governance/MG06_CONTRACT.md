# MG06 来源与发布漂移核对契约

状态：阶段A已批准规则的具体化，可实施。只读核对，不发布目录／改授权／自动回退。

`POST /api/governance/v1/catalog/drift` 输入MG04严格Candidate，由当前 HUMAN Owner读取本应用。旧清单可无source；未知字段、客户端runtime证明／URL／主体一律拒绝。返回 application、observed_at、expected_version、content_hash／presentation_hash、declared_source、published（当前固定MG04 Release或null）、state、diff（完整两版CatalogPreview或null）、deployment、runtime_state。

状态优先顺序：未发布NOT_PUBLISHED；版本或权限摘要不同SOURCE_MISMATCH；展示摘要不同DISPLAY_MISMATCH；任一来源未知UNKNOWN；双方固定commit／artifact_hash不同SOURCE_MISMATCH；两摘要／版本／来源均相同MATCHED_DECLARATION。保留当前实际发布及完整差异，低版本候选也可核对，不为查询提高版本或篡改旧摘要。读取后重新检查当前Owner和版本，竞争变化409要求重查，不把异常当零漂移。

核对响应中的published展示摘要从固定历史实际计算，旧无显示sidecar也能比较空展示的真实摘要；MG04历史原来源／记录保持原样，不回填数据库元数据。

受信部署声明只来自受控0600服务配置快照，HTTP调用方不能提交：`catalog.deployment.count`最多100；`catalog.deployment.N.application-id / manifest-version / content-hash / presentation-hash / commit / artifact-hash / declared-at / evidence-ref`。应用唯一、正版本、稳定commit和双摘要、明确ISO时点、非空200字符证据引用，缺项／重复／无效启动失败。deployment返回 `{state,declaration}`；未登记为UNKNOWN/null，已登记与当前发布比较同样状态。声明存在仍不能证明正在运行。

`runtime_state`初版始终UNKNOWN：当前项目尚无受信运行制品核验入口。返回服务端注册部署声明可以定位部署声明与发布目录差异；手填、Owner输入和配置标签不会升级为实测。未来经独立受信制品核验扩展该入口之前，不声称运行一致。此限制在页面及工具准确呈现，MG11／MG19运行收口另外核验真实制品，不用该状态假装部署验收。

CatalogEditor内独立按需只读核对已输入清单／导出信封；来源、当前发布人／时点／版本、核验时点、部署声明／证据／时点、具体差异分组展示。编辑／应用切换取消旧请求和清除旧结果。403／409／503／缺接口与UNKNOWN分开，不留旧成功报告或回退旧发布。

维护CLI `check-source`使用原受控配置和当前Owner，读取Candidate；人工工具只读取本地固定导出文件、严格拆出false自动标记、核对配置中目标应用，使用指定当前JAR调用本只读路径，输出私密JSON报告。它不是机器发布凭据。无定时器、任意URL拉取或结果持久化；报告observed_at就是本次核验时点，保存文件可追溯，页面不编造后台“最后一次”记录。

验收：漏发布、旧版本、内容改变、仅展示改变、来源未知／不同、来源一致、只有部署声明无运行实测、错误目标、当前Owner拒绝与依赖故障；读取前后角色／Grant／目录不变，三宽度实际组件操作及截图。
