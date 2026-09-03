#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || "$2" != "--confirm" ]]; then
  echo "用法：$0 <backup.sql.gz> --confirm" >&2
  echo "恢复会覆盖当前数据库内容，请先停止业务流量并保留现有备份。" >&2
  exit 1
fi

backup="$1"
if [[ ! -f "$backup" ]]; then
  echo "备份文件不存在：$backup" >&2
  exit 1
fi

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if command -v docker >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v podman >/dev/null 2>&1; then
  compose=(podman compose)
else
  echo "未找到 Docker 或 Podman。" >&2
  exit 1
fi

env_file="${PRODUCTION_ENV_FILE:-.env.production}"
gzip -dc "$backup" | "${compose[@]}" --env-file "$env_file" -f compose.prod.yml exec -T postgres \
  sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" "$POSTGRES_DB"'
echo "恢复完成。请启动服务并执行 scripts/smoke-test.sh。"
