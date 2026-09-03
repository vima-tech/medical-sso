#!/usr/bin/env bash
set -euo pipefail

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
output="${1:-backups/keycloak-$(date +%Y%m%d-%H%M%S).sql.gz}"
mkdir -p "$(dirname "$output")"

"${compose[@]}" --env-file "$env_file" -f compose.prod.yml exec -T postgres \
  sh -c 'pg_dump --clean --if-exists --no-owner -U "$POSTGRES_USER" "$POSTGRES_DB"' | gzip -9 > "$output"
chmod 600 "$output"
echo "备份完成：$output"
