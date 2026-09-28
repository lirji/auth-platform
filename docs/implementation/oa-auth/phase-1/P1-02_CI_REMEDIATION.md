# P1-02 CI 隔离环境整改

两次远程失败均保留：

- 434cf977：[run 36386785526](https://github.com/lirji/auth-platform/actions/runs/36386785526)，单测/PG 成功，IdP readiness 失败，真实 IdP IT 未运行。Linux runner 的 0600 配置 owner=1001，镜像原生 UID=1000 无法读取；本机 Docker Desktop 所有者映射掩盖了差异。
- c97739a：[run 36387590196](https://github.com/lirji/auth-platform/actions/runs/36387590196)，同样在 readiness 失败。显式改为 runner UID 后能读配置，但不能写镜像 UID 1000 所有的 `/logs`。本机独立命名空间以 UID 1001 复现 `logs/casdoor.log: permission denied`，容器退出 2。

最终修复保持 Casdoor 原生 UID 1000。配置通过固定镜像的临时 helper 复制到带任务标签的专用 Docker volume，目录 0700、文件 0600、owner 1000；Casdoor 只读挂载 `/conf`。helper 使用 root，但无网络、只读根文件系统，只能写本任务配置卷，不运行 IdP。宿主私密文件权限保持不变。已有文件必须内容一致，非本任务卷拒绝使用。

独立库与容器在回环 18093 实际启动 PASS，discovery issuer 和认证后的版本 API 均确认 v4.11.0；容器内 `/conf`=700、`app.conf`=600、`/logs`=755，全部 owner 1000。固定多架构 OCI 摘要未改变。默认仍为 18090，18093 仅用于第二个隔离验证命名空间。readiness 失败仅输出状态/退出码和错误类别，不回显可能含 DSN 的日志。

证据保存在忽略目录 `.local/governance/p1-02/ci-uid-repro-container.log`（0600）、`ci-native-user-fixed.log` 与 `.local/governance-ci-runtime/casdoor-isolated/runtime-result.json`。失败容器保留为后缀 `-uid1001-failure`，没有删除现场或改共享组件。重跑与 Python 编译 PASS，限定 runtime 文件的 Hygiene 扫描见 `ci-native-user-hygiene.json`。

本次仅交付 runtime 整改和相应证据，不夹带未验收的 P1-03。新的精确 SHA 远程 CI 成功前，合并门禁继续 HOLD。独立的错误 redirect_uri 升级缺陷仍为 HOLD。
