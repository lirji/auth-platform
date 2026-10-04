# WMS Auth Integration Progress

## 任务目标

电商运行核查后，连续完成 WMS 主数据/库存只读、入出库、盘点调整与调拨的 Auth 集中权限接入、必要验证与正常 Git 交付。生产部署不在范围。

## 当前状态

W00–W02 本地验证 DONE，实现已提交并推送任务分支；W03 BLOCKED，Q-ORG 已发出尚未答复。全任务未完成。最终精确 CI 与主线发布结果以本机交付回执为准：`/Users/liruijun/personal/LLM/auth-platform/.local/wms-auth-integration/delivery-result.json`。回执缺失或必需检查未通过时不得视为已合入主线。

## 已完成

- 真实 Docker 核查：8602 镜像 rev-2851fc1，未配置 COMMERCE_IAM 开关，5273 治理管理分区不控制该电商运行实例。
- Auth 独立任务分支 feat/wms-central-authorization，基线 b564f8e；WMS 基于已交付 origin/main 9522118 创建同名任务分支。
- WMS 新工作树：/Users/liruijun/.local/share/git-worktrees/wms-platform/central-authorization。原目录 Driver/pom/文档等改动不编辑、不暂存。
- W00–W02 技术未知、架构、影响、资源/目录契约与完整后续切片已记录。

- W01 已验证：206 reactor单元、13 SDK HTTP、2真实PG/图IT通过，见 [W01_TEST_RESULT](W01_TEST_RESULT.md)。
- W02 已验证：94接口/43源scope → 49能力/16菜单，9个Python工具测试与Auth生产解析器通过，见WMS docs/iam/W02_TEST_RESULT.md。

## 未完成与阻塞

- W01 实现提交 b2d2414，W02 实现提交 411d3db；任务分支均为 feat/wms-central-authorization。交付回执单独记录最终含文档的 SHA、精确 CI 和远端 main，不能将本地测试替代完整流水线。
- Q-ORG：ENT-DEMO 应归独立 local-wms，还是复用 local-commerce？未获得答复不能创建主体映射、复制成员或授予权限。
- W03–W07 的身份/运行/消费契约、业务实现、真实验收尚未完成；不得以目录注册或健康检查声称接入成功。

## 下一步

恢复时先读取交付回执并核对当前 Git/CI；用户答复后从 W03 继续。不要重做 W00、旧 MG 计划、S9/Driver 或 ERP 暂停任务。
