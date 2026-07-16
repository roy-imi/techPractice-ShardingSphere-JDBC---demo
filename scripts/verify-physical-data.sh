#!/usr/bin/env bash
# ============================================================================
# 验收“单库单表 -> 两库四个固定季度槽位 + 每库一主一从”的物理结果。
# ============================================================================
# 本脚本直接连接物理 MySQL，不经过 ShardingSphere；它回答六个关键问题：
# 1. 每个分片库是否恰好存在 inspection_record_q1 ~ q4 四张同构表？
# 2. q1/q2/q3/q4 内 record_date 是否分别属于自然季度 1/2/3/4？
# 3. 槽位元数据绑定的 YYYYQn 是否与实际数据年份、季度完全一致？
# 4. ds0 是否只有偶数租户、ds1 是否只有奇数租户？
# 5. 稳定态是否只有三个 ACTIVE 热季度，另一个槽位处于 EMPTY 或 EXPIRED？
# 6. 主库与副本在同一 GTID 检查点上的表、元数据和行数是否一致？

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "${SCRIPT_DIR}/_common.sh"

require_docker

assert_equals() {
  local expected="$1"
  local actual="$2"
  local message="$3"

  if [[ "${actual}" != "${expected}" ]]; then
    fail "${message}：期望=${expected}，实际=${actual}"
    return 1
  fi
  pass "${message}（${actual}）"
}

assert_greater_or_equal() {
  local minimum="$1"
  local actual="$2"
  local message="$3"

  if ((actual < minimum)); then
    fail "${message}：至少=${minimum}，实际=${actual}"
    return 1
  fi
  pass "${message}（${actual}）"
}

# 生成跨四个固定物理槽位的 UNION ALL。这里只用于离线验收；业务查询仍应访问
# 逻辑表 inspection_record，让 ShardingSphere 根据 record_date 裁剪真实节点。
build_union_count_sql() {
  local predicate="${1:-1=1}"
  local sql=""
  local quarter

  for quarter in 1 2 3 4; do
    if [[ -n "${sql}" ]]; then
      sql+=" UNION ALL "
    fi
    sql+="SELECT COUNT(*) AS row_count FROM inspection_record_q${quarter} WHERE ${predicate}"
  done

  printf 'SELECT COALESCE(SUM(row_count), 0) FROM (%s) AS quarter_counts' "${sql}"
}

build_union_ids_sql() {
  local sql=""
  local quarter

  for quarter in 1 2 3 4; do
    if [[ -n "${sql}" ]]; then
      sql+=" UNION ALL "
    fi
    sql+="SELECT id FROM inspection_record_q${quarter}"
  done

  printf '%s' "${sql}"
}

# 统一排序后的快照既检查绑定信息，也检查主从复制后的运维状态是否完全一致。
read_slot_snapshot() {
  local service="$1"
  local database_name="$2"

  run_mysql "${service}" "
    SELECT GROUP_CONCAT(
      CONCAT(slot_no, ':', physical_table, ':', COALESCE(bound_quarter, '-'), ':', slot_status,
             ':', COALESCE(DATE_FORMAT(cleanup_after, '%Y-%m-%d %H:%i:%s.%f'), '-'))
      ORDER BY slot_no SEPARATOR '|'
    )
    FROM ${database_name}.inspection_quarter_slot
  "
}

verify_shard() {
  local label="$1"
  local primary_service="$2"
  local replica_service="$3"
  local database_name="$4"
  local expected_mod="$5"
  local expected_primary_id="$6"
  local expected_replica_id="$7"
  local table_count
  local replica_table_count
  local obsolete_month_tables
  local primary_rows
  local replica_rows
  local misplaced_rows
  local misplaced_sql
  local wrong_quarter_rows=0
  local wrong_binding_rows=0
  local current_wrong
  local duplicate_ids
  local primary_app_id
  local replica_app_id
  local all_count_sql
  local all_ids_sql
  local quarter
  local slot_count
  local slot_state_counts
  local active_count
  local empty_count
  local expired_count
  local wrong_slot_binding
  local empty_slot_rows
  local active_span
  local primary_slot_snapshot
  local replica_slot_snapshot

  print_title "验收 ${label} (${database_name})"

  table_count="$(run_mysql "${primary_service}" "
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = '${database_name}'
      AND table_name REGEXP '^inspection_record_q[1-4]$'
  ")"
  assert_equals "4" "${table_count}" "${primary_service} 固定季度表数"

  replica_table_count="$(run_mysql "${replica_service}" "
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = '${database_name}'
      AND table_name REGEXP '^inspection_record_q[1-4]$'
  ")"
  assert_equals "4" "${replica_table_count}" "${replica_service} 固定季度表数"

  # 如果复用了旧 Demo 数据卷，十二张 2026 月表可能仍在；显式失败比静默混用更安全。
  obsolete_month_tables="$(run_mysql "${primary_service}" "
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = '${database_name}'
      AND table_name REGEXP '^inspection_record_[0-9]{6}$'
  ")"
  assert_equals "0" "${obsolete_month_tables}" "${primary_service} 不残留旧月表"

  all_count_sql="$(build_union_count_sql)"
  primary_rows="$(run_mysql "${primary_service}" "USE ${database_name}; ${all_count_sql}")"
  replica_rows="$(run_mysql "${replica_service}" "USE ${database_name}; ${all_count_sql}")"
  assert_greater_or_equal "3" "${primary_rows}" "${primary_service} 至少包含三个跨季度样例"
  assert_equals "${primary_rows}" "${replica_rows}" "${label} 主从跨季度总行数一致"

  misplaced_sql="$(build_union_count_sql "MOD(tenant_id, 2) <> ${expected_mod}")"
  misplaced_rows="$(run_mysql "${primary_service}" "USE ${database_name}; ${misplaced_sql}")"
  assert_equals "0" "${misplaced_rows}" \
    "${database_name} 不存在 tenant_id MOD 2 != ${expected_mod} 的错库数据"

  for quarter in 1 2 3 4; do
    # 第一层校验：自然季度必须和 q 后缀一致，不允许把 5 月数据写入 q1。
    current_wrong="$(run_mysql "${primary_service}" "
      SELECT COUNT(*)
      FROM ${database_name}.inspection_record_q${quarter}
      WHERE QUARTER(record_date) <> ${quarter}
    ")"
    wrong_quarter_rows=$((wrong_quarter_rows + current_wrong))

    # 第二层校验：年份也必须等于槽位元数据绑定的 YYYYQn，防止跨年复用后混入旧数据。
    current_wrong="$(run_mysql "${primary_service}" "
      SELECT COUNT(*)
      FROM ${database_name}.inspection_record_q${quarter} AS r
      LEFT JOIN ${database_name}.inspection_quarter_slot AS s ON s.slot_no = ${quarter}
      WHERE s.bound_quarter IS NULL
         OR CONCAT(YEAR(r.record_date), 'Q', QUARTER(r.record_date)) <> s.bound_quarter
    ")"
    wrong_binding_rows=$((wrong_binding_rows + current_wrong))
  done
  assert_equals "0" "${wrong_quarter_rows}" "${database_name} 日期季度与 q 后缀完全一致"
  assert_equals "0" "${wrong_binding_rows}" "${database_name} 数据与 YYYYQn 槽位绑定完全一致"

  all_ids_sql="$(build_union_ids_sql)"
  duplicate_ids="$(run_mysql "${primary_service}" "
    USE ${database_name};
    SELECT COUNT(*) - COUNT(DISTINCT id) FROM (${all_ids_sql}) AS all_ids
  ")"
  assert_equals "0" "${duplicate_ids}" "${database_name} 跨季度主键无重复"

  slot_count="$(run_mysql "${primary_service}" \
    "SELECT COUNT(*) FROM ${database_name}.inspection_quarter_slot")"
  assert_equals "4" "${slot_count}" "${database_name} 槽位元数据行数"

  slot_state_counts="$(run_mysql "${primary_service}" "
    SELECT CONCAT(
      SUM(slot_status = 'ACTIVE'), '|',
      SUM(slot_status = 'EMPTY'), '|',
      SUM(slot_status = 'EXPIRED')
    )
    FROM ${database_name}.inspection_quarter_slot
  ")"
  IFS='|' read -r active_count empty_count expired_count <<<"${slot_state_counts}"
  assert_equals "3" "${active_count}" "${database_name} 常态只保留三个 ACTIVE 热季度"
  assert_equals "1" "$((empty_count + expired_count))" \
    "${database_name} 第四槽位处于待命或延迟清理状态"

  wrong_slot_binding="$(run_mysql "${primary_service}" "
    SELECT COUNT(*)
    FROM ${database_name}.inspection_quarter_slot
    WHERE physical_table <> CONCAT('inspection_record_q', slot_no)
       OR (bound_quarter IS NOT NULL AND RIGHT(bound_quarter, 1) <> CAST(slot_no AS CHAR))
  ")"
  assert_equals "0" "${wrong_slot_binding}" "${database_name} 固定槽位映射未发生错乱"

  empty_slot_rows="$(run_mysql "${primary_service}" "
    SELECT COALESCE(SUM(table_rows), 0)
    FROM (
      SELECT 1 AS slot_no, COUNT(*) AS table_rows FROM ${database_name}.inspection_record_q1
      UNION ALL SELECT 2, COUNT(*) FROM ${database_name}.inspection_record_q2
      UNION ALL SELECT 3, COUNT(*) FROM ${database_name}.inspection_record_q3
      UNION ALL SELECT 4, COUNT(*) FROM ${database_name}.inspection_record_q4
    ) AS counts
    JOIN ${database_name}.inspection_quarter_slot AS slots USING (slot_no)
    WHERE slots.slot_status = 'EMPTY'
  ")"
  assert_equals "0" "${empty_slot_rows}" "${database_name} EMPTY 待命槽位确实为空"

  # 三个 ACTIVE 绑定季度必须连续，防止遗漏季度或错误回退。
  active_span="$(run_mysql "${primary_service}" "
    SELECT MAX(LEFT(bound_quarter, 4) * 4 + RIGHT(bound_quarter, 1))
         - MIN(LEFT(bound_quarter, 4) * 4 + RIGHT(bound_quarter, 1))
    FROM ${database_name}.inspection_quarter_slot
    WHERE slot_status = 'ACTIVE'
  ")"
  assert_equals "2" "${active_span}" "${database_name} 三个 ACTIVE 季度连续"

  primary_slot_snapshot="$(read_slot_snapshot "${primary_service}" "${database_name}")"
  replica_slot_snapshot="$(read_slot_snapshot "${replica_service}" "${database_name}")"
  assert_equals "${primary_slot_snapshot}" "${replica_slot_snapshot}" "${label} 主从槽位元数据一致"

  # 用 asset_app 登录并读取 @@server_id，同时验证账号、schema 与物理节点身份。
  primary_app_id="$(run_app_mysql "${primary_service}" "${database_name}" "SELECT @@server_id")"
  replica_app_id="$(run_app_mysql "${replica_service}" "${database_name}" "SELECT @@server_id")"
  assert_equals "${expected_primary_id}" "${primary_app_id}" "asset_app 命中 ${primary_service}"
  assert_equals "${expected_replica_id}" "${replica_app_id}" "asset_app 命中 ${replica_service}"

  echo "${label} 当前总行数：primary=${primary_rows}, replica=${replica_rows}"
  echo "${label} 槽位状态：${primary_slot_snapshot}"
}

print_title "先确认 GTID 复制健康"
"${SCRIPT_DIR}/check-replication.sh"

print_title "验收 legacy 单库单表"
legacy_server_id="$(run_app_mysql "legacy-mysql" "asset_legacy" "SELECT @@server_id")"
legacy_table_count="$(run_mysql "legacy-mysql" "
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema='asset_legacy' AND table_name='inspection_record'
")"
legacy_rows="$(run_app_mysql "legacy-mysql" "asset_legacy" "SELECT COUNT(*) FROM inspection_record")"
assert_equals "10" "${legacy_server_id}" "legacy server-id"
assert_equals "1" "${legacy_table_count}" "legacy 只有一张 inspection_record"
assert_greater_or_equal "6" "${legacy_rows}" "legacy 至少包含六条跨租户样例"

verify_shard "ds0（偶数租户）" \
  "ds0-primary" "ds0-replica" "asset_ds_0" "0" "100" "200"
verify_shard "ds1（奇数租户）" \
  "ds1-primary" "ds1-replica" "asset_ds_1" "1" "101" "201"

echo
pass "季度槽位、库表落点、日期边界、主键和主从数据全部通过验收"
