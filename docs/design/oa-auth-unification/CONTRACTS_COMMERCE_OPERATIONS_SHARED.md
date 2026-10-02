# CE06/07 共享有限执行扩展

用户已授权全量角色权限与真实页面接入；本扩展只登记 COMMERCE_PERMISSION_BINDINGS 的闭集能力，不自动迁移任何租户。

- order/payment/fulfillment/aftersale/refund 共14项采用实际 store Facts，保留当前与原Grant路径交集，短引用最长60秒，不隐含 order.read 或其他能力。
- ops_page 十项只接受 TENANT_ALL；create集合，其余已有对象 Facts 必须正内容版本；状态CAS不能充当内容版本。
- event/runtime 八项采用 commerce_runtime TENANT_ALL；event.pump、runtime.replay.preview/create仅集合，其余追加真实Owner事实；运行时没有内容正版本时保留原版本。
- 只有真实 runtime.replay.create POST 可签发86460秒有限引用。这是授权窗口；MAX_RANGE31days为历史选择范围，不是业务deadline。原Grant较早截止仍立即限制实时检查。PAUSE/RESUME/CANCEL不能替换原来源，不额外增加业务DTO字段。
- HUMAN、原引用、应用/环境/调用方、主体代际、目录代际、实际资源类型及撤权均沿用执行契约。新授不扩大旧引用。

验证：任务隔离Mavenrepo reactor install通过；独立PG16/SpiceDB ExecutionAuthorizationIT20方法通过，新增18个ops/runtime能力与14个门店能力的真实路径、时限、事实、能力置换及撤权检查。数据库仅本任务专用19532/19544，原D2未操作。
