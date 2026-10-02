# 完整权限与控制台交付

业务实现和本地验收完成。Auth 权限控制台已取消抽屉并采用居中弹层，覆盖窄屏、关闭保护、提交状态与焦点返回。商城已补齐订单、支付、履约、售后、退款、会员经营、营销旅程、页面及运行治理的能力和范围校验；接入项目资源与已发布业务菜单可供运营选择。

真实隔离 test 分区完成 122 能力、21 资源类型、42 菜单节点及 34 固定同资源角色快照（17 岗位），模板合并覆盖 99 能力；其余 23 能力保留显式手工职责审查。没有自动创建业务 Grant 或 Policy。目录、角色、PKCE、普通用户 403、SQL、14 张最终视觉图和进程退出均通过独立核验。

验证证据：组合 Maven 546（541 PASS / 5 既有条件 skip）、004 修补受影响 58 / 5 套件全 PASS；当前制品 17 内部模块 / 821 编译文件、61 当前前端资产、protocol104 / SDK12 逐字节核验。Auth 254 单元、既有实际 128 持久与执行授权集成证据，以及出版工具 23 回归保持 PASS。远程 Commerce 新全库 553（548 PASS / 5 既有条件 skip）按精确 run 归档，真实浏览器结果由本次最终成功 run 补齐。

旧 Commerce 浏览器售后验收失败保留；修正仅在点击提交前监听实际 POST 成功回执，核对原订单和售后编号，再从原管理员列表验证该编号的审批、入库与退款完成。没有修改权限、业务源码或超时标准，没有用旧通过覆盖失败。

Git：所有本任务独立实现提交已正常合并、推送 main。正常分支推送和快进 main，未强推或重写历史。文档收尾提交不改变已验证产品源码；其精确 CI 终态存入私密当前交付回执，避免为引用自身提交而无限追加状态提交。Auth 文档路径沿用原 workflow 的过滤规则，不改规则或跳过检查。

五个本任务工作树实现提交均为 main 祖先，跟踪/未跟踪文件 clean；忽略的原证据、数据库配置、制品及私密 Maven 仓保留。历史工作树、数据库、卷和失败证据未清理，未生产部署。最新精确远程状态见 GitHub run 链接和 Auth `.local/governance/commerce-contracts/parallel-complete-permissions-git-delivery-current.json`。

业务交付精确 CI：

- [Auth 3958f14 / 37044087418](https://github.com/lirji/auth-platform/actions/runs/37044087418)：completed/SUCCESS，28 步全部通过。
- [Commerce 8237200 / 37045250996](https://github.com/lirji/commerce-platform/actions/runs/37045250996)：completed/SUCCESS，17 步全部通过。
- [旧 Commerce eef0a3c / 37044089855](https://github.com/lirji/commerce-platform/actions/runs/37044089855)：FAILURE 原样保留；实际全库 553 / 浏览器 40 PASS、1 FAIL、20 既有条件 skip。新成功轮次独立归档，不覆盖旧失败。

独立终态索引：Auth `.local/governance/commerce-contracts/parallel-publication-final-independent-review.json` SHA `4a6a5ee2b1d0939baefad4113b88ba31bb6f20f50bcdaee128f30fc66028c574`；出版 Git 绑定 SHA `3c11e730fa3321440e4d32918fa8d12069fd8a4ad81e6e00e753f2cc381fb2b7`；当前 Source/004/制品与 worktree 回执均在同私密目录。真实出版完整不可变索引位于已有 Auth `integrated-resource-selector` 工作树 `.local/commerce-catalog-publication/runtime-b8d81bb347e3/immutable-evidence-index-delivery-v2.json`，SHA `93cd32a9ddce367de84be243f8818a657a044bff7de3f34c8e3296ed735f43ea`。

修正后远程实际统计：Commerce8237200 / CI37045250996，74 个 XML 套件共553项=548 PASS/5既有条件skip/0失败错误；真实浏览器41 PASS/20既有条件skip/0失败、0 flaky，会员下单→售后退款及权益冲正用例实际通过。独立新CI result SHA `3a78e2c42fbc27bae3b0d207819d9f6137dab128d248f9b0e6ca3af1bcadc854`，不可变 manifest SHA `73fe6c8b4a1ff1aec767b379d85211d5bee20ff603ac79a025b272ff27fe39db`，存于 Commerce 既有 `central-order-operations-permissions` 工作树 `.local/order-operations/root-commerce-ci-corrected-sequence-review-v1/`。旧 FAIL 另档保留。
