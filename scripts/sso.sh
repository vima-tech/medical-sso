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
# 想跑带演示子系统的完整环境，用 scripts/demo/start-local.sh，不要用这里。
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
source "$project_dir/scripts/lib/compose.sh"
source "$project_dir/scripts/lib/dotenv.sh"

# 先校验子命令再探测容器运行时：敲错命令时该看到用法，而不是「没装 Docker」
case "${1:-}" in
  start|stop|status|logs) ;;
  *)
    echo "用法：$0 {start|stop|status|logs [服务名]}" >&2
    exit 1
    ;;
esac

sso_compose_detect || exit 1

cmd_start() {
  sso_ensure_env

  echo "启动中（首次会构建平台镜像，需要几分钟）..."
  # --force-recreate 不能省：podman compose 在镜像重建后不会重建已存在的容器，
  # 于是「改完代码再 start」跑的仍是上一版镜像，界面看不到任何变化，
  # 而这种「明明改了却没生效」极难自查。多花的时间只是容器重建，数据都在卷里。
  sso_compose_dev up -d --build --force-recreate

  local url
  url="$(sso_public_url)"
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
  sso_compose_dev ps
  echo
  local url
  url="$(sso_public_url)"
  if curl -fsS -o /dev/null "$url"; then
    echo "对外入口 $url 可访问。"
  else
    echo "对外入口 $url 不可访问。"
  fi
}

case "${1:-}" in
  start)  cmd_start ;;
  stop)   sso_compose_dev down ;;
  status) cmd_status ;;
  logs)   shift; sso_compose_dev logs -f "$@" ;;
  *)
    echo "用法：$0 {start|stop|status|logs [服务名]}" >&2
    exit 1
    ;;
esac
