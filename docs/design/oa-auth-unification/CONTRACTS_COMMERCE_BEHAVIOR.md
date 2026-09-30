# CE04-B 会员行为员工接入实施契约

消费已批准member_behavior.read/update/rebuild三独立HIGH权限，commerce_member + TENANT_ALL。B0中央有限60秒HUMAN执行组合；read/update需要真实Member事实，rebuild为租户级有界集合操作，scope-only拒绝伪会员resource-check。

B1独立MEMBER_BEHAVIOR族/V55。detail/events读取实际Member Owner并前后scope/会员版本复核；profile独立能力，路由锁→会员锁/Owner版本复核→准入期限早于旧命令回执，稳定主体代际加入中央幂等摘要，原birthday/旅程开关/ACTIVE/资料版本保留，写入和身份审计同事务。客户本人detail/profile/events以及record/projectOrder/facts/journeyAllowed内部语义不受员工接管替换。旧ADMIN在CENTRAL/STOPPED不可通过behavior管理入口。

rebuild保持原POST路径、after+limit<=50、next/scanned/done及同步原事务，不新增异步任务或后台权限。应用层专用重建服务先取得独立集合许可，guard路由锁/期限在旧回执之前。订单Owner提供仅内部调用的有限投影源端口，仅返回本租户真实orderId/createdAt，SQL参数绑定/稳定游标，不借adminList或伪造ADMIN，也不返回订单金额/地址/明细给操作者；会员projectOrder仍从真实member_growth_order锁后当前读完成状态/净额。rebuild不隐含order.read或behavior.read/update。

重建实际审计目标是持久命令批次而不是伪会员。固定本能力映射commerce_member_behavior_batch，resourceId为实际command_key；行中tenant/actor/operation/key联合唯一指向platform_command，empty批次也有真实命令。不新增中央授权资源类型，不允许HTTP自选审计类型；V55新增允许分类和注释。命令摘要包含输入+中央稳定身份；审计失败回滚投影/命令/归属。不把重建同步批次当长程持久任务，客户端通过返回游标显式发起下一批，每批独立鉴权。

B2固定/operations/member-behavior?tenant_id，已知会员详情/事件和独立偏好修改/重建Tab；仅请求本域API与update/rebuild独立提示，不隐式取会员列表/订单。UTC生日月日与旅程选择保持原含义；重建输入after/limit返回真实进度，未知结果锁原键，409保留，401卸载，403独立，503失败关闭，退出/Tab保护和1440/390。

验收B0真实PG+graph有限组合/错误类型/范围/期限/代际/撤权；B1真实MySQL及中央联调覆盖无read的update/rebuild、真实会员/跨租户、客户本人不回归、生日/ACTIVE/版本、Owner竞争、审计故障事务回滚、同键/代际/撤权/STOPPED/503、真实订单来源与空批次、limit边界与游标。B2真实浏览器/截图/未知响应重试及原页面回归。不得为了新测试改旧业务语义。


## 有序实施切片

|ID|依赖/可观察验收|状态|
|---|---|---|
|CE04-B0|T2；有限3中央执行组合、scope-only重建、真实PG+graph/SDK Boot4与既有组合回归|DONE（本地）|
|CE04-B1|B0；实际Member Owner、独立族/V55、内部最小订单来源、真实MySQL与中央联调|TODO|
|CE04-B2|B1；独立SSO行为页、动作提示、真实读写/错误恢复与1440/390浏览器|TODO|

本技术细化消费已批准TENANT_ALL和三个能力，不增加岗位或默认授权，不改变交易门店权限边界。重建只是既有会员行为投影维护，不是新的员工订单查询能力。V55仅新迁移，应用前后均不得修改既有V54历史；空批次的命令归属也不可伪装成真实会员对象。
