# P5 CI Result

最终核对：全部PASS。包括商城纯文档交付状态提交0f7bd6b触发的额外完整CI。

| 仓库 / 精确产品SHA | 必需流水线 | 结果 |
|---|---|---|
| auth / e26b256e468578eef4a7dd86d21304b20c242cbe | [Auth Platform CI 36596325002](https://github.com/lirji/auth-platform/actions/runs/36596325002) | PASS |
| commerce / c7384fe487d875e1f90254be1283ebe2adf0098c | [Verify commerce platform 36596109216](https://github.com/lirji/commerce-platform/actions/runs/36596109216) | PASS |
| commerce最终main / 0f7bd6b3aee3c740037e00aef831528e0938403e | [Verify commerce platform 36597020947](https://github.com/lirji/commerce-platform/actions/runs/36597020947) | PASS：纯文档提交后完整回归 |
| OA / 6385a869e234b635c44e5b96f75e48133b3a1390 | [ci 36596514330](https://github.com/lirji/oa-platform/actions/runs/36596514330) | PASS：PC Console、Mobile H5、Backend |

本轮OA初次[36596118857](https://github.com/lirji/oa-platform/actions/runs/36596118857)失败于queryKey命名元测试：实际已带权限版本，但局部名permissionVersion不匹配约定扫描。修为prop解构别名permVersion，未加豁免，125前端测试/构建重跑通过，提交6385a86后最终全CI通过。历史失败保留。

CI策略取各仓现有workflow，未新增发布/部署门槛；auth增加P5脚本语法和4项上下文测试入口。本地三仓真实P5跨进程/浏览器证据另见各TEST_RESULT，不将CI脚本语法检查冒充整条跨仓浏览器执行。未授权生产部署，deployment=N/A。

最后的auth纯文档记录提交保持产品/测试/流水线树与上述已验证版本一致；此表绑定产品SHA，不声称文档SHA执行了另一次流水线。

SKILL_HANDOFF: skill=ci-cd-gate, status=COMPLETED, gate=PASS；精确SHA/运行ID见表。无剩余required检查，无生产部署动作。
