# IR-01 Progress

任务目标：完整CE00—CE08与Auth实际接入菜单资源选择；本片负责有界实际目录与角色/Grant/Policy选择。

当前完整IR状态 DONE（产品与有界CI修复已main交付，精确CI37026620293 SUCCESS）：新增published-catalog读用例、三表单真实有限选项与两ScopeFields消费者已完成；TEST_RESULT.md=PASS，源SHA256见IMPLEMENTATION_EVIDENCE.json。独立分支feat/integrated-resource-selector，原D2工作树/target不变。

已完成：188单元、51真实PG、当前图ALLOW→disable DENY→restore ALLOW、前端14测试/类型构建、专有PG/HTTP/三HUMAN PKCE/SQL与13张1440/390/320视觉。旧createRole/grantScoped语义保留；未知结果原命令冻结与重放通过。

未完成（完整任务后续，非本片阻塞）：正式Commerce 21导航Owner准确映射/真实publish；原实施已由主Agent正常集成/推送main；原精确CI37023342834已COMPLETED/FAILURE；新测试配置兼容修复本地验证通过，新的精确远程CI待主Agent。本片测试菜单不能替代正式发布，见OWNER_MENU_PUBLICATION.md。

当前限制：仓库canonical formatter不可用；UI测试Grant受理PENDING，不宣称其已实际投影。真实图IT另证有效授权停用与恢复。失败历史和私密验证证据保留，没有清理共享数据。

本地完整实施提交已创建：a09915f；Delivery见DELIVERY_RESULT.json。原实施已由主Agent集成/推送（14870ed）。验证用21662/21665仅自有进程已结束；私有库/IdP测试身份与.local证据保留供验收。

同任务有界CI注册补齐 DONE/LOCAL_VALIDATION_PASS：新harness触发/语法及Node14已加入既有authz-ci；精确Node14、12Node语法、Python编译、YAML结构差异与20原产品SHA全部PASS，见TEST_RESULT末节。本次逻辑提交仅workflow+三份本片文档；不push/merge，主Agent负责精确新CI与最终集成。

同任务CI测试配置修复 DONE/LOCAL_VALIDATION_PASS：原远程37023342834因新PG open只读取CONFIG，而第一governance-it仅提供三个既有DB env失败；保留失败日志，未把失败改成PASS。新测试沿用Directory/Portal双入口且保留原loopback独立测试库URL安全约束。CONFIG显式unset完整真实PG108/0fail/0error/0skip（含新7与既有51路径），0600CONFIG且三个DB env unset精确新7/0fail/0error/0skip。仅测试setup和本片证据/验证/进度/交付文档修改；19其他原IR源码SHA不变。两个本地followup交主Agent，不自行push/merge，远程CI终态仍须精确核对。

本轮计数独立修正：CI-style按实际日志9个persistence Running与failsafe-summary.completed核对为108，先前109误含历史ReliableAuthorizationIT1已撤回；显式CONFIG仅本轮新7，保留目录中的其他旧XML不计入。精确选取与排除SHA已私密记录，无产品/测试改动或重新运行。

IR远程门禁已关闭：独立`gh run view37026620293`核对headSha=b1edecc61ceef29f82a280259d2679b0d662f378、completed/success、updatedAt2026-10-02T15:30:06Z。root已正常集成/推送03054+6f750+b1edecc；原14870/37023342834 FAILURE和108/7当前报告计数保留。下方/上方旧“待新CI”句子为本轮完成前的历史检查点。完整Commerce publication另为独立后续切片，不借此CI宣布122/当前实际菜单已发布。
