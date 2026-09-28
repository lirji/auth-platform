# P1-02 CI 隔离环境整改

首个提交 434cf97739ac580400082821ac192e464893f396，远程 [run 36386785526](https://github.com/lirji/auth-platform/actions/runs/36386785526) FAIL：全 reactor 单测/PG 成功，固定 IdP readiness 失败，真实 IdP IT 未运行。没有把该 run 写为通过，main 尚未合并该提交。

镜像是多架构 OCI index：本机 arm64，CI amd64；固定摘要没有改变。镜像默认 UID 1000，Linux runner 的私密 0600 文件属于另一 UID。本机 Docker Desktop 把挂载所有者映射成 1000，掩盖了 Linux UID 差异。

隔离无网络/无凭据的 Linux 容器实测：0600 文件 owner=1001 时 UID 1000 `test -r` 退出 1，UID 1001 退出 0。修复仅在创建自己的隔离 Casdoor 时显式使用配置文件生成者的 UID/GID；不将文件 chmod 成公共可读，不采用容器 root，不更改共享组件或现有任务容器。readiness 失败仅输出容器状态/退出码，不输出可能含 DSN 的日志。

本机隔离实例重跑与 Python 编译 PASS；API/Token 产品源码不变，P1-03 未提交改动不夹带此整改。修复后的远程精确 SHA CI 待观察，成功前 Delivery Gate HOLD。保留首次失败日志，不修改既有失败记录。此项 CI 故障与独立的 redirect_uri 升级缺陷分别治理。
