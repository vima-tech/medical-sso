#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$project_dir"
source "$project_dir/scripts/lib/compose.sh"

sso_compose_detect || exit 1

env_file="${PRODUCTION_ENV_FILE:-$SSO_ENV_PROD}"
output="${1:-backups/keycloak-$(date +%Y%m%d-%H%M%S).sql.gz}"
mkdir -p "$(dirname "$output")"

sso_compose_prod "$env_file" exec -T postgres \
  sh -c 'pg_dump --clean --if-exists --no-owner -U "$POSTGRES_USER" "$POSTGRES_DB"' | gzip -9 > "$output"
chmod 600 "$output"
echo "备份完成：$output"
