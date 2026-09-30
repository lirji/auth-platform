# CE04会员扩展验证记录

## CE04-P0基础执行协议本地DONE

auth基线3a8f7e9，任务分支feat/commerce-member-execution；消费CONTRACTS_COMMERCE_MEMBER.md。仅四基础能力member.read/create/profile.update/status.update→commerce_member，60秒HUMAN执行引用、完整TENANT_ALL scope；已有对象读取/修改要求匹配类型和规范Owner事实，创建不能伪造已有对象事实。execution-check现在与issue/scope一样要求显式Owner登记。旧CATALOG/库存/目录协议保持，新API/真实Owner配置/生产清单均未新增。

验证：251单元测试PASS（member-core-unit.log）；专用PG7cdc4197661c+现有隔离SpiceDB执行ExecutionAuthorizationIT共4项PASS（member-core-integration.log），其中新项遍历四能力的范围/类型/租户/代际/超期/伪门店事实/未知能力、撤销与重授不能复活，旧三项全部回归。该验证使用协议事实，不是实际商城会员Owner验收，后者留P1。专用PG已由finally停止，数据保留。

SDK install、Boot4兼容和全模块package PASS，member-core-sdk-install.log/member-core-boot4.log/member-core-package.log。SDK公开方法和JSON不变；新增协议常量不会要求旧SDK重编译。hygiene无阻断，保留未配置Java formatter/静态分析限制。git diff --check PASS。

本片没有商城数据迁移、员工授权或浏览器改动；P1业务Owner/V52与P2会员页面未实施，后续成长/周期/积分等尚待细化。Git/CI交付另记，不把P0完成算成CE04完成。

## CE04-P0交付与CE04-P1本地DONE

P0 auth dd07223161df6e3dcb6e55756aac7f91369f416d已正常合并推送main，CI36668768692 SUCCESS；该CI包含D2基线。D2 auth36668435670被后续推送取消（不是通过），commerce5587d53 CI36668436980 SUCCESS。

P1消费正式会员契约：MEMBER_PROFILE独立族、四精确能力、真实会员Owner事实和版本，先集合资格再对象判权，路由/会员锁在旧回执读取前，稳定主体代际进入幂等摘要；创建、资料/状态变更和身份审计共原事务。V52只扩展允许族/审计类型，已成功执行不得改写；客户本人和内部交易接口未改。SDK来源固定已推送dd07223并通过原安装脚本验证。

商城完整mvn -B -Pwith-ui verify：406项401PASS/5既有可选skip，member-verify-fixed.log；新CentralMemberMySqlTest4项使用真实MySQL/HTTP、中央SDK协议桩，覆盖全租户/其他租户、旧ADMIN/聚合拒绝、客户本人读取、创建无读、独立资料/状态、注销终态、代际幂等冲突、Owner版本竞争、撤权重试、故障503/STOPPED。Outbox真实事务路径注入失败后，会员/命令/身份审计三表均无记录。中央行为另由真实跨进程演练证明。

真实PG+IdP+中央服务+商城演练rehearsal-451c622849b4共114项PASS（无浏览器），member-owner-rehearsal-fixed.log与result.json保留。四权限有限授权和TENANT_ALL、创建无读、资料无状态权限、真实会员历史/外租户拒绝、三次业务效果与三条审计各一次、注销不能复活、撤权后同键回执拒绝且读仍有效、依赖停机503通过；库存/目录/CATALOG后台恢复回归通过。原商城8602未切换。自有PG/IdP/JVM由finally停止，数据/网络保留；专用MySQL43308继续供后续片。

失败历史保留：member-compile.log错误枚举名已改既有INVALID_INPUT；member-verify.log首次测试故障注入触发MANDATORY代理（改AopTestUtils取得spy设置）以及旧库存测试对现已接入会员入口仍期望401（已认证无权应403）。第一次演练c1091cdbe5fa因Docker默认地址池耗尽而停止，未删除历史资源；工具增加显式RFC1918 /24参数，核对既有Docker子网后本次使用10.254.81.0/24。公共/IPv6/过宽/非规范子网单测拒绝，Docker负责重叠检查。36项Python测试、223真实入口/122能力/34角色离线契约核对、两仓hygiene及diff检查通过；hygiene保留无Java formatter/未配置静态分析限制，不冒充自动事务审查。

本片没有页面验收、生产授权或真实OA导入。下一P2页面及成长/标签/行为/周期/积分、CE05—08未完成。回退必须使用认识MEMBER_PROFILE族的版本和STOPPED路由，旧二进制不具备该隔离能力；5秒仅准入期限，不宣称跨库即时撤权。Git/CI结果随后记录。

## CE04-P1 Git交付

auth2cb8c11ab8f773edab94327bc79ac5996990799c与commerce1bc81f276520aa1c54b78c337c99ce413299561b已按任务分支普通合并推送main。CI36669783915、36669785165均SUCCESS。期间auth其他任务782ae3d仅修改gitignore且已在origin/main，保留该提交及其gitignore-hygiene工作树，未清理或夹带其未提交内容。

## CE04-P2验证中

会员固定SSO页、列表/历史Drawer、三个独立写工作区及精确提示API已实现。407项Java402PASS/5skip，member-ui-verify.log；5项CentralMemberMySqlTest含三提示的独立/旧ADMIN/401/范围/epoch/503验证；静态壳GET与非GET门禁回归。最终登录说明文字后重跑frontend build/package，member-ui-build-final.log/member-ui-package.log；Prettier/hygiene无阻断，227入口契约/36项Python PASS。无schema/依赖新增。

第一轮真实浏览器rehearsal-e56215a12fb5到108项PASS，包含会员SSO、create-only、真实响应丢失同键恢复、409保留输入后修改、真实历史Drawer和窄屏；状态Select的role=option定位超时，截图显示菜单正常，已修测试为实际可见Ant选项，业务断言/产品代码未改。失败截图及日志保留。第二轮--identity-subnet 10.254.83.0/24正在完整复跑，未据首轮部分通过标DONE。


### P2最终本地DONE

最终rehearsal-aa0b96471601共137项PASS，会员6阶段共12条真实浏览器细分检查；目录/库存/CATALOG共享壳回归全部通过。会员create-only真实PKCE、输入校验、真实提交后丢失响应、原输入锁定/相同键重试、退出取消/切Tab保留；profile-only无read可改、409保留输入再纠正；真实历史包含前后值/原因/操作人/版本，Esc关闭回原列表；独立状态权限注销后恢复409；profile撤权不影响read/status；外租户、logout/401移除内容、真实中央停机503通过。SQL确认UI创建/改名/注销三次效果恰三条身份审计。脚本未伪造成功响应或业务数据。

已实际查看当前同版第一轮create-form/unknown/list/history/390-list/390-history以及最终status-form/390-status/conflict/outage/create-form/390-history/list截图；表单宽度/按钮/中文提示清楚，Drawer桌面保留上下文、390宽表在容器内滚动，状态/权限错误不保留可执行旧入口。只是自审，未声称用户视觉批准。失败run e56215a12fb5和成功run均保留；前端/业务代码未因定位修正改变，验收后仅CentralStoreErrors缩进对齐，无语义变化。

最终Java407项402PASS/5skip、前端build/Prettier/package、36项Python、227入口/122能力/34角色契约及两仓hygiene无阻断。hygiene保留无Java formatter与未配置静态分析限制。自有PG/IdP/JVM/Vite均由finally停止，独立子网82/83与数据保留；不清理其他任务资源。P2本地DONE，Git交付中；下一CE04-G成长模块，其他会员和CE05—08仍未完成。

## CE04-G0成长执行协议本地DONE

消费CONTRACTS_COMMERCE_GROWTH.md，仅增加growth.policy.read/publish（commerce_member_policy）和growth.read/adjust/recalculate（commerce_member）五个精确组合，HUMAN最长60秒、完整TENANT_ALL。已有会员实例须真实Owner事实；政策集合/追加发布只允许scope，resource-check拒绝虚构已有对象。政策常量替换既有类型字符串，不增加ScopeResourceBindings解释范围或SDK公开API。实际商城成长Owner/API/V53/页面未实施，不将协议通过当成业务接管。

验证：growth-core-unit.log全仓251既有单测PASS，新增政策Owner单测后growth-owner-unit.log四项Controller测试PASS（原3+新增1）；真实自有PG f7cc1b4cd6cd +隔离SpiceDB的ExecutionAuthorizationIT共5方法PASS，新增5能力矩阵覆盖两类型、禁止伪门店范围、错误类型/事实、未知能力、120秒拒绝、成员代际/分区、撤权/重授不能复活及到期；旧CATALOG/库存/目录/会员基础回归通过。growth-core-integration.log及-result.json保留，自有PG已finally停止。

SDK install、Boot4兼容、全模块package PASS，growth-core-sdk.log/growth-core-boot4.log/growth-core-package.log；hygiene无阻断，仍限制无Java formatter/未配置静态分析。git diff --check通过。没有生产Owner配置、能力清单或Grant发布；下一G1真实成长Owner与V53。

## CE04-G0交付 / G1验证中

G0 auth51f637c7fc9dbff730da369adbd37c73b8a4db0e已推送，CI36670815592 SUCCESS（包含P2基线，原P2 auth36670574749被后续推送取消）；P2 commerce36670578849 SUCCESS。

G1成长5能力/独立MEMBER_GROWTH路由、政策真实版本审计、会员Owner/版本锁及身份幂等已实现；V53已在专用测试MySQL及真实演练成功应用，不改历史。SDK来源固定51f637c并原安装脚本验证，compile/package PASS。新增CentralGrowthMySqlTest4项在修正后PASS：政策发布无读、真实会员调整/重算/只落一次、客户本人语义、代际摘要、Owner并发、Outbox失败回滚、撤权/STOPPED/503。

真实联调rehearsal-fb6d4a99a58b共151项PASS，growth-owner-rehearsal.log；--growth隐含member/directory/inventory，显式政策Owner和5有限角色，policy publish-only、策略独立读、adjust无read/无recalculate、真实钱包/账本、外租户/缺失目标、重算独立、旧回执撤权拒绝、真实中央停止503、实际policy与member三条身份审计/业务效果一次以及旧各域回归通过。无浏览器验收，G2待实施。自有进程/PG/IdP由finally停止，子网10.254.84.0/24和数据保留。

失败历史：growth-verify.log新测试2项在authenticate处抛SDK拒绝，却断言业务DomainException；测试改为先取得Actor再撤权，并保留HTTP403，未改产品代码以迁就测试。growth-verify-fixed.log的成长4项通过，但既有CouponDeliveryTest首轮处理16而固定断言20；源码DELIVERY策略为20项/500ms上限（非固定20），并行演练下预算耗尽符合该有界行为。未修改发券实现或放宽断言；演练停止后growth-verify-serial.log串行完整复验中，取得最终PASS前G1不标DONE。该时序敏感测试限制即使后续串行通过仍保留。

### G1最终本地DONE

串行完整growth-verify-serial.log BUILD SUCCESS：411项406PASS/5既有skip，新增成长4项及既有发券7项全部通过。此前500ms发券预算的时序敏感断言限制仍保留，未修改该实现/测试。真实隔离151PASS、36项Python/227入口契约、SDK固定来源/compile/package及两仓hygiene无阻断；验证后没有产品代码变化。hygiene没有Java formatter/静态分析的限制不变。G1本地DONE，下一G2真实页面/浏览器，未把成长后端当全CE04或全部迁移完成。

G1已交付commerce5d78721；auth83e38b2经合并已有main成为e23cb52并正常推送。main中其他任务8b0af28/ffb82a5的Docker治理控制台和连接池限制原样保留，合并无冲突，合并后36项工具/227入口再次通过；未部署这些变更。CI待查。G2按既定技术细化开始。

## G2验证中与独立轮转修复

G1两仓CI36671974503/36671965481 SUCCESS。G2独立SSO页面/三提示/五权限工作区、真实钱包/账本/政策与原意图重试已实现；实际后端成长5项、静态壳/非GET门禁通过，36项Python、231入口/122能力/34角色、build/Prettier/hygiene无阻断，hygiene工具限制保持。完整growth-ui-verify-final.log 412项407PASS/5skip，但前一growth-ui-verify.log触发原OrderExpiryLaneTest公平性失败，未忽略失败或改断言。

独立可控时钟复现：领取查询耗尽预算后零尝试仍推进游标，小租户被跳过、热租户先获第二个quantum。已用独立fix/tenant-budget-cursor提交commerce45b74ee（只含轮转修复、确定性测试、说明文档）普通合并推送，G2分支依赖此修复。修复前探针FAIL、后PASS；修复版完整growth-ui-budget-fixed-verify.log 413项408PASS/5skip，其中TenantRotationTest10项/OrderExpiryLaneTest3项通过，原发券7项也通过。旧失败历史保留，不将偶发重跑通过当成问题消失。CI36672968400待查，独立修复说明在commerce docs/TENANT_ROTATION_BUDGET_FIX.md。

真实--growth --browser演练rehearsal-324fea9731d5正在10.254.85.0/24运行，私密证据growth-ui-rehearsal.log；浏览器/当前截图未验收前G2不标DONE。其他会员与CE05—08、原生产HOLD仍未完成。

### G2最终本地DONE

rehearsal-324fea9731d5最终183PASS；成长6阶段11条浏览器细分检查，基础会员12、目录11、库存9、CATALOG9全部回归。真实PKCE、政策publish-only、两位小数/首档与门槛递增/动态等级增删、人工调整无read、0值校验/真实409保留输入后纠正、真实提交丢失响应锁定原输入/原键重试、切Tab与取消退出保留意图、独立政策读和钱包/账本、显式重算、单独撤权/外租户/实际401/实际中央停止503通过。分页首批真实2条政策/1条账本和末页按钮禁用已验，未额外伪造大列表。SQL/API确认UI成长250/账户版本2/仅1条账本，与API路径共6条身份审计，政策审计各绑定真实版本1、2，未知重试无重复效果。

已实际查看当前版policy-form、390-policy-form、policies、wallet、390-wallet、unknown、conflict、recalculate-form、outage截图：桌面布局/层级与现有员工页一致；窄屏表单和钱包保持在视口内，账本在表格内部横向滚动；提示、禁用输入和重试按钮清楚，非用户视觉批准。自有PG/IdP/JVM/Vite由finally停止，独立子网85/数据/私密截图保留，原8602未切换。浏览器后产品代码无变化。

最终413项408PASS/5既有skip（含独立轮转修复）、36项Python、231入口/122能力/34岗位快照、前端build/Prettier、两仓hygiene/diff无阻断，未配置Java formatter/静态分析限制保持。G2本地DONE，Git交付进行中；标签T草案仅在私有目录，尚未实施，下一按既定顺序继续T0/T1/T2。CE04其余能力和CE05—08不能算完成。

G2已普通合并推送auth8351043/commerce646ebd5，CI36673405496/36673407016待查。独立轮转修复CI36672968400被G2推送取消，非失败亦非通过；本地完整回归通过，后续G2 CI含该修复基线。T正式契约已写CONTRACTS_COMMERCE_TAG.md，T0实施中。

## CE04-T0本地DONE

auth有限member_tag.read/define/assign三能力绑定commerce_member，最长60秒HUMAN且完整TENANT_ALL；字典define与member.create同为集合许可，拒绝伪会员execution-check，read/assign允许正确会员事实。没有修改Owner类型/SDK方法/JSON或增加新授权资源类型；未知能力、伪门店/部门、错误租户/类型保持失败关闭。商城真实标签Owner/业务路由/V54/页面留T1/T2。

tag-core-unit.log 252单元PASS；自有PG f5d5df7c48f4加现有隔离SpiceDB执行ExecutionAuthorizationIT6方法PASS（tag-core-integration.log及-result.json）。新增标签矩阵对三能力的scope/资源事实、类型/范围/120秒拒绝、代际/分区、撤权/重授不能复活和到期验证，原CATALOG/库存/目录/基础会员/成长组合回归；合成协议事实不是实际商城Owner验收，自有PG已finally停止并保留数据。tag-core-sdk/boot4/package三日志PASS；hygiene无阻断，缺Java formatter/静态分析限制仍保留。验证后无产品代码变动，下一T1。

## CE04-T1验证中

T0 auth6bfaae7已推送，CI36673611219待查；G2 auth36673405496被后续推送取消，commerce36673407016 SUCCESS含轮转修复。T1 MEMBER_TAG独立族、三精确能力、真实会员Owner/版本锁、稳定身份幂等和同事务审计已实现。标签define的授权类型仍commerce_member，实际审计类型固定commerce_member_tag/真实tagId；assign仍实际memberId，不接受请求指定审计类型。V54已在专用MySQL成功应用不可改历史，SDK固定6bfaae7并原安装脚本验证。

完整tag-verify.log：417项412PASS/5既有skip，编译时包含新增标签4项。随后补入64活跃上限/撤销释放名额用例，最终CentralTagMySqlTest5项在tag-limit-test-module.log全部PASS；包括真实MySQL审计故障回滚字典/关联/命令、Owner竞争、版本/代际、冻结/CLOSED、撤权/外租户/STOPPED/503。最初两次-am窄测试因根POM硬设failIfNoTests而在没有匹配测试的shared-kernel停止，未执行业务测试；安装本地依赖后仅跑目标模块成功，未改POM或放宽断言，失败日志保留。最终产品源码与完整回归相同，后补仅测试；install/package与两仓hygiene无阻断。

36项Python与231入口契约通过；真实--tags中央演练rehearsal-c1893723fb4a正在10.254.86.0/24运行，tag-owner-rehearsal.log，含成长/基础会员/目录/库存/CATALOG后台回归，不含浏览器。实际联调完成前T1不标DONE，T2仅有私有页面草案，未实施。

### T1最终本地DONE

实际rehearsal-c1893723fb4a最终182PASS，tags_checked/growth_checked均true，runtime_switched/production_ready均false；真实标签定义无read/无assign、独立assign无read、字典/关联读、真实目标与外租户拒绝、关联版本冲突、撤销后保留/重授递增、四次效果恰四条审计（字典真实tag类型1、会员分配3）、撤权后旧回执拒绝且read保留、真实中央停止503及各域后台恢复回归通过。自有PG/IdP/JVM由finally停止，子网86与数据保留。

完整417项412PASS/5skip加最终标签5项窄测试（新增上限1项）、36项工具/231入口、SDK安装/package、两仓hygiene与diff检查通过；产品代码验证后无修改。T1本地DONE，Git交付中；下一T2页面/浏览器，标签后端通过不代表页面或其他域已完成。
