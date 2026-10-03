# 本地治理依赖 Compose 分组迁移

任务 `GOVERNANCE-COMPOSE-GROUP`，协议 `engineering-baseline/v1` / `skill-contract/v1`。目标为本机 Docker Desktop 的 `auth-platform` 组；用户已授权将两项现用认证和授权依赖纳入该组。商城测试数据库保持原状。

## 证据、拓扑与环境

迁移前 Docker inspect 确认：`auth-gov-casdoor-p1-f56b6f94d6bc` 和 `auth-governance-p3-graph` 正在运行但没有 Compose 标签。前者使用只读配置卷、dev-infra 网络和 18090 端口；后者使用 bridge 网络、持久化 PostgreSQL、18544 端口及读池1/4、写池1/2。应用的私密配置和 nginx 仍依赖这两个端口。

DeploymentTopology（`deployment-topology/v1`）：三个治理应用依赖 Casdoor 和 SpiceDB；两项依赖从 EXTERNAL 转为 MANAGED_BY_DEPLOYMENT。已有共享 PostgreSQL 继续 EXTERNAL；旧 8000 Casdoor、8543 SpiceDB、项目门户与商城不在变更集内。

EnvironmentProfile（`environment-profile/v1`，`local-default`）：保留原 PostgreSQL 数据源、配置卷、凭据、原镜像 digest、网络、回环端口、资源限制和连接池；不迁移身份、关系或业务数据。ResolvedDeployment（`resolved-deployment/v1`）：新增 `governance-casdoor` / `governance-graph`，固定容器名 `auth-governance-casdoor` / `auth-governance-graph`，项目名 `auth-platform`。私密实际解析结果保存在原项目 `.local/governance-compose-group/`，没有把连接串或密钥写入 Git。

## 变更集与回退

- CREATE `deploy/governance/dependencies.compose.yml`：按 inspect 证据定义两项依赖，引用原 external 卷和网络。
- CREATE `deploy/governance/prepare-dependencies.py`：只读导出现有配置及原始容器快照，不轮换凭据，不覆盖不同配置。
- CREATE `deploy/governance/wait-dependencies.py`：应用启动前实际读取登录发现文档和原图 schema，最长等待45秒，不修改数据。
- PATCH `deploy/governance/run.sh`：存在私密接管配置时显式加载依赖，未接管环境保留外部依赖模式。
- PATCH `deploy/governance/README.md`：说明依赖管理、首次切换和停机/回退范围。

风险为端口互斥和认证短暂中断。先备份原配置、关联数据库，再停止/重命名两个旧容器，创建同数据源的新容器。旧容器保持停机并关闭自动重启，避免 Docker 重启后抢占端口。验证失败时停止新实例、恢复旧实例原名与 unless-stopped 策略并启动；不自动恢复数据库、不删除卷。

## 验证与结果

当前状态：DONE / PASS。两项依赖已归入 `auth-platform`；原始容器以 `-rollback-20261002` 后缀停机保留并设 restart=no，防止自动启动抢占端口。

- L1：已接管/未接管两种模式的 Compose config、Shell/Python 语法、diff check 全部 PASS。
- L2：N/A；没有镜像源码变化，复用原镜像不可变 digest，不重新构建。
- L3：新容器 project/service 标签、镜像ID、环境、命令、入口、资源、网络、端口和卷与原实例核对 PASS。重复 prepare 与 Compose up 均通过，第二次 up 容器ID不变。
- L4：真实授权码+PKCE登录 PASS；管理、角色/授权、本人应用/组织、审计读取200，匿名401；POLICY/DIRECTORY均 READY；授权图 schema 前后一致。角色、授权及本人应用/组织响应前后一致；原审计条目全部保留，仅新增读取审计。应用容器未重建，投影进程持续 healthy。
- 备份：迁移前原只读配置卷、认证库/图库/治理库三份非空 PostgreSQL custom-format备份及摘要已保存。未删除数据库、卷或原始容器。

投影状态从真实 `/access/management` 接口验证；审计查询本身会新增 READ_ACCESS_AUDIT，因此按原条目不可变、仅增加正常读取审计验证。早期检查脚本的断言/元数据读取失败单独记录在私密 validation-issues.json，修正后定向复核通过，没有修改产品行为或执行业务授权写入。

Git交付结果记录在私密 `delivery-result.json`，避免为文档引用自身提交而重复提交。本次部署配置路径会触发 Project Portal CI（含部署配置检查与治理镜像构建），Auth Platform CI 的路径过滤不触发；远程终态以精确提交查询为准，本地实际运行与认证验证已通过。

进度：原项目及任务工作树 `CODEX_PROGRESS.md`；私密证据：原项目 `.local/governance-compose-group/`。工作树用于保护原目录同时执行的部署收尾，路径 `~/.local/share/git-worktrees/auth-platform/governance-compose-group`，分支 `fix/governance-compose-group`。不自动清理停机容器或工作树。
