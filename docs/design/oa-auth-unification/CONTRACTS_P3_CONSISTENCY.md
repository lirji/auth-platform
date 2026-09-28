# P3 持久化栅栏与投影契约

## P3-03：切换与主库快照

授权沿P3原方案。新增policy_partition仅由受控Runtime启用，按tenant/app/env唯一；directory_fence按tenant唯一。旧分区不自动接管，启用后不能退回无栅栏模式。启用命令具备管理委派、幂等及审计。默认入口仍关闭。

政策epoch只在安全事实变更时增加：Grant创建/撤销version、应用准入、应用清单版本（含紧急能力停用后的扩展）、管理委派。目录epoch覆盖主体/成员/企业状态与代际、目录记录和来源状态。更新与epoch增加在同一PG事务，由所有既有写路径共用的数据库触发器保障。投影回执PENDING→ACTIVE不增加desired，以免自我触发无限投影。租户级目录栅栏有意保守阻断所有关联应用。

desired/applied均为非负bigint；状态READY/UPDATING/BLOCKED显式编码。READY要求两版本相等且存在图水位。BLOCKED不会被普通业务变更自动清除。ZedToken仅当作不透明字符串保存，不做大小比较。P3-03不伪造图确认；真实READY仅由后续CAS执行器产生。

A和C各在独立 `REQUIRES_NEW + REPEATABLE_READ` 短事务（5秒）读取主库，不跨图调用保留数据库事务。每份快照覆盖：Principal/成员/企业状态和版本、成员generation及期限、准入、当前清单、policy和directory READY/epoch/watermark、同一快照内候选Grant。C必须重新进入事务，不能复用调用方旧事务快照。过时身份、栅栏未就绪、两次快照不同返回AUTHZ_STATE_NOT_READY（503），没有有限重试后的宽松放行。

原P2读取和投影在升级版本发现严格分区时拒绝执行，避免绕过CAS；新图使用独立P3定义，旧P2迟到写不能改变P3关系。切换前必须将试点受保护入口全部路由至升级节点；旧二进制不具备这一检查，禁止把仍在旧进程服务的入口标记切换成功。回滚应关闭受保护入口，不能重新开旧判权。仅隔离试点启用，不动共享图或生产。

## P3-04a/b/c：后续约束

每批ProjectionOperation固定不可变payload/content_hash/expected_marker/new_marker，不复用operation UUID。WriteRelationships的前置条件和关系/marker切换同一远端事务。初始化MUST_NOT_MATCH，后续MUST_MATCH精确旧marker；多个/未知marker隔离。SQL租约不授予换marker重试旧payload的权利。

租约领取和operation规划在PG短事务，远程调用在事务外；失租进程不能确认READY。恢复先完全一致读取marker，图已提交时补receipt/token；新目标仍未收敛则继续UPDATING。多个批次全部核对后，仅desired=target时CAS到READY。图响应未知保持处理中，不能回滚权威管理状态。

## P3-05/06：后续读写语义

严格批量响应校验请求关联、基数、逐项状态及错误；Conditional/缺项/重复/超时拒绝。对policy和directory两个水位分别执行同一批检查，交集才允许；不取字符串max。撤权受理与完成分开，完成必须有receipt且当前栅栏已覆盖对应操作。P3-06补能力紧急停用、组资格变化和到期行为。P3-07必须保存真实进程kill/双执行器晚到结果，不用当前PG单测替代。
