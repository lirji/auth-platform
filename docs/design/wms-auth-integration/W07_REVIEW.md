# W07 实施审查

范围：当前源码镜像、中央Compose叠加、可信TLS/独立凭据、原本机目标、机器兼容与交付工具。WMS/Auth业务代码、既有迁移未在本片修改。

| 场景 | 源码与实际证据 | 判定 |
|---|---|---|
| 越企业/越仓或把企业权限当仓权限 | 固定local-wms→ENT-DEMO；中央overlay和服务配置；真实A/B拒绝；实际管理页仓范围没有TENANT_ALL | PASS |
| TLS被本机便利配置绕过 | PKCS12 SAN/CA、SDK HTTPS、五truststore；未知CA/错误主机名失败；没有skipTLS或允许远端HTTP | PASS |
| 私密文件与公开交付 | 分服务卷、UID10001/600，CA私钥仅宿主；relay server级覆盖基础combined日志，只记路径/状态/耗时；Git排除私密目录 | PASS |
| 同一令牌因旧菜单或多个来源继续误允许 | 管理UI只授/撤自己来源，连续projector SQL确认；同授予前Token A200→403，B始终403，8演示来源保留 | PASS |
| 机器获得公开人类权限或错误Owner | 旧8000实际发行3主体/最小scope；Owner HTTP/SQL23项、公开401、越仓403、旧序列镜像同库幂等重放 | PASS |
| 健康检查掩盖真实依赖失效 | 当前TLS暂停503/恢复、实际PKCE浏览器；原503与projector失败保留，同一Graph本体绑定及原客户端回环已验证；不缓存允许或放宽2秒身份期限 | PASS，范围限本机 |
| 原数据与其他项目被覆盖 | 两MySQL镜像/业务卷相同；142表摘要一致；初始化挂载同字节；原dirty WMS/local main受保护，不操作Driver/ERP | PASS |
| 将镜像回退当业务恢复 | 旧镜像/配置/dump保留，旧序列真实兼容验证；未执行全目标回滚/快照恢复 | 证据范围已明确 |

运行时观察到共享Docker VM内存回收停顿，原目标首轮页面超时；只在本任务私密env将五WMS堆预算设为Xms64m/Xmx256m并重验，不调整其他组件或权限期限。启动原消息开关前先恢复既有Kafka/Redis。本机验证不构成容量/可用性/RTO承诺。

机器JWT一小时和演示Grant不超过24小时，受控续发/重新授予仍需运行手册操作；没有自动续发。TCC只证明发行/认证链，原RM和执行开关关闭。上述边界不是完成TCC/生产部署或灾备证明。验收与Git Gate以 [W07_TEST_RESULT](W07_TEST_RESULT.md) 和实际交付回执为准。
