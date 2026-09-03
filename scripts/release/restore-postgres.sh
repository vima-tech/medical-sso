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

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$project_dir"
source "$project_dir/scripts/lib/compose.sh"
sso_compose_detect || exit 1

env_file="${PRODUCTION_ENV_FILE:-$SSO_ENV_PROD}"
gzip -dc "$backup" | sso_compose_prod "$env_file" exec -T postgres \
  sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" "$POSTGRES_DB"'
echo "恢复完成。请启动服务并执行 scripts/release/smoke-test.sh。"
