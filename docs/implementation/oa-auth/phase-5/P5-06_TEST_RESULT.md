# P5-06 TEST_RESULT

状态：PASS。受邀PARTNER在真实commerce指定门店商品上完成申请→OA审批→投影回执→持久导出→下载→到期/撤销拒绝，独立商品read保留。不是供应商订单或Mock演示。

- 真实跨进程e2e-b74400d225：72项HTTP检查PASS，7项commerce/portal浏览器与3项OA浏览器PASS。邀请在真实页面接受、无隐含应用权限；获单独S001 read后仅看到P001，其他门店P002/其他租户FOREIGN及商品update直接请求403。
- 浏览器从商城跳本人申请表提交固定product.export策略；OA实际Flowable待办查看不可变快照后批准。外部OA待办/审批依据拒绝。真实签名回调丢首个ACK、重试同payload、单一OA来源仍通过。
- 图故障时页面显示批准/等待实际生效，商城导出503。实际投影operation回执完成后ACTIVE；浏览器提交→开始→批次→下载实际JSON，只有P001；URL刷新恢复任务，1440/390截图已实际查看。
- 另一个S003短申请实际审批、导出P003及下载成功；OA和清理worker停止后，固定有效期经过，旧下载403，S001独立查询正常。生命周期收敛后，原S001申请取消只撤OA来源：旧任务/下载/新导出403，P001查询仍正常，页面与来源回执一致。
- 10项真实MySQL CentralScopeMySqlTest PASS。新增product.export全生命周期重验/只撤导出read仍可用；集成复核补齐中央商品写入仍调用Store Owner检查门店/商家ACTIVE，停用时拒绝，未借中央ACL绕过原业务不变量。
- commerce UI构建/Prettier、两仓Code Hygiene PASS（既有无统一格式化工具限制仍记录）。无新增数据库迁移/中间件；沿用原幂等、配额、50行批次、1000行总量及下载版本复查。

兼容：product旧read导出有意收紧，必须登记独立export能力/审批策略。旧product任务fingerprint不匹配，需重新申请当前授权并创建任务；不回退为read代替export。store P3旧范围试点契约不扩大。代码回退不回收已下载文件或已提交商品效果。

前两次工具失败分别为私密夹具O_EXCL重复写和MySQL默认字符集导致中文乱码，均已修复并完整重跑；未修改生产/共享数据。私密凭据和邀请证明只保留忽略的0600文件，不进入截图或提交。完整交互OIDC/SSO仍留P507，不把注入真实PKCE令牌等同交互登录。

SKILL_HANDOFF: slice=P5-06, status=COMPLETED, gate=PASS_WITH_ASSUMPTIONS, next=P5-07。
