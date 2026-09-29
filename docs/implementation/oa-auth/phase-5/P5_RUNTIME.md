# P5 运行与恢复

适用已交付的统一入口、内部商品经营和外部门店协作。正式共享Casdoor8000仍保持既有升级HOLD；本次仅在已验证的隔离18090运行，不是生产部署授权。

## 入口和配置

- auth-console复用现有壳：`/governance`、组织应用角色/授权、邀请、申请通知、权限来源/诊断。`VITE_CASDOOR_AUTHORITY`直连可信issuer；`VITE_CASDOOR_CLIENT_ID`使用独立管理客户端。后端治理、presentation、access、scope、requests、invitations按已有显式开关及私有配置启用。
- commerce固定入口 `/operations/products`、`/collaboration/products`、`/iam/callback`。构建时设置 `VITE_IAM_ENABLED=true`、`VITE_IAM_AUTHORITY`、独立`VITE_IAM_CLIENT_ID`、`VITE_IAM_PORTAL_URL`、`VITE_IAM_ENVIRONMENT`。前端配置只有公开地址/客户端ID，禁止secret。工作台链接仅带`tenant_id/environment`，服务端再次校验，不将URL当授权。
- commerce运行时同时启用`commerce.iam.store-read.enabled`和`commerce.iam.scope.enabled`，使用权限0600的`commerce.iam.store-read.configuration`。数据库/平台凭据沿用私有环境文件。旧登录后台不会成为中央入口的回退。
- OA使用自身独立OIDC客户端、`VITE_AUTH_ENABLED=true`，API JWT audience必须匹配OA客户端。`oa.security.allowed-origins`仅登记准确前端Origin；不使用通配Credential CORS。OA审批页面复用现有待办，不授予外部申请人员工权限。
- 每个客户端精确登记自己的`<origin>/callback`或商城`<origin>/iam/callback`；浏览器授权码+PKCE，无client_secret。SSO客户端启用已有登录会话，后续应用可能要求点击已有账户确认，但不会转发别的应用Token。业务API显式Bearer，不能仅凭Cookie访问。

## 本地复现

先按P4_RUNTIME/P5-06报告建立本任务隔离数据库、图、IdP和审批夹具。P5-06脚本生成的成员和read grant有有效期，过期后需新跑该夹具，不能改历史数据延长既有批准时间。

```sh
python3 deploy/governance-p5-login.py --fixture <本轮.local/governance/p4/e2e目录> --commerce <商城仓库> --oa <OA仓库>
# 输出本轮P5_LOGIN_RUN后，在另一终端运行：
P5_LOGIN_RUN=<上述目录> P5_PLAYWRIGHT_MODULE=<商城frontend/node_modules/@playwright/test> node deploy/governance-p5-login.mjs
python3 deploy/governance-p5-packaged.py --login-run <上述目录> --fixture <同一P5-06目录> --commerce <商城仓库>
P5_LOGIN_RUN=<上述目录> P5_PLAYWRIGHT_MODULE=<商城frontend/node_modules/@playwright/test> node deploy/governance-p5-packaged.mjs
```

运行器新建独有P5客户端，仅操作本任务IdP；复用隔离业务夹具，不修改共享客户端。要求本机15275/15276/18420/18421/18422/18603/18605/18606空闲。浏览器工具不注入Token、不Mock接口，首次实际输入私有夹具用户密码。`login.py`上限40分钟，packaged上限20分钟；创建运行目录的`stop`/`packaged-stop`文件可结束，finally只终止本工具拥有进程。同一运行器的一次性故障信号不重复使用；重跑应生成新目录，保留失败证据。

商城同源打包：`npm --prefix frontend run build`后`mvn -pl commerce-app -am package -Pwith-ui -DskipTests`。上述packaged脚本使用相同命令、复制不可变JAR并监听回环18606；前端资产和资源API由同一JAR提供，已实测深链刷新和回调。Vite仅开发代理，不能作为部署证据。

## 停止和恢复边界

- 关闭商城双试点开关并重启可停止中央业务入口；已验证旧中央Token返回401。开启需恢复原私有客户端、service credential、scope开关与映射，不能回退为read即可export。
- 撤销申请来源要等待真实回收回执；已有独立read来源保留。旧任务/已生成文件下载每次按product.export重新判权。到期后不能恢复旧导出；新授权须新任务。
- 商品写保留Store/Merchant活动状态、版本和范围条件；提交前决策最长5秒在途窗口，不宣称跨数据库原子撤权。代码回退不撤销已提交商品修改或已下载文件。
- auth依赖故障fail closed为503；错误页清空数据且允许人工刷新。未知写结果保留原幂等命令重试，不自动构造新写。到期登录仅清理当前应用会话，其他应用SSO行为由各自客户端决定。
- 本轮新增V17为诊断审计的扩展迁移；旧P503 JAR与V17共存已验证。P6目录接管、影子比较和存量单写切换不在P5中执行。
- `.local`含测试密钥/Token/私有日志，不能提交。独有客户端、测试数据库/卷保留便于复查；本次不执行数据清理。
