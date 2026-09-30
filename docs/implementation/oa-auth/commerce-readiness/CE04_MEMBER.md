# CE04会员扩展验证记录

## CE04-P0基础执行协议本地DONE

auth基线3a8f7e9，任务分支feat/commerce-member-execution；消费CONTRACTS_COMMERCE_MEMBER.md。仅四基础能力member.read/create/profile.update/status.update→commerce_member，60秒HUMAN执行引用、完整TENANT_ALL scope；已有对象读取/修改要求匹配类型和规范Owner事实，创建不能伪造已有对象事实。execution-check现在与issue/scope一样要求显式Owner登记。旧CATALOG/库存/目录协议保持，新API/真实Owner配置/生产清单均未新增。

验证：251单元测试PASS（member-core-unit.log）；专用PG7cdc4197661c+现有隔离SpiceDB执行ExecutionAuthorizationIT共4项PASS（member-core-integration.log），其中新项遍历四能力的范围/类型/租户/代际/超期/伪门店事实/未知能力、撤销与重授不能复活，旧三项全部回归。该验证使用协议事实，不是实际商城会员Owner验收，后者留P1。专用PG已由finally停止，数据保留。

SDK install、Boot4兼容和全模块package PASS，member-core-sdk-install.log/member-core-boot4.log/member-core-package.log。SDK公开方法和JSON不变；新增协议常量不会要求旧SDK重编译。hygiene无阻断，保留未配置Java formatter/静态分析限制。git diff --check PASS。

本片没有商城数据迁移、员工授权或浏览器改动；P1业务Owner/V52与P2会员页面未实施，后续成长/周期/积分等尚待细化。Git/CI交付另记，不把P0完成算成CE04完成。
