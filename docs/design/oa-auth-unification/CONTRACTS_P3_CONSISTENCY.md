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

## P3-04b 实施细化（同一已批准算法）

projection_stream按policy或directory的真实FK二选一登记，保存固定fence_id、最后确认marker/批号、当前目标与扫描游标、有界失败重试，以及worker_id/lease_generation/lease_until。每次领取生成新代际，使用数据库时间；本地租约15秒，每次调用一个批次，进程入口最多30秒。不在网络期间持有SQL事务。

projection_operation固定operation UUID、fence、target_epoch、单调batch_no、expected_marker、规范化payload/hash、末游标和是否最后一批；PENDING/APPLIED/SUPERSEDED/QUARANTINED为稳定状态。每分区只允许一个PENDING；同分区target+batch唯一。payload/expected/new marker等语义字段由数据库不可变触发器保护，只有状态/尝试信息可更新。没有「读新marker后改旧operation」入口。

规划按当前权威Grant（包括已撤销来源）稳定id扫描，51条探测是否还有下一批，实际每批最多50条；新epoch从头重新扫描，不静默丢掉尾部。关系与marker同一次CAS写，所有批次确认之前保持UPDATING。目录当前直接成员阶段使用独立空关系marker批次，P3-06组关系变化复用该分区和批次协议。

每步先读取远端marker：等于最后确认值才能规划/重发固定operation；若指向同分区已持久化且比已确认更新、expected与最后确认一致的operation，先恢复其receipt，不重发payload。未知、回退或多marker立即BLOCKED。恢复确认需要当前有效租约，旧执行者不能提交READY。新desired覆盖旧PENDING时标SUPERSEDED并创建新operation；迟到旧CAS至多在新CAS之前完成，后续新worker必须重新读权威状态和恢复已知operation，不能换expected复用旧内容。

receipt按operation唯一，保存graph_marker、content_hash、opaque zed_token和confirmed_at。末批确认仅在desired仍等于operation.target_epoch且非BLOCKED时READY；并发管理更新保留UPDATING。PENDING→ACTIVE仅匹配当前Grant version，不恢复REVOKED。每步失败保留操作，指数退避带抖动，上限60秒/最多5次后BLOCKED；协议/未知marker直接隔离。受控审计重试由P3-06管理接口补齐。

运行历史不自动删除。当前marker引用的operation、所有非终态和幂等记录必须保留；本隔离验证阶段保留全部证据。生产保留/归档期限需沿P7运营策略确认，不能编造法规保留期。这里不引入新的定时调度或消息中间件。
