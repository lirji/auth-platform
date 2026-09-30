# CE04-T 标签员工接入实施契约

消费已批准member_tag.read/define/assign，授权资源类型保持commerce_member、完整TENANT_ALL/HIGH。字典定义/字典读仅scope，不伪造会员对象；assign及按会员查询assignments由真实MemberMapper ID/version作Owner facts。read兼容集合与会员实例，define的resource-check拒绝，assign必须对象许可。

独立MEMBER_TAG能力族，有限精确四既有入口GET/POST admin/member-tags、POST admin/member-tags/{memberId}/assign、GET该会员/assignments。参数路径名id实际是memberId，不是tagId，正式契约说明以免把字典ID误作会员Owner。assign body沿用tagId/active/expectedVersion/reason，原CLOSED限制、活跃64上限、关联版本0→1、失活保留/重授递增均保留；幂等摘要中央加稳定身份，路由锁→会员锁/Ownerversion复核早于旧回执，业务和身份审计原事务。assignments对目标真实存在与归属先判权，冻结仍可读，不拿requireActive替代范围。

字典define无会员对象，身份审计不可冒充commerce_member ID。在仅本能力的审计映射中固定实际目标kind=commerce_member_tag，ID=真实tagId；授权类型仍commerce_member，不开放新授权类型/Ownercheck。V54扩MEMBER_TAG与审计允许type，不改已执行V53。对外API与资源权限契约不变，审计映射明确区分权限类型与业务目标类型；原mapper/commands保存真实字典。已核对EmployeeAuthority.audit及EmployeeAuthorityMapper：resourceType为单独绑定参数，现有业务消费者没有把所有审计行反查为会员。V54以新允许值扩展check，旧store/merchant/member/policy语义及旧迁移保持。

T0中央有限3能力，define scope-only/read+assign资源check；T1真实商城接管/版本并发/幂等/事务审计/跨租户/客户与原业务回归、实际中央有限角色联调；T2独立SSO标签页，不复用会同时请求成长/积分的旧组件。页面字典游标+定义、已知会员关联游标+分配/撤销表单；定义和分配独立提示，不隐含读。沿用未知结果锁原键/409保留输入/401隐藏/503不误报空数据/1440和390浏览器截图真实验收。G2 auth8351043/commerce646ebd5已交付，413项408PASS/5skip与183项真实隔离通过；按下表串行实施，不发布生产清单。

## 有序切片

| ID | 依赖与验收 | 状态 |
|---|---|---|
| CE04-T0 | G2；auth有限3能力/60秒HUMAN/全租户/define只scope，真实PG+graph对范围/类型/Owner/代际/撤权重授/到期与旧组合回归；SDK/Boot4 | DONE（本地） |
| CE04-T1 | T0；MEMBER_TAG/V54、实际字典审计类型、真实Member Owner/事务/版本/64上限/幂等/撤权与真实MySQL/跨进程中央验收 | DONE（本地） |
| CE04-T2 | T1；独立SSO标签页/提示与真实读写、错误恢复/1440及390截图/既有页面回归 | DONE（本地） |

T0仅增加有限执行能力，不把协议测试中的合成会员事实当真实商城Owner验收；T1按实际会员Owner完成。read在字典列表用scope、在assignments用Member facts，define不接受伪会员resource-check。未知能力和伪门店/部门范围继续失败关闭。审计类型commerce_member_tag仅本地业务目标分类，不新建中央授权资源类型，也不允许调用方任意传入审计类型。

## T1锁与审计实施细节

MemberTagService注入已有EmployeeAccess及MemberMapper，无新业务接口/DTO。definitions读前后scope指纹核对；assignments先全租户资格、读取真实会员并resource-check，读后scope/会员版本复核，不用标签ID冒充会员。assign的guard先路由锁、真实会员FOR UPDATE比较版本、再期限核验，早于Commands旧回执，之后沿用原GrowthMapper标签字典/关联与会员状态约束。

define/assign在中央模式把稳定principal/membership/generation追加原幂等摘要，LEGACY/SHADOW保留原摘要。define审计固定映射commerce_member_tag和真实tagId，assign审计仍是commerce_member和真实memberId；路径参数、Owner事实和审计目标的职责分开。EmployeeAuthority只有MEMBER_TAG_DEFINE可作此固定映射，HTTP不能指定审计类型。V54只扩族/审计check及中文注释，保留所有旧类型/触发器；已执行后不可修改。

验证T1以真实MySQL覆盖字典定义不隐含读、分配不隐含读、关联版本/撤销/重授/冻结/CLOSED、活跃64上限与名额释放、Owner竞争、审计异常回滚业务与命令、代际摘要、缺失/外租户目标、撤权/STOPPED/503；实际中央有限600秒角色联调另证scope与资源事实，标签定义审计1条、分配/撤销/重授审计3条，旧模块后台恢复回归。客户/内部规则消费仍沿原Growth facts，不把员工撤权套在已承诺系统作业上。

## T2页面与提示实施契约

固定/operations/member-tags?tenant_id=<UUID>，沿现有SSO/AntDesign。仅允许真实字典GET/POST admin/member-tags与已有会员GET {id}/assignments、POST {id}/assign，以及两独立提示GET operations/member-tags/{define|assign}-access；提示固定对应能力、OPERATOR/执行引用与scope双核验，不替代提交时Owner。静态壳/SSO allowlist增加该固定页，未接管能力不被旧聚合MemberGrowth组件隐式请求。

字典列表按tagId游标50条，定义表单tagId/name沿既有DTO；会员关联按已知memberId查询，列表显示tagId/active/version/source/reason并按tagId游标50条，不暗中请求会员姓名或成长。独立分配岗位用已知memberId/tagId/expectedVersion/active/reason；选择分配或撤销时提交布尔active，不凭本地UI推断版本/会员状态。定义不隐含读，分配不隐含字典/会员读取；首次关联版本0，后续必须核对关联版本；注销限制、64活跃上限仍以服务端为准。

两个写区各保留原path/body/key，未知结果锁输入并只能原样重试，之后403不能丢弃原意图。409保留输入；401卸载业务；403单区拒绝；503不能当空列表/成功。切Tab保留输入，退出/离开提示未保存和未知意图。界面复用原有布局/字号/中文文案，不换设计系统。

真实验收定义无读、分配无读、真实响应丢失相同键重试、版本409后纠正、撤销保留关联并增加版本、独立撤权/外租户/401/真实依赖503、列表/表单/结果/未知/冲突及1440/390截图实际查看，共享成长/会员/目录/库存/CATALOG回归。新增静态壳加两个提示共3入口，预期231→234，不新增能力/岗位/schema/依赖。
