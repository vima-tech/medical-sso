#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if command -v docker >/dev/null 2>&1; then
  docker compose down
elif command -v podman >/dev/null 2>&1; then
  podman compose down
else
  echo "未找到 Docker 或 Podman。" >&2
  exit 1
fi
