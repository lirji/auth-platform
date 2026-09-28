# P2-05 中央检查公开协议（v1）

新接口与旧 `/v1/*` 九操作并存；不放宽旧SDK行为。

- POST `/internal/governance/v1/access/check`：服务Authorization Bearer + X-User-Access-Token。先验证固定服务/app/env绑定与 `access.check.callers` 显式允许名单，再验证用户Token的固定发行方/受众并读取本人有效成员。
- JSON为 `{tenant_id,expected_membership_generation,request_id,capability,resource_type}`；不接受principal/app/environment。request_id为调用关联UUID，不是可重用授权票据。
- 响应 `{schema_version:"1",request_id,capability,resource_type,decision:"ALLOW"|"DENY",scope:"TENANT_ALL"|null,context:{P1 AccessContext},decision_id}`。故障返回503稳定错误，不返回ALLOW。未知范围和未登记能力DENY。
- POST `/internal/governance/v1/access/check-bulk` body `{checks:[同上]}`；1～20项，request_id唯一，所有项属于同一tenant/generation。逐项完整返回；任一依赖故障整个批次503，不能以缺项或部分成功暗中放行。
- SDK需要明确expected app/environment、中央URL、服务凭据和超时；只从后端保管服务凭据。对响应严格验证版本、每项ID/能力/资源类型、tenant/app/env、HUMAN主体、有效UUID与正版本/代际、ALLOW对应TENANT_ALL；缺项/重复/未知/错位/非布尔语义均异常，未知枚举不当DENY吞掉。
- 明确DENY为AccessDeniedException；HTTP/协议/超时为CentralAccessException，业务边界映射503。认证401单独保留，不能降级使用旧权限。
- Boot3/4 SDK独立Jackson2编码与解析，不依赖宿主全局ObjectMapper；Java HTTP连接和总请求时限有界。不引新库、不引gRPC、不做后台重试、不缓存ALLOW。
