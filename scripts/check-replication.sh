#!/usr/bin/env bash
# 检查两组复制线程、server-id、只读保护与 GTID 追平状态。

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "${SCRIPT_DIR}/_common.sh"

require_docker

check_pair() {
  local label="$1"
  local source_service="$2"
  local replica_service="$3"
  local expected_source_id="$4"
  local expected_replica_id="$5"
  local database_name="$6"
  local source_id
  local replica_meta
  local replica_id
  local read_only
  local super_read_only
  local status
  local io_running
  local sql_running
  local source_gtid
  local wait_result

  print_title "检查 ${label}: ${source_service} -> ${replica_service}"

  source_id="$(run_mysql "${source_service}" "SELECT @@server_id")"
  replica_meta="$(run_mysql "${replica_service}" \
    "SELECT CONCAT(@@server_id, '|', @@GLOBAL.read_only, '|', @@GLOBAL.super_read_only)")"
  IFS='|' read -r replica_id read_only super_read_only <<<"${replica_meta}"

  [[ "${source_id}" == "${expected_source_id}" ]] \
    || fail "${source_service} server-id 应为 ${expected_source_id}，实际为 ${source_id}"
  [[ "${replica_id}" == "${expected_replica_id}" ]] \
    || fail "${replica_service} server-id 应为 ${expected_replica_id}，实际为 ${replica_id}"
  [[ "${read_only}" == "1" && "${super_read_only}" == "1" ]] \
    || fail "${replica_service} 未同时开启 read_only/super_read_only"

  # 使用纵向输出，字段名在不同 MySQL 补丁版本中比列序号更稳定。
  status="$(compose exec -T "${replica_service}" env MYSQL_PWD="${ROOT_PASSWORD}" \
    mysql --host=127.0.0.1 --user=root --execute="SHOW REPLICA STATUS\G")"
  io_running="$(awk '$1 == "Replica_IO_Running:" {print $2}' <<<"${status}")"
  sql_running="$(awk '$1 == "Replica_SQL_Running:" {print $2}' <<<"${status}")"

  # 输出面试最常看的六个字段；出错时能直接看到 Last_*_Error。
  awk '
    $1 == "Source_Host:" ||
    $1 == "Replica_IO_Running:" ||
    $1 == "Replica_SQL_Running:" ||
    $1 == "Seconds_Behind_Source:" ||
    $1 == "Last_IO_Error:" ||
    $1 == "Last_SQL_Error:" { print }
  ' <<<"${status}"

  [[ "${io_running}" == "Yes" ]] \
    || fail "${replica_service} 的 Replica_IO_Running=${io_running:-MISSING}"
  [[ "${sql_running}" == "Yes" ]] \
    || fail "${replica_service} 的 Replica_SQL_Running=${sql_running:-MISSING}"

  # Seconds_Behind_Source=0 只是近似指标；GTID wait 更能证明“当前检查点”已经执行。
  source_gtid="$(run_mysql "${source_service}" "SELECT @@GLOBAL.gtid_executed")"
  wait_result="$(run_mysql "${replica_service}" \
    "SELECT WAIT_FOR_EXECUTED_GTID_SET('${source_gtid}', 10)")"
  [[ "${wait_result}" == "0" ]] \
    || fail "${replica_service} 在 10 秒内未追平源 GTID，返回 ${wait_result}"

  # 最后不用 root，而以 ShardingSphere 配置中的账号访问，验证副本业务权限也正常。
  app_server_id="$(run_app_mysql "${replica_service}" "${database_name}" "SELECT @@server_id")"
  [[ "${app_server_id}" == "${expected_replica_id}" ]] \
    || fail "asset_app 访问 ${replica_service} 失败或 server-id 不符"

  pass "${label} 复制线程正常、GTID 已追平、副本只读保护生效"
}

check_pair "ds0" "ds0-primary" "ds0-replica" "100" "200" "asset_ds_0"
check_pair "ds1" "ds1-primary" "ds1-replica" "101" "201" "asset_ds_1"

echo
pass "两组 MySQL GTID 主从复制均健康"
