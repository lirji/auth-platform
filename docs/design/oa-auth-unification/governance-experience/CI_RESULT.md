# GUX CI结果

2026-09-30，skill：ci-cd-gate。**gate=PASS**，绑定最终产品提交 `aa23e3b85671f05716d8f72f5544f122f28ca385`。

| 检查 | 结果 | 证据 |
|---|---|---|
| Auth Platform CI / verify | PASS | [36724101530](https://github.com/lirji/auth-platform/actions/runs/36724101530)，workflow及job均completed/success，耗时6m16s |
| 主改版提交4954b54的前一轮 | PASS | [36723258438](https://github.com/lirji/auth-platform/actions/runs/36723258438) |
| 本地构建、上下文单测、浏览器 | PASS_WITH_LIMITATIONS | [TEST_RESULT](TEST_RESULT.md) |
| Project Portal CI | N/A | 本次没有命中该workflow的产品/部署路径 |
| 生产发布、外部奖项评价 | UNVERIFIED / 未执行 | 不在本次CI门禁授权范围 |

完整远程日志已保存到忽略路径 `.local/governance-experience/ci-36724101530.log`，确认包含6次Maven BUILD SUCCESS、前端6测试摘要和Vite构建完成。GitHub步骤级API一度返回滞后的pending状态，因此同时核对了最终workflow/job结论与完整日志，未只凭分支名推断结果。

非阻断annotation：现有Actions的Node20目标与setup-java v4弃用提示、ubuntu-latest将迁移提示、runner临时Docker网络清理告警。未为消除提示扩大为全仓CI依赖升级；job最终成功。本地容器未被该远程runner清理操作影响。

后续仅允许纯交付文档收尾，不能把本结果归给任何未重新验证的产品修改。Git推送与文档收尾见 [DELIVERY_RESULT](DELIVERY_RESULT.md)；无生产部署授权扩张。
