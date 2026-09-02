# 医共体统一身份认证

基于 Keycloak 26.7.3 的内网单点登录 MVP，包含统一登录、应用门户、统一人员字段、系统级角色隔离、子系统自助登记、按子系统生成的对接文档，以及 JDK 17 和 JDK 8 两套 Spring Boot 接入组件和可运行 Demo。

## 项目组成

| 目录 | 用途 |
| --- | --- |
| `deploy/keycloak` | Realm 初始化数据，以及完全自定义的登录主题（模板、文案、样式） |
| `medical-sso-spring-boot-starter` | 接入组件，Spring Boot 3.x / JDK 17+ |
| `medical-sso-spring-boot2-starter` | 接入组件，Spring Boot 2.3-2.7 / JDK 8 |
| `medical-portal` | 统一应用门户，含子系统登记与对接文档生成 |
| `medical-sso-demo` | 子系统最小接入示例（JDK 17），包含页面和 `/api/me` |
| `medical-sso-demo-boot2` | 子系统最小接入示例（JDK 8） |
| `docs` | 接入、管理员操作和生产部署文档 |

## 本地启动

前置条件：Java 17、Maven 3.9+、Docker Compose 或 Podman Compose、curl。

```bash
chmod +x scripts/*.sh
./scripts/start-local.sh
```

启动后访问：

| 服务 | 地址 |
| --- | --- |
| 统一身份管理平台 | http://localhost:18081 |
| 接入示例（JDK 17） | http://localhost:8082 |
| 接入示例（JDK 8） | http://localhost:8083 |

**对外只有 18081 这一个端口。** 认证内核 Keycloak 不映射任何宿主端口，浏览器经网关的 `/auth` 前缀访问它的登录页；它自带的管理控制台已用 `--features-disabled=admin` 在引擎层面关闭，不存在第二个管理入口。

Realm 导入文件是**生产就绪**的：只有平台管理员和平台自身的客户端，没有演示人员、演示业务系统和机构骨架，且平台管理员首次登录强制改密。本机启动脚本会额外执行 `scripts/seed-demo.sh` 装载演示数据；上线时不要执行该脚本。

### 三个账号，用途完全不同，不要混用

| 账号 | 登录哪里 | 用途 |
| --- | --- | --- |
| `sso-admin / Admin@123456` | 统一身份管理平台 http://localhost:18081 | **平台管理员**。演示环境为固定密码；生产环境首次登录强制改密 |
| `zhangsan / Demo@123456` | 应用门户和各业务系统 | **演示医生**，仅由 `seed-demo.sh` 装载，生产环境不存在 |
| `admin / admin-change-me` | 无图形界面可登录 | **引导管理员**，只存在于 `master` Realm，仅供底层排查 |

`admin / admin-change-me` 来自 `.env`，是认证内核的引导管理员，只存在于 `master` Realm。Keycloak 控制台已关闭，这个账号没有界面可登；在业务登录页上用它也一定登不进去，会提示「工号或密码错误」。日常运维一律用 `sso-admin`。

停止三个 Spring Boot 应用请在启动终端按 `Ctrl+C`，停止 Keycloak 和 PostgreSQL：

```bash
./scripts/stop-local.sh
```

## 统一身份管理平台

`18081` 是本项目的核心产品，不是演示门户。医院管理员在这里完成全部日常运维，界面只用业务语言，不出现 Realm、Client、Scope、Mapper 等术语。

```text
                18081
                  ↓  按角色分流
    ┌─────────────┴─────────────┐
平台管理员                    业务人员
    ↓                            ↓
统一身份管理平台 /admin      应用门户 /apps
 ├── 机构与科室               只列出本人有权进入的系统
 ├── 人员管理
 └── 业务系统
    ↓ Admin API
Keycloak 认证内核（不对外暴露端口，无管理控制台）
```

访问 `18081` 无需记路径：平台管理员登录后直接落到管理平台，业务人员落到应用门户。两边顶栏互有入口。

Keycloak 只作为认证内核运行在后台，其自带控制台仅用于引擎故障排查，不是运维入口。

| 模块 | 能做什么 |
| --- | --- |
| 机构与科室 | 维护机构、科室及编码；删除前校验下属科室与在职人员 |
| 人员管理 | 建档、编辑、调科、重置密码、停用（同时注销会话）、强制下线、批量导入 |
| 授权中心 | 「人员 × 业务系统」矩阵，按科室批量授权与收回 |
| 业务系统 | 登记、改名换址、系统角色维护、重新生成 Secret、停用与删除；生成专属对接文档 |
| 通用身份 | 维护医生、护士等跨系统岗位标识 |
| 审计记录 | 登录成功失败、退出、改密，以及管理员的全部增删改；只读不可删 |

界面术语对照：

| 底层概念 | 管理员看到的名称 |
| --- | --- |
| Client | 业务系统 |
| Client Role `access` | 可访问的业务系统 |
| Realm Role | 通用身份 |
| Group | 机构 / 科室 |
| Redirect URI | 系统回调地址（自动推导，不需填写） |

## 接入一个子系统

用 `sso-platform-admin` 账号登录门户，进「子系统管理」登记，只填系统名称、系统编码、访问地址和技术栈四项。提交后平台自动建立客户端、强制 PKCE、推导回调地址并创建 `access` 角色，页面随即给出填好真实参数的依赖、配置和代码，逐段复制到子系统即可。Client Secret 只展示一次，配置片段里只留环境变量占位，可以安全提交进代码库。

最后在 Keycloak 中给用户分配该系统的 `access` 角色。详见 [管理员操作指南](docs/ADMIN-GUIDE.md)。

## 统一身份返回值

子系统通过 `MedicalUserMapper` 得到稳定的业务对象：

```json
{
  "subject": "Keycloak 用户 UUID",
  "personId": "P000123",
  "employeeNo": "10086",
  "username": "zhangsan",
  "name": "张三",
  "organizationCode": "H001",
  "departmentCode": "D001",
  "realmRoles": ["doctor"],
  "clientRoles": ["access", "his-user"]
}
```

`personId` 是跨系统稳定人员标识。`subject` 只作为认证平台内部标识。患者信息、诊疗数据、身份证号等敏感数据不得放入 Token。

两套接入组件返回的对象方法名一致，业务代码在 JDK 8 和 JDK 17 之间可以直接互换。

## 文档入口

- [Spring Boot 完整接入文档](docs/INTEGRATION.md)
- [用户、机构、科室和角色管理](docs/ADMIN-GUIDE.md)
- [生产部署与安全清单](docs/DEPLOYMENT.md)

## 验证

```bash
mvn -Dmaven.repo.local=.m2/repository test
```

当前 `compose.yml` 使用开发模式和演示密钥，只用于本机或隔离测试网。生产部署必须按照部署文档替换。
