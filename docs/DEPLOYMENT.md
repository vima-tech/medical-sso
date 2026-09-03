# 生产部署与安全清单

生产用 `deploy/compose.prod.yml`，不要用 `deploy/compose.yml`——后者是本机开发环境。两者都是四个容器（PostgreSQL、Keycloak、网关、统一身份管理平台），差别在于生产走 HTTPS、强制配置密码、并使用单独生成的 Realm。完整发布步骤见 [Release Package](RELEASE.md)。

## 推荐拓扑

```text
内网浏览器
    |
HTTPS 统一域名（唯一对外入口）
    |
网关容器（反向代理 / 负载均衡）
    |-- /auth/*  --> Keycloak 认证内核容器 1-2 节点
    |-- /        --> 统一身份管理平台容器
                          |
                     生产 PostgreSQL
```

认证内核不对外暴露端口，只能经反向代理的 `/auth` 前缀访问；其自带管理控制台已在引擎层面关闭，生产同样不得开启。

**只有网关映射宿主端口**，另外三个容器都不对外可达。

部署机**只需要容器运行时**，不需要装 JDK 和 Maven：平台镜像按 `deploy/Containerfile` 多阶段构建，Maven 只存在于构建阶段，不进运行镜像。本机和生产用的是同一个 Containerfile。仓库里的四个演示子系统不参与生产部署，也不进镜像。

### 本机与生产在网络上有一处不同

生产环境（`deploy/compose.prod.yml`）里平台是一个普通服务，网关按容器名 `portal:8080` 访问它——这是标准做法，因为生产的对外地址是真实域名，平台容器能通过内网 DNS 解析到它，`iss` 校验自然通过。

本机（`deploy/compose.yml`）做不到这一点：对外地址是 `http://localhost:18081`，而容器里的 `localhost` 指向容器自己。令牌里的 `iss` 是对外地址，平台做 OIDC discovery 必须用同一个字面地址去请求，否则校验通不过。所以本机让平台与网关共享网络命名空间（`network_mode: service:gateway`），这样容器内的 `localhost:18081` 就是网关，网关也用 `127.0.0.1:18082` 回连平台。

这个差异只影响本机，不要把 `network_mode: service:gateway` 搬进生产编排。

首期用户规模较小时可以单 Keycloak 节点，但数据库必须备份。认证中断会影响所有接入系统，新系统正式推广前建议升级为双节点。

## 生产配置：没有「可以先不填」的项

生产用的是 `deploy/.env.production`（模板 `deploy/.env.production.example`），**不是**本机那个 `deploy/.env`，变量名也不完全相同——本机用 `SSO_PUBLIC_URL`（含协议和端口），生产用 `SSO_HOSTNAME`（只填域名，例如 `sso.intra.example`，协议固定 HTTPS）。

`deploy/compose.prod.yml` 对每一项都写了 `${VAR:?}`，缺一项就直接启动失败并指名是哪一项。这是刻意的：生产环境不存在「先用默认值跑起来再说」。需要填的是：

| 配置项 | 填什么 |
| --- | --- |
| `SSO_HOSTNAME` | 对外域名，只填主机名。认证内核签发的 issuer 由它推导，必须与浏览器实际访问的地址一致 |
| `POSTGRES_PASSWORD` | 数据库密码 |
| `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD` | 认证内核引导管理员 |
| `SSO_PLATFORM_ADMIN_PASSWORD` | 平台管理员初始密码，首次登录强制改 |
| `SSO_ADMIN_CLIENT_SECRET` | 平台调用 Admin API 的服务账号 Secret |
| `SSO_PORTAL_CLIENT_SECRET` | 平台自身参与统一登录的客户端 Secret，必须与上一项不同 |
| `TLS_CERT_FILE` / `TLS_KEY_FILE` | 证书和私钥的绝对路径 |
| `REALM_IMPORT_DIR` | `render-production-realm.sh` 生成的 Realm 目录绝对路径 |

填完先跑 `./scripts/release/preflight-production.sh` 自检（默认读 `deploy/.env.production`），再按 [Release Package](RELEASE.md) 的步骤发布。

## 必须修改

- 使用固定内网域名，例如 `https://sso.intra.example`。
- 使用受信任的内网 CA 或合规证书，全链路 HTTPS。
- 将 `start-dev` 改为生产 `start`，并配置正确的 hostname 和 proxy headers。
- 替换管理员密码、数据库密码、所有 Client Secret。
- Secret 通过容器 Secret 或配置中心注入，不写入 Compose 和 Git。
- PostgreSQL 使用独立持久化存储并执行自动备份。
- Redirect URI 和 Web Origin 使用精确地址，禁止通配符。
- 所有客户端保持 `pkce.code.challenge.method=S256`，新建客户端同样要开启。
- 接口类子系统保持 `medical.sso.require-audience=true`；客户端默认 `fullScopeAllowed=true`，不校验 audience 时其他系统的 Token 可以调本系统接口。
- 保持 `--features-disabled=admin`，不要为了排查方便临时打开 Keycloak 管理控制台。
- 为管理平台单独配置服务账号 Secret（`SSO_ADMIN_CLIENT_SECRET`），不要复用平台登录用的 Secret。
- 复核服务账号权限：`manage-clients`、`view-clients`、`manage-users`、`view-users`、`view-realm`、`manage-realm`、`view-events`、`manage-events`。这是管理平台全部功能所需的最小集合，不要授予 `realm-admin`。
- 开启登录事件、管理员事件、健康检查和指标采集。

## 会话建议

当前演示 Realm 配置：Access Token 5 分钟、SSO 空闲 30 分钟、SSO 最长 8 小时。上线前根据医院安全制度确认。

高风险操作不能只依赖“用户已经登录”。处方、退费、权限分配等操作应在业务系统中增加二次确认或重新认证。

## Realm 导入的两个陷阱

**`--import-realm` 不会覆盖已存在的 Realm。** 数据卷里已经有 `medical` 时，Keycloak 直接跳过导入，改了 `medical-realm.json` 也不会生效，而且不报错。表现是「配置明明改了却没变化」。要让改动生效，只能清掉数据卷重新导入，或用 Admin API 删除该 Realm 后按 JSON 重建：

```bash
./scripts/sso.sh stop                            # 停止容器
docker volume rm medical-sso_keycloak-postgres   # 清掉数据卷，容器名前缀按实际项目名
./scripts/sso.sh start                           # 重新导入
```

**Realm JSON 里的 `clientScopes` 数组会替换内置 Scope 集合，不是追加。** 只写自定义 Scope 会让 `web-origins`、`acr`、`basic`、`email` 这些内置 Scope 在导入后消失；客户端如果仍在 `defaultClientScopes` 里引用它们，导入时会报找不到，运行时授权请求会因 `Invalid scopes` 被拒。本项目的做法是让客户端只引用 JSON 中实际定义的三个 Scope：`profile`、`roles`、`medical-profile`。新增 Scope 时两边要一起改。

## 子系统登记功能

门户的 `/admin` 区域用服务账号客户端 `medical-portal-admin` 调用 Keycloak Admin API。它仅持有管理平台功能所需的八项细粒度权限：`manage-clients`、`view-clients`、`manage-users`、`view-users`、`view-realm`、`manage-realm`、`view-events`、`manage-events`，不授予 `realm-admin`。

- 只授予上述八项权限，不要顺手加 `realm-admin`。
- Secret 通过密钥系统注入，与门户登录用的 `medical-portal` Secret 分开管理并分别轮换。
- `/admin` 已限定平台级角色 `sso-platform-admin`。该角色能创建 OAuth 客户端并取得 Client Secret，只授予统一认证平台负责人，不要与机构级 `organization-admin` 合并，并纳入管理员事件审计。
- 不需要自助登记时，用 `PORTAL_ADMIN_ENABLED=false` 关闭整个功能，此时门户不再需要服务账号 Secret。
- 登记页生成的 Client Secret 只展示一次，门户不落库、不写日志。

## 发布接入组件

子系统要在自己的 `pom.xml` 里依赖 `medical-sso-spring-boot-starter`（或 boot2 版），
所以这两个组件必须先发布到子系统能访问的 Maven 仓库（公司私服，或各自 `mvn install` 到本地仓库）。

**父 POM 要一起发布。** 只发 starter 模块的话，子系统解析依赖时会直接失败：

```
Could not find artifact com.medical.union:medical-sso:pom:0.1.0
```

因为 starter 的 POM 里 `<parent>` 指向聚合工程的 POM，仓库里没有它，整条依赖树就断了。
本地验证时在仓库根执行一次 `mvn -N install`（`-N` 只装父 POM 不构建子模块），
发布到私服时同理，父 POM 与两个 starter 一并 deploy。

## 演示数据与生产数据

`deploy/keycloak/realm/medical-realm.json` 是**生产就绪**的导入文件：

- 只有平台管理员 `sso-admin` 和平台自身的两个客户端
- 没有演示人员、演示业务系统，机构科室为空，由管理员在平台上自行建立
- `sso-admin` 带 `UPDATE_PASSWORD`，首次登录必须改密

演示数据由 `scripts/demo/seed-demo.sh` 单独装载，本机 `scripts/demo/start-local.sh` 会自动执行。**生产环境不要执行该脚本**；它对非 `localhost` 地址会直接拒绝，需要显式 `ALLOW_DEMO_SEED=yes` 才会继续。

这样安排的原因是失败方向：忘了执行播种脚本，得到的是一个干净的生产环境；反过来如果默认带演示数据、靠上线时手工删除，一旦漏删就是演示账号进生产。

## 审计与留存

Realm 已开启事件记录，随导入生效：

- 登录事件：登录成功、登录失败、退出、修改密码、令牌签发与失败、锁定，保留 30 天
- 管理事件：管理员在平台上的全部增删改，**不记录请求体**（`adminEventsDetailsEnabled=false`），避免把密码等内容写进审计库

管理平台的「审计记录」只读，管理员无法删改。保留期到后由 Keycloak 自行清理。

医院有更长留存要求时，把事件同步到内网日志平台，不要单纯调大 `eventsExpiration`——事件表随之膨胀会拖慢认证内核。

## 数据与隐私

Token 只承载完成认证和粗粒度授权所需字段。不要加入：身份证号、手机号、患者信息、诊疗信息、完整组织通讯录或其他非必要数据。

浏览器 Session Cookie 应启用 Secure、HttpOnly 和合适的 SameSite 策略。反向代理应设置安全响应头并限制管理入口。

## 备份恢复

核心数据位于 PostgreSQL。至少执行：

- 每日全量备份和按需增量备份。
- 定期恢复演练，而不只是检查备份文件存在。
- Realm 配置、主题代码和接入文档进入版本管理。
- Client Secret 由密钥系统备份，不写入 Realm 导出文件长期保存。

开发环境 Realm JSON 中包含演示 Secret 和演示密码，不得直接作为生产备份使用。

## 上线验收

- Keycloak 重启后 Realm、用户和客户端数据正常。
- 单节点故障符合既定可用性目标。
- 数据库备份可恢复。
- 登录失败锁定策略生效。
- Keycloak 未映射任何对外端口，其管理控制台确认不可访问。
- 统一身份管理平台自身端口只对反向代理开放，不直接暴露给用户网段。
- 事件记录已开启，且管理事件未记录请求体。
- 未执行 `scripts/demo/seed-demo.sh`；确认 Realm 中不存在 `zhangsan`、`medical-demo`、`medical-demo-boot2`。
- 平台管理员 `sso-admin` 已完成首次登录改密，或已改用真实管理员账号。
- 所有应用使用 HTTPS 和精确回调地址。
- 所有客户端强制 PKCE S256，且各子系统已显式开启，登录流验证通过。
- 门户服务账号权限仅为本文列出的八项细粒度权限，Secret 与门户登录 Secret 不同。
- `sso-platform-admin` 的授予名单已复核，未混入机构管理员。
- 接口类子系统的 audience 校验已开启，跨系统 Token 调用被拒绝。
- 跨系统单点登录和统一退出验证通过。
- 用户停用后的会话清理流程验证通过。
- 时钟同步正常，Keycloak、应用和数据库时间一致。

## 升级原则

固定 Keycloak 镜像版本，不使用 `latest`。先在测试环境升级并验证 Realm 导入、登录主题、OIDC 登录、角色 Claim 和退出流程，再升级生产环境。升级前必须完成数据库备份。

## 仓库内的生产基线

仓库提供 `deploy/compose.prod.yml`、`deploy/.env.production.example`、生产 TLS 网关模板和管理平台镜像构建文件。它们的安全边界是：数据库、认证内核和管理平台均不映射宿主端口，只有 TLS 网关开放 80/443；生产启动使用 `start`，严格校验统一域名，继续关闭认证内核自带管理控制台。

```bash
cp deploy/.env.production.example deploy/.env.production
# 填入真实域名、证书绝对路径和由密钥系统下发的密码/Secret
./scripts/release/render-production-realm.sh
./scripts/release/preflight-production.sh
docker compose --env-file deploy/.env.production -f deploy/compose.prod.yml up -d --build
./scripts/release/smoke-test.sh "https://你的统一身份域名"
```

`render-production-realm.sh` 会生成不入库的 Realm 文件，把平台管理员初始密码和两个客户端 Secret 写入一次性导入文件，并保持首次登录强制改密。已经存在的 Realm 不会被覆盖；存量环境应通过变更流程轮换 Secret，而不是重新导入。

备份与恢复命令：

```bash
./scripts/release/backup-postgres.sh
./scripts/release/restore-postgres.sh backups/具体文件.sql.gz --confirm
```

恢复属于破坏性操作，只能在停止业务流量、保留当前库备份后执行。完成后必须跑冒烟测试和关键登录流程。
