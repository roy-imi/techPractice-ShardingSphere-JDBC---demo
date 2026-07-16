#!/usr/bin/env bash
# 供本目录其他运维脚本复用的 Compose / MySQL 小工具。
# 文件名以下划线开头，表示它不是用户直接运行的入口。

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

# 如果用户复制了 .env.example，则让 Bash 脚本读取与 Compose 相同的 root 密码。
# shellcheck disable=SC1091
if [[ -f "${PROJECT_DIR}/.env" ]]; then
  set -a
  source "${PROJECT_DIR}/.env"
  set +a
fi

readonly ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root-password}"
readonly APP_PASSWORD="asset-password"

# 始终在项目目录执行 Compose，避免用户从任意工作目录运行脚本时找不到 .env/Compose 文件。
compose() {
  (
    cd -- "${PROJECT_DIR}"
    docker compose "$@"
  )
}

require_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "未找到 docker 命令，请先安装 Docker Desktop。" >&2
    return 1
  fi

  if ! docker compose version >/dev/null 2>&1; then
    echo "未找到 Docker Compose v2（docker compose）。" >&2
    return 1
  fi

  if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon 未运行，请先启动 Docker Desktop。" >&2
    return 1
  fi
}

# 以 root 执行一条 SQL。-N 关闭列名、-s 关闭表格，便于脚本精确读取单值。
run_mysql() {
  local service="$1"
  local sql="$2"

  compose exec -T "${service}" env MYSQL_PWD="${ROOT_PASSWORD}" \
    mysql --host=127.0.0.1 --user=root --batch --raw --skip-column-names \
    --execute="${sql}"
}

# 以应用账号执行查询，用来证明配置中的 asset_app 确实可登录主库和副本。
run_app_mysql() {
  local service="$1"
  local database_name="$2"
  local sql="$3"

  compose exec -T "${service}" env MYSQL_PWD="${APP_PASSWORD}" \
    mysql --host=127.0.0.1 --user=asset_app --database="${database_name}" \
    --batch --raw --skip-column-names --execute="${sql}"
}

print_title() {
  echo
  echo "========== $* =========="
}

fail() {
  echo "[失败] $*" >&2
  return 1
}

pass() {
  echo "[通过] $*"
}
