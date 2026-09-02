#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if command -v docker >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v podman >/dev/null 2>&1; then
  compose=(podman compose)
else
  echo "未找到 Docker 或 Podman，请先安装容器运行环境。" >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "已从 .env.example 创建 .env。演示环境可直接使用，正式环境请先修改密码。"
fi

platform_port="${PLATFORM_PORT:-}"
if [[ -z "$platform_port" ]]; then
  while IFS= read -r line; do
    if [[ "$line" =~ ^[[:space:]]*PLATFORM_PORT[[:space:]]*=[[:space:]]*([0-9]+)[[:space:]]*$ ]]; then
      platform_port="${BASH_REMATCH[1]}"
      break
    fi
  done < .env
fi
platform_port="${platform_port:-18081}"

if [[ ! "$platform_port" =~ ^[0-9]+$ ]]; then
  echo "PLATFORM_PORT 必须是数字端口。" >&2
  exit 1
fi

public_url="http://localhost:${platform_port}"
issuer="${public_url}/auth/realms/medical"

echo "构建统一身份管理平台与接入组件..."
mvn -Dmaven.repo.local=.m2/repository -q -DskipTests install

# 认证内核与网关。Keycloak 不对外暴露端口，只能经网关的 /auth 访问。
"${compose[@]}" up -d

echo "等待认证内核就绪..."
ready=false
for _ in $(seq 1 120); do
  if curl -fsS "${issuer}/.well-known/openid-configuration" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done

if [[ "$ready" != "true" ]]; then
  echo "认证内核未在 120 秒内就绪，请查看容器日志。" >&2
  exit 1
fi

# Realm 导入文件是生产就绪的，本机演示需要额外装载示例数据
echo "装载演示数据..."
PLATFORM_URL="$public_url" ./scripts/seed-demo.sh

portal_pid=""
demo_pid=""
demo_boot2_pid=""
cleanup() {
  [[ -n "$portal_pid" ]] && kill "$portal_pid" 2>/dev/null || true
  [[ -n "$demo_pid" ]] && kill "$demo_pid" 2>/dev/null || true
  [[ -n "$demo_boot2_pid" ]] && kill "$demo_boot2_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# 平台监听 18082，对外由网关以 ${platform_port} 承载
SSO_ISSUER="$issuer" \
SSO_ACCOUNT_URL="${issuer}/account" \
java -jar medical-portal/target/medical-portal-0.1.0-SNAPSHOT.jar &
portal_pid=$!
SSO_ISSUER="$issuer" \
java -jar medical-sso-demo/target/medical-sso-demo-0.1.0-SNAPSHOT.jar &
demo_pid=$!
SSO_ISSUER="$issuer" \
java -jar medical-sso-demo-boot2/target/medical-sso-demo-boot2-0.1.0-SNAPSHOT.jar &
demo_boot2_pid=$!

echo
echo "统一身份管理平台：${public_url}"
echo "  平台管理员登录后进管理平台，业务人员登录后进应用门户"
echo "接入示例（JDK 17）：http://localhost:8082"
echo "接入示例（JDK 8）： http://localhost:8083"
echo
echo "平台管理员：sso-admin / Admin@123456"
echo "演示人员：  zhangsan / Demo@123456"
echo
echo "认证内核 Keycloak 不对外暴露端口，其自带管理控制台已在引擎层面关闭。"
echo "按 Ctrl+C 停止三个 Spring Boot 应用；容器可用 scripts/stop-local.sh 停止。"

wait -n "$portal_pid" "$demo_pid" "$demo_boot2_pid"
