#!/usr/bin/env bash
# 停止并删除容器/网络，但默认保留 MySQL 命名卷，之后启动无需重新初始化。

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "${SCRIPT_DIR}/_common.sh"

require_docker

print_title "停止数据库环境（保留数据卷）"
compose down --remove-orphans

pass "容器已停止，数据卷仍保留"
echo "若确实要清空全部数据并重新执行初始化脚本，请手工确认后运行："
echo "  docker compose down -v"
