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
|CE04-B1|B0；实际Member Owner、独立族/V55、内部最小订单来源、真实MySQL与中央联调|DONE（本地）|
|CE04-B2|B1；独立SSO行为页、动作提示、真实读写/错误恢复与1440/390浏览器|DONE（本地）|

本技术细化消费已批准TENANT_ALL和三个能力，不增加岗位或默认授权，不改变交易门店权限边界。重建只是既有会员行为投影维护，不是新的员工订单查询能力。V55仅新迁移，应用前后均不得修改既有V54历史；空批次的命令归属也不可伪装成真实会员对象。


B1实现核对：重建由commerce-app应用服务编排OrderApi.behaviorSources与MemberBehaviorApi.projectOrder；前者只返回id/createdAt并在Owner SQL按tenant+稳定ID游标过滤，后者沿既有成长来源/会员锁当前读规则。原after/limit/next/scanned/done与旧幂等摘要JSON保持，中央模式才追加稳定身份。有限批次末尾再次核对本地许可期限，过期整批回滚。真实集成的历史订单/成长来源为明确隔离种子，不声明演练发生过实际支付/履约；既有MemberBehaviorTest保留真实下单/支付事实/履约/投影回归。


## B2页面与动作提示契约

固定/operations/member-behavior?tenant_id，三个Tab：行为查询、修改偏好、历史成交补建。沿原AntDesign/SSO布局，凭据局限本域允许API，不写旧全局Token。已知会员编号查询详情/事件，刷新和按真实sequenceId游标50；显示真实姓名/会员状态、偏好版本/生日月日/旅程接收状态、30日浏览/加购/成交笔数/净消费、最近订单/加购与未知成交时间。不请求其他域列表。

修改独立hint GET operations/member-behavior/update-access，只提示完整租户能力；form已知memberId/expectedVersion>=0安全整数、birthday可空（提交null）或合法MM-DD含02-29、旅程接收/关闭显式选择、reason<=256。不能依赖behavior.read预填才能操作。服务端仍核对会员Owner和ACTIVE；409保留输入再核对版本。

补建独立hint GET operations/member-behavior/rebuild-access；form after可空或稳定标识、limit1—50整数，真实返回next/scanned/done（扫描数不是新增/会员数），手动使用返回游标开启下一批。重建不要求read/update，不增加自动循环或后台调度。unknown冻结原key/path/body，原样重试；两个写区独立意图，退出/Tab保护，401卸载/403独立/503不冒充空结果。结果保留原目标/实际版本。

真实浏览器阶段update-only/rebuild/read/revoked/outage；修改无read、生日校验/02-29、响应丢失相同key/body重试；重建无read和limit校验/真实游标；真实facts/events、409保留再纠正及未知无成交显示；单独撤销写保留读，外租户/401/真实503，1440/390实际查看。新增固定壳+两hint=237入口，能力/角色不变。
