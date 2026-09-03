#!/usr/bin/env bash
set -euo pipefail

base_url="${1:-http://localhost:18081}"
base_url="${base_url%/}"
failures=0

expect_code() {
  local path="$1"
  local expected="$2"
  local actual
  actual="$(curl -sS -o /dev/null -w '%{http_code}' "$base_url$path")"
  if [[ "$actual" == "$expected" ]]; then
    echo "通过 $path -> $actual"
  else
    echo "失败 $path -> $actual，期望 $expected" >&2
    failures=$((failures + 1))
  fi
}

expect_code "/actuator/health" "200"
expect_code "/auth/realms/medical/.well-known/openid-configuration" "200"
expect_code "/" "302"
expect_code "/admin" "302"
expect_code "/apps" "302"
expect_code "/auth/realms/master/.well-known/openid-configuration" "404"
expect_code "/auth/admin/master/" "404"

if (( failures > 0 )); then
  echo "冒烟测试失败：$failures 项。" >&2
  exit 1
fi
echo "冒烟测试全部通过。"
