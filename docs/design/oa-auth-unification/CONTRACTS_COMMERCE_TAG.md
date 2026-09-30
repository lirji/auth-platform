# CE04-T 标签员工接入实施契约

消费已批准member_tag.read/define/assign，授权资源类型保持commerce_member、完整TENANT_ALL/HIGH。字典定义/字典读仅scope，不伪造会员对象；assign及按会员查询assignments由真实MemberMapper ID/version作Owner facts。read兼容集合与会员实例，define的resource-check拒绝，assign必须对象许可。

独立MEMBER_TAG能力族，有限精确四既有入口GET/POST admin/member-tags、POST admin/member-tags/{memberId}/assign、GET该会员/assignments。参数路径名id实际是memberId，不是tagId，正式契约说明以免把字典ID误作会员Owner。assign body沿用tagId/active/expectedVersion/reason，原CLOSED限制、活跃64上限、关联版本0→1、失活保留/重授递增均保留；幂等摘要中央加稳定身份，路由锁→会员锁/Ownerversion复核早于旧回执，业务和身份审计原事务。assignments对目标真实存在与归属先判权，冻结仍可读，不拿requireActive替代范围。

字典define无会员对象，身份审计不可冒充commerce_member ID。在仅本能力的审计映射中固定实际目标kind=commerce_member_tag，ID=真实tagId；授权类型仍commerce_member，不开放新授权类型/Ownercheck。V54扩MEMBER_TAG与审计允许type，不改已执行V53。对外API与资源权限契约不变，审计映射明确区分权限类型与业务目标类型；原mapper/commands保存真实字典。已核对EmployeeAuthority.audit及EmployeeAuthorityMapper：resourceType为单独绑定参数，现有业务消费者没有把所有审计行反查为会员。V54以新允许值扩展check，旧store/merchant/member/policy语义及旧迁移保持。

T0中央有限3能力，define scope-only/read+assign资源check；T1真实商城接管/版本并发/幂等/事务审计/跨租户/客户与原业务回归、实际中央有限角色联调；T2独立SSO标签页，不复用会同时请求成长/积分的旧组件。页面字典游标+定义、已知会员关联游标+分配/撤销表单；定义和分配独立提示，不隐含读。沿用未知结果锁原键/409保留输入/401隐藏/503不误报空数据/1440和390浏览器截图真实验收。G2 auth8351043/commerce646ebd5已交付，413项408PASS/5skip与183项真实隔离通过；按下表串行实施，不发布生产清单。

## 有序切片

| ID | 依赖与验收 | 状态 |
|---|---|---|
| CE04-T0 | G2；auth有限3能力/60秒HUMAN/全租户/define只scope，真实PG+graph对范围/类型/Owner/代际/撤权重授/到期与旧组合回归；SDK/Boot4 | DONE（本地） |
| CE04-T1 | T0；MEMBER_TAG/V54、实际字典审计类型、真实Member Owner/事务/版本/64上限/幂等/撤权与真实MySQL/跨进程中央验收 | TODO |
| CE04-T2 | T1；独立SSO标签页/提示与真实读写、错误恢复/1440及390截图/既有页面回归 | TODO |

T0仅增加有限执行能力，不把协议测试中的合成会员事实当真实商城Owner验收；T1按实际会员Owner完成。read在字典列表用scope、在assignments用Member facts，define不接受伪会员resource-check。未知能力和伪门店/部门范围继续失败关闭。审计类型commerce_member_tag仅本地业务目标分类，不新建中央授权资源类型，也不允许调用方任意传入审计类型。
