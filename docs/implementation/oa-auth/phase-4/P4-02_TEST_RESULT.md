# P4-02 TEST_RESULT

status=COMPLETED；gate=PASS；slice=P4-02（OA持久实例幂等启动）。

- auth定向verify通过，RequestPostgresIT当前8项0失败：原6项加启动响应未知先查询、查询失败不盲目创建与5次耗尽。真实PG保存租约、attempt和绑定；原文HMAC协议2项单测，出站目标限制单测通过。
- OA真实PG3项、专用签名Filter3项和相关模块/架构单测通过。完整jar真实跨进程HTTP5检查通过（evidence/start-http.json）：三路启动同实例、查回同结果、改体409、nonce重放409、签名伪造401、跨分区403。
- auth单条投递CLI、30秒租约、单HTTP5秒预算、查询后创建、5次指数退避，耗尽崩溃也收敛DEAD；绑定与状态审计同事务。SQL与HTTP分离。OA映射/实例/原有工作流Outbox原子提交。
- Code hygiene通过，有既有无格式化器限制，未配置静态分析；事务人工复核及git diff --check通过。
- 首轮协议消费者本地仓不同、Controller序列化触发架构规则、安全链Bean重名、隔离Redis连接不足均已修复，失败记录保留。共享workflow可信producer没有OA是后续真实审批环境要求，未改共享配置。

本片只确认OA实例已持久化，APPROVED/Grant/引擎处理和真实故障恢复是P4-03至07，不能宣称阶段完成。
