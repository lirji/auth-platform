# 受限目录发布运行说明

MG10默认关闭；下面是已验收能力的操作顺序，不授予生产部署或生产开启权限。测试与生产使用独立权限实例和数据库，各环境专用Casdoor客户端／受众／密钥；不要复用个人Owner Token作为CI机器凭据。

## 准备固定目标

1. migration owner在明确的目标专用数据库执行追加V23／V24；HTTP只validate迁移，不自动迁移或初始化。保留原表、快照和审计，不修改旧迁移。
2. 创建专用、非共享Casdoor应用，仅client_credentials、JWT／RS256、最长300秒，无密码、注册或登录跳转。测试可使用回环HTTP，prod／production必须HTTPS。
3. 在既有0600治理配置中加入固定publisher字段；关闭时不读取这些字段。目标UUID由受控初始化产生，不能根据请求自动选择数据库。
4. 使用当前Admin制品运行`CatalogPublisherTargetCli initialize-target <private-properties>`。配置须含publisher.initialize.operator及publisher.initialize.reason；仅首次写入，相同目标幂等核对，异目标拒绝。普通HTTP和启动不调用初始化。
5. 校验目标与实例后显式设置authz.governance.publisher.enabled=true。当前HUMAN Owner先将选定应用置为GUARDED（旧写节点已退出），再通过独立Owner接口给予固定slot有限期委派。默认false，不随部署或应用启动自动发布。

## 配置字段

| 字段 | 语义 |
|---|---|
| publisher.target-instance-id | 此专用数据库唯一固定实例UUID |
| publisher.environment | 当前实例固定环境 |
| publisher.ids | 最多16个slot UUID，逗号分隔 |
| publisher.<slot>.service-principal-id | 固定SERVICE主体UUID |
| publisher.<slot>.application | 固定应用标识 |
| publisher.<slot>.subject | Casdoor实际应用ID（admin/专用应用名） |
| publisher.<slot>.issuer／jwks.uri | 固定可信发行方与公钥地址，不能从body构造 |
| publisher.<slot>.audience／client.id | 同一专用client ID，环境间不同 |
| publisher.<slot>.client.secret | 受控私密文件中的introspection凭据 |
| publisher.<slot>.version-probe.client.id／client.secret | 版本探针专用管理凭据，后者完整键为version-probe.client.secret |
| publisher.<slot>.connect-timeout-ms／read-timeout-ms／maximum-concurrent | 沿用TokenAuthority有界配置，默认2000／2000／8 |

所有真实密钥留在受控凭据文件，禁止放Git、前端、日志、报告或命令行。配置是一份启动时类型化快照，变更后须按既有发布流程重新装配，不能假设自动热刷新。

## 请求与恢复

Owner委派和机器路由、严格字段及错误见[MG09_CONTRACT](MG09_CONTRACT.md)。机器预览只有AUTOMATION_ALLOWED且ticket非空才可发布；REQUIRES_OWNER_REVIEW进入既有真实Owner固定预览／发布流程，机器不能提交decision、影响确认或operator。

机器发布记录实际SERVICE主体以及独立Owner／委派／目标actor。source.commit与artifact_hash是发布声明，不证明当前运行制品；MG06运行核验未知时仍显示UNKNOWN。发布不会授予新增权限、修改角色或延长既有Grant。

请求超时结果未知时，保留原command_id、preview_id、目标和输入，查询原命令或同体重试；不要换键。当前委派仍须有效，已禁用身份没有原命令认证豁免。委派禁用用当前Owner的版本CAS；轮换同时禁用旧委派，使用新客户端、新slot、新委派，不能复用旧client或只换secret宣称旧Token已撤销。

紧急停止工具或关闭机器入口会阻止后续发布，已提交目录不会自动撤销。目录修正采用更高版本的合法候选；代码回退需保留追加表和旧快照，并检查GUARDED旧写节点兼容，不能恢复不理解门禁的旧写程序。业务授权回退／补偿走原授权来源，不由目录工具恢复。

## 本机隔离验收

先构建当前Admin及其嵌套治理制品，再运行`python3 deploy/governance-publisher-runtime.py`。工具只新建随机专用库与IdP客户端，检查两个独立目标；只停止本工具持有的回环进程。失败日志、凭据和检查点保留在忽略的0600／0700私密目录，不删除共享数据或更新原部署。
