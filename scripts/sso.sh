#!/usr/bin/env bash
# 统一身份认证平台的运维入口。
#
# 底下是四个容器（数据库、认证内核、网关、统一身份管理平台），
# 但日常运维只需要认这一个命令，不需要知道它们各自叫什么。
#
#   ./scripts/sso.sh start     启动，等到平台真正可访问才返回
#   ./scripts/sso.sh stop      停止
#   ./scripts/sso.sh status    看运行状态
#   ./scripts/sso.sh logs      看日志，可跟服务名只看其中一个
#
# 想跑带演示子系统的完整环境，用 scripts/start-local.sh，不要用这里。
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

dotenv_value() {
  local key="$1"
  local line
  [[ -f .env ]] || return 0
  while IFS= read -r line; do
    line="${line%$'\r'}"
    if [[ "$line" == "$key="* ]]; then
      printf '%s' "${line#*=}"
      return
    fi
  done < .env
}

public_url() {
  local url
  url="${SSO_PUBLIC_URL:-$(dotenv_value SSO_PUBLIC_URL)}"
  printf '%s' "${url:-http://localhost:$(dotenv_value PLATFORM_PORT)}"
}

cmd_start() {
  if [[ ! -f .env ]]; then
    cp .env.example .env
    echo "已从 .env.example 创建 .env。演示环境可直接使用，正式环境请先修改密码。"
  fi

  echo "启动中（首次会构建平台镜像，需要几分钟）..."
  "${compose[@]}" up -d --build

  local url
  url="$(public_url)"
  echo "等待平台就绪..."
  for _ in $(seq 1 120); do
    if curl -fsS -o /dev/null "$url"; then
      echo
      echo "统一身份认证平台已就绪：$url"
      echo "平台管理员登录后进管理平台，业务人员登录后进应用门户。"
      return 0
    fi
    sleep 1
  done

  echo "平台未在 120 秒内就绪，用 ./scripts/sso.sh logs 查看原因。" >&2
  return 1
}

cmd_status() {
  "${compose[@]}" ps
  echo
  local url
  url="$(public_url)"
  if curl -fsS -o /dev/null "$url"; then
    echo "对外入口 $url 可访问。"
  else
    echo "对外入口 $url 不可访问。"
  fi
}

case "${1:-}" in
  start)  cmd_start ;;
  stop)   "${compose[@]}" down ;;
  status) cmd_status ;;
  logs)   shift; "${compose[@]}" logs -f "$@" ;;
  *)
    echo "用法：$0 {start|stop|status|logs [服务名]}" >&2
    exit 1
    ;;
esac
