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
