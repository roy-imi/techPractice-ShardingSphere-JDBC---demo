#!/usr/bin/env bash
# 恢复副本 SQL 应用线程，并等待它执行完恢复时主库已有的 GTID 集合。

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "${SCRIPT_DIR}/_common.sh"

require_docker

target="${1:-all}"
case "${target}" in
  ds0) pairs=("ds0-primary:ds0-replica") ;;
  ds1) pairs=("ds1-primary:ds1-replica") ;;
  all) pairs=("ds0-primary:ds0-replica" "ds1-primary:ds1-replica") ;;
  *)
    echo "用法：$0 [ds0|ds1|all]" >&2
    exit 2
    ;;
esac

for pair in "${pairs[@]}"; do
  source_service="${pair%%:*}"
  replica_service="${pair##*:}"

  print_title "恢复 ${replica_service} 的 SQL_THREAD"
  run_mysql "${replica_service}" "START REPLICA SQL_THREAD"

  source_gtid="$(run_mysql "${source_service}" "SELECT @@GLOBAL.gtid_executed")"
  wait_result="$(run_mysql "${replica_service}" \
    "SELECT WAIT_FOR_EXECUTED_GTID_SET('${source_gtid}', 60)")"
  [[ "${wait_result}" == "0" ]] \
    || fail "${replica_service} 在 60 秒内未追平，返回 ${wait_result}"

  pass "${replica_service} 已恢复并追平恢复时的主库 GTID"
done

"${SCRIPT_DIR}/check-replication.sh"
