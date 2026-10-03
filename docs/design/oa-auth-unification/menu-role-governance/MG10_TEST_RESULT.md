# MG10 受限机器发布验证

结论：PASS（既有格式化工具限制），MG10 DONE；MG00–MG19整体仍ACTIVE，下一片MG11。入口默认关闭，仅隔离演练启用；不生产部署，不更新原目录或业务Grant。

## 实现边界

- 独立VerifiedMachine、固定有界slot和机器前缀。每请求RS256、准确issuer／subject／type／用途／azp／唯一audience／原iat与exp，最长300秒，并实时版本探针和introspection。机器Token不进入HUMAN Owner、角色、Grant或能力恢复路径；配置关闭时不读取机器密钥，错误不转发旧JWT链。
- V23追加唯一实例目标、有限期委派、不可变事件／票据关系／SERVICE发布actor五表，全部表与字段中文注释及精确FK。V24追加发行方客户端与应用subject不可复用约束：禁用后换slot不能让旧Token继承新委派。V23真实执行后发现该不变量需要补强，使用新V24，没有修改已执行迁移。
- HUMAN当前Owner创建／禁用／查询委派，SERVICE只预览／发布／查询自己的原命令。固定实例／环境、ACTIVE SERVICE／登录映射、ACTIVE当前HUMAN Owner、ACTIVE未到期委派与Token期限必须同时成立。最多30天委派、100条完整列表、16个配置slot，停用不续期或复活；轮换用新客户端、新slot和新委派。
- ApplicationCatalog共同内核保留MG05应用锁、双摘要、固定十分钟票据和原命令优先回执。机器只发布完整能力与执行入口不变的展示／纯分组调整；首次、新能力和路由／any_of／业务菜单增删返回REQUIRES_OWNER_REVIEW、无机器票据。HUMAN与机器票据不能交叉消费。
- 快照、展示、指针、原发布回执、实际SERVICE审计和actor同事务。actor写入和查询后使用PG clock_timestamp再次验证Token和委派；不把事务开始时有效视为提交时有效。IdP HTTP在事务外验证，不宣称跨系统原子撤销。
- 委派事件是原命令历史：创建命令回执不因禁用改变；禁用回执保存禁用命令及原因，列表中的command_id／reason仍为原创建事实。当前资格始终看主库状态及期限。

## 实际验证

| 验收 | 方法／证据 | 结果 |
|---|---|---|
| 编译、严格JSON和认证 | Admin reactor 242单测：Protocol20、Core27、Governance141、Admin54；包括41认证测试和18自动资格／严格请求测试 | PASS |
| 数据库与目录兼容 | 专用PG16.15共48项：Publisher15、Guard10、Release5、Catalog6、Published8、Drift4；真实V23／V24迁移 | PASS |
| 幂等、旧基础、身份与FK | 原命令同体／异票据／异委派、后来HUMAN版本、双机器同基础仅一成功；目标不可重绑、客户端不可复用、HUMAN／SERVICE票据隔离、停用Owner和SERVICE拒绝、actor不可篡改 | PASS |
| 时间与撤销竞争 | 真实PG actor INSERT门闩；Token／委派在事务中到期，整次发布回滚；禁用争用同应用锁，发布完成后禁用完成，后续机器请求拒绝 | PASS |
| 审计及业务不变量 | 审计或actor INSERT失败，快照／回执／指针全部回滚；委派事件失败回滚新SERVICE与绑定。已有真实角色、SCOPED Grant、范围和管理委派row_to_json前后完全一致 | PASS |
| 安装版IdP与HTTP | 最终mg10-http-039fbb87c060，Casdoor v4.11.0实际client_credentials、300秒JWT与introspection、短Token到期、IdP单个自有Token撤销、当前委派禁用／新客户端轮换；两个独立PG实例test／staging模拟环境隔离；37 HTTP／CLI检查 | PASS |
| 默认关闭与保密 | 无效机器配置在关闭状态不读取；匿名及HUMAN均独立403，开启后匿名401、HUMAN／机器交叉401、错误目标／环境403、严格JSON400、原命令404。客户端和Token仅0600私密文件，DTO不含secret | PASS |
| 静态、卫生和事务审查 | Python编译、diff --check、Code Hygiene IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无BLOCKING。事务中无远程调用，不写角色／Grant；统一脱敏CLI异常是显式边界，100／16等为契约命名上限 | PASS（工具限制） |
| 页面／实际生产 | 无前端变更，视觉N/A；实际生产环境、容量和部署验收未执行，也不是本片授权范围 | N/A |

私密证据在`.local/menu-role-governance/`：mg10-regression.log、mg10-hygiene.json、mg10-evidence.json和最终HTTP目录。源码27文件指纹23b2813712247c5ac6a3f5f876c6149472a30a37ccb803c4b51c260eaab4a630，基线8ee2ab6；逐文件SHA和制品SHA保留。纯展示发布的source是测试声明，不当作实际部署证明；运行状态仍UNKNOWN。

## 失败和限制

- 扩展PG第一轮门闩等待碰到正式2秒lock_timeout；夹具改为1秒期限后真实到期回滚通过，未放宽产品超时。夹具最初尝试管理员自授权，被既有规则拒绝；改为同租户另一真实成员的SCOPED来源，未放宽自授权限制。错误假设TENANT_ALL总有范围行后改用实际SCOPED行验证四类非空事实。
- 首个HTTP制品栅栏拒绝旧嵌套JAR；强制生成当前归档后重验。默认关闭的HTTP首次发现框架/error转发触及旧认证链，修复独立脱敏错误处理后真实复验。旧FAIL和专用库／客户端继续保留。
- 当前仓库未配置统一Java formatter／静态分析器；周边约定、实际编译、diff和卫生检查通过，不虚称工具执行。PG测试按真实本机依赖运行，不用Mock证明事务。
- test／staging是两新隔离目标模拟，绝非真实生产；测试与生产IdP client／audience／secret必须分别管理，生产HTTPS。不清理历史、不重启原部署，不自动部署或开启生产。

恢复使用新目录版本修正；禁用委派停止后续机器操作，不撤销已发布目录或恢复任何业务Grant。启用／回退操作见[PUBLISHER_RUNBOOK](PUBLISHER_RUNBOOK.md)。

## CI配置边界补证

MG10产品8ad560d正常推送后检查workflow，发现新PG测试只读取本机私密配置，而既有CI使用受限数据库环境变量。补齐与既有测试一致的输入分支；本机移除GOVERNANCE_TEST_CONFIG，按CI三项环境变量实际执行18规则单测和15真PG，均PASS。首次带skipTests命令未执行IT，只作为编译记录，实际证据为mg10-ci-config-actual-tests.log。该修正只改测试配置入口，产品源码／真实HTTP制品未变；原产品指纹保留，当前测试文件另记SHA。

最终补丁4371b486888118c0c3e78304002d18dbbcd1f87c已正常合入并推送main；[精确CI37112631055](https://github.com/lirji/auth-platform/actions/runs/37112631055) completed SUCCESS，headSha完全一致。第一次配置入口失败保留，最终CI完成而非用历史run替代。
