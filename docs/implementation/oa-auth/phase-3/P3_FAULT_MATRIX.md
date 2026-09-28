# P3 故障验收清单（非完成报告）

| 场景 | 当前证据 | 后续必需证据 |
|---|---|---|
| read全范围/refund单店交叉放大 | ScopeRulesTest PASS | 真实资源Owner查询/写入 |
| 旧marker晚到写 | ProjectionCasIT PASS | 两个真实Worker进程暂停、撤销、恢复 |
| 图成功而SQL无receipt | readAt恢复端口PASS | 写图成功后kill，重启补receipt |
| bulk缺项/错关联/Conditional | 待P3-05先写协议失败用例 | 真实HTTP替换响应，不允许部分ALLOW |
| 双实例SQL/图水位 | 主库新快照PASS | 两进程交替读写、撤权之后新请求 |
| Count/search/export/download | 未执行 | P3-02真实MySQL及用户Token |
| 组变化/到期/角色扩展/能力紧急停用 | 未执行 | P3-06真实权威变化 |
| 延迟/吞吐基线 | 未执行 | P3-07记录真实范围和数字，不虚构SLO |
