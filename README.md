# 医共体统一身份认证

基于 Keycloak 26.7.3 的内网单点登录 MVP，包含统一登录、应用门户、统一人员字段、系统级角色隔离、子系统自助登记、按子系统生成的对接文档，以及 JDK 17 和 JDK 8 两套 Spring Boot 接入组件和可运行 Demo。

## 项目组成

| 目录 | 用途 |
| --- | --- |
| `deploy/keycloak` | Realm 初始化数据，以及完全自定义的登录主题（模板、文案、样式） |
| `medical-sso-spring-boot-starter` | 接入组件，Spring Boot 3.x / JDK 17+ |
| `medical-sso-spring-boot2-starter` | 接入组件，Spring Boot 2.3-2.7 / JDK 8 |
| `medical-sso-gateway` | 接入网关，替改不动的系统完成登录，身份以请求头注入，业务系统零改动 |
| `medical-portal` | 统一身份管理平台，含业务系统登记、对接文档生成与接入自检 |
| `medical-sso-demo` | 子系统最小接入示例（JDK 17），包含页面和 `/api/me` |
| `medical-sso-demo-boot2` | 子系统最小接入示例（JDK 8） |
| `medical-sso-demo-legacy` | 已有账号体系的接入示例：保留原登录，桥接统一身份，支持自助绑定 |
| `docs` | 接入、管理员操作和生产部署文档 |

## 子系统怎么接入

四种方式，按系统情况选一种，不要挨个看：

| 你的系统 | 接入方式 | 改动量 |
| --- | --- | --- |
| Spring Boot，没有自己的登录体系 | 标准接入 | 两段配置 + 一个安全配置类 |
| Spring Boot，已有账号密码登录，且要两种登录并存 | 桥接模式 | 实现一个接口，共两个方法 |
| 不是 Java、没有源码、或代码动不了 | 接入网关 | **零改动**，前面挂一个网关进程 |
| 只提供接口、不做页面 | 资源服务 | 一段配置 |

在管理平台登记业务系统时选好接入方式，页面会直接生成填好真实参数的对接说明。
配完点「自检」，平台会逐项检查配置并给出修复动作。详见 `docs/INTEGRATION.md`。

## 快速开始

### 第一步：装一个容器运行环境

| 你的系统 | 装什么 |
| --- | --- |
| Windows | [Docker Desktop](https://www.docker.com/products/docker-desktop/)，装完打开它，等托盘图标不再转圈 |
| macOS | [Docker Desktop](https://www.docker.com/products/docker-desktop/) |
| Linux | Docker 或 Podman |

**只需要这一样。** 不用装 Java，不用装 Maven，不用装数据库——它们都在镜像里。

### 第二步：启动

| 你的系统 | 怎么做 |
| --- | --- |
| Windows | 双击仓库根目录的 **`启动平台.cmd`** |
| Linux / macOS | 终端执行 **`./scripts/sso.sh start`** |

第一次启动要构建平台镜像，约几分钟；以后每次都是秒级。看到「统一身份认证平台已就绪」就成了。

### 第三步：第一次登录

浏览器打开 **http://localhost:18081**，用下面这组账号登录：

```
账号：sso-admin
密码：Admin@123456
```

**登录后系统会立刻要求你改密码。** 这是刻意设计的，不是出错了——初始密码写在配置文件里，人人可见，所以它只能用一次。改完密码就进入统一身份管理平台，可以开始建机构、建人员、登记业务系统。

后面怎么用，看[管理员操作指南](docs/ADMIN-GUIDE.md)。

### 停止

| 你的系统 | 怎么做 |
| --- | --- |
| Windows | 双击 **`停止平台.cmd`** |
| Linux / macOS | **`./scripts/sso.sh stop`** |

数据存在容器卷里，停止不会丢，下次启动接着用。

### 其它几个命令

```bash
./scripts/sso.sh status    # 看运行状态和对外入口是否可访问
./scripts/sso.sh logs      # 看日志，按 Ctrl+C 退出
```

Windows 上对应 `.\scripts\sso.ps1 status` 和 `.\scripts\sso.ps1 logs`。

**日常只需要这一个脚本。** 底下跑着数据库、认证内核、网关和管理平台四个容器，但运维不需要知道它们各自叫什么。

### 需要改配置吗？

第一次启动会自动生成 `.env`。**本地试用什么都不用改。** 正式上线前必须改的只有四项，`.env` 里都有注释说明：

| 配置项 | 改成什么 |
| --- | --- |
| `SSO_PLATFORM_ADMIN_PASSWORD` | 平台管理员密码 |
| `KC_BOOTSTRAP_ADMIN_PASSWORD` | 认证内核引导管理员密码 |
| `POSTGRES_PASSWORD` | 数据库密码 |
| `SSO_PUBLIC_URL` | 对外访问地址，例如 `https://sso.intra.example` |

完整的上线清单见[生产部署与安全清单](docs/DEPLOYMENT.md)。

## 跑完整演示环境

上面启动的是一个干净平台：只有平台管理员，没有演示人员和演示业务系统。

想看四种接入方式的实际效果，可以跑完整演示——它会额外装载演示数据，并在宿主机上拉起四个示例子系统。**这一步是给开发者看接入效果的，额外需要 Java 17、Maven 3.9+ 和 python3，且目前只支持 Linux / macOS：**

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
| 已有登录体系（桥接模式） | http://localhost:8084 |
| 改不动的系统（接入网关） | http://localhost:8085 |

演示环境里 `sso-admin` 的密码取自 `.env` 的 `SSO_PLATFORM_ADMIN_PASSWORD`，并且**不强制改密**，方便反复演示。演示人员用 `zhangsan / Demo@123456`。

停止四个示例子系统在启动终端按 `Ctrl+C`，停止平台用 `./scripts/sso.sh stop`。

**对外只有 18081 这一个端口。** 认证内核 Keycloak 不映射任何宿主端口，浏览器经网关的 `/auth` 前缀访问它的登录页；它自带的管理控制台已用 `--features-disabled=admin` 在引擎层面关闭，不存在第二个管理入口。

Realm 导入文件是**生产就绪**的：只有平台管理员和平台自身的客户端，没有演示人员、演示业务系统和机构骨架。演示数据由 `scripts/seed-demo.sh` 单独装载，`start-local.sh` 会自动执行；**上线时不要执行该脚本**。

### 三个账号，用途完全不同，不要混用

| 账号 | 登录哪里 | 用途 |
| --- | --- | --- |
| `sso-admin` | 统一身份管理平台 http://localhost:18081 | **平台管理员**。初始密码 `Admin@123456`，首次登录强制改密；演示环境改用 `.env` 里的密码且不强制改密 |
| `zhangsan / Demo@123456` | 应用门户和各业务系统 | **演示医生**，仅由 `seed-demo.sh` 装载，生产环境不存在 |
| `.env` 里的 `KC_BOOTSTRAP_ADMIN_*` | 无图形界面可登录 | **引导管理员**，只存在于 `master` Realm，仅供底层排查 |

引导管理员没有界面可登：Keycloak 控制台已关闭，在业务登录页上用它也一定登不进去，会提示「工号或密码错误」——这是预期行为，它不是业务账号。日常运维一律用 `sso-admin`。

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

用具有 `sso-platform-admin` 身份的管理员账号登录管理平台，进「业务系统」登记，只填系统名称、系统编码、访问地址和接入方式四项。提交后平台自动建立认证关系、强制 PKCE、推导回调地址并创建访问权限，页面随即给出填好真实参数的依赖、配置和代码，逐段复制到业务系统即可。Client Secret 只展示一次，配置片段里只留环境变量占位，可以安全提交进代码库。

最后在管理平台「授权中心」中为人员分配该系统的访问权限。详见 [管理员操作指南](docs/ADMIN-GUIDE.md)。

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

交付候选还应执行 `./scripts/check-release.sh`；本地未提交改动可用
`./scripts/check-release.sh --allow-dirty` 做同等构建检查。生产部署、备份恢复、验证和回滚步骤见
[Release Package](docs/RELEASE.md)。

当前 `compose.yml` 使用开发模式和演示密钥，只用于本机或隔离测试网。生产部署必须按照部署文档替换。
