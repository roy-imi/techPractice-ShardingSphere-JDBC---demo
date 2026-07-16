#!/usr/bin/env bash
# ==============================================================================
# 四张固定季度表的安全轮转教学脚本
# ==============================================================================
#
# 状态机：
#   EMPTY --activate--> ACTIVE --expire--> EXPIRED --purge--> EMPTY
#
# 推荐操作顺序（以 2026Q3 切换到 2026Q4 为例）：
#   1. ./scripts/quarter-rollover.sh status
#   2. ./scripts/quarter-rollover.sh prepare 2026Q4
#   3. ./scripts/quarter-rollover.sh activate 2026Q4
#   4. 上一步默认只是 dry-run；复核后追加 --execute，再执行 release-check，
#      通过后才允许发布平台更新 CURRENT_QUARTER 并滚动重启应用。
#   5. ./scripts/quarter-rollover.sh expire 2026Q1 --execute
#   6. 至少等待 cleanup_after，再执行带完整人工确认的 purge。
#
# 安全边界：
#   - status / prepare / release-check 永远只读；activate / expire / purge 默认也只 dry-run；
#   - 脚本不修改应用配置、不重启应用，也不会在未确认时执行 TRUNCATE；
#   - 只有 purge 同时提供 --execute、--confirm-quarter、归档确认、对账确认、
#     流量栅栏确认和可恢复备份确认，且数据库状态通过全部检查后，才会清空；
#   - YYYYQn 会经过严格白名单校验，表名和 schema 名只由固定常量拼接，避免 SQL 注入。

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "${SCRIPT_DIR}/_common.sh"

readonly CLEANUP_GRACE_DAYS=3
readonly -a PRIMARY_SERVICES=("ds0-primary" "ds1-primary")
readonly -a DATABASE_NAMES=("asset_ds_0" "asset_ds_1")

execute_requested=false
archive_confirmed=false
reconciliation_confirmed=false
traffic_fenced_confirmed=false
backup_confirmed=false
confirm_quarter=""

usage() {
  cat <<'USAGE'
用法：
  ./scripts/quarter-rollover.sh status
  ./scripts/quarter-rollover.sh prepare  YYYYQn
  ./scripts/quarter-rollover.sh activate YYYYQn [--execute]
  ./scripts/quarter-rollover.sh release-check YYYYQn
  ./scripts/quarter-rollover.sh expire   YYYYQn [--execute]
  ./scripts/quarter-rollover.sh purge    YYYYQn [--execute]
      [--confirm-quarter=YYYYQn]
      [--archive-confirmed]
      [--reconciliation-confirmed]
      [--traffic-fenced-confirmed]
      [--backup-confirmed]

说明：
  status     查看两个主库的槽位、行数及 cleanup_after。
  prepare    检查目标固定槽位为 EMPTY、表为空且四张表结构一致。
  activate   将新季度绑定到对应槽位并标记 ACTIVE；默认只打印计划。
  release-check 在发布 CURRENT_QUARTER 前校验槽位绑定、数据归属及复制状态。
  expire     将最老热季度标记 EXPIRED，cleanup_after 设置为 3 天后。
  purge      保护期结束后清空过期表并将槽位恢复 EMPTY；执行条件最严格。

purge 真正执行示例：
  ./scripts/quarter-rollover.sh purge 2026Q1 --execute \
    --confirm-quarter=2026Q1 --archive-confirmed --reconciliation-confirmed \
    --traffic-fenced-confirmed --backup-confirmed
USAGE
}

# 只接受四位年份和 Q1~Q4。该检查必须发生在季度值进入任何 SQL 之前。
validate_quarter() {
  local value="$1"

  if [[ ! "${value}" =~ ^[0-9]{4}Q[1-4]$ ]]; then
    fail "季度格式非法：${value}；必须严格使用 YYYYQ1 ~ YYYYQ4"
  fi
}

quarter_slot() {
  local value="$1"
  printf '%s' "${value:5:1}"
}

# 将 YYYYQn 转为可连续比较的整数，便于检查跨年季度是否紧邻。
quarter_ordinal() {
  local value="$1"
  local year="${value:0:4}"
  local quarter="${value:5:1}"
  printf '%d' "$((10#${year} * 4 + 10#${quarter} - 1))"
}

# 把连续季度序号还原为 YYYYQn，供发布门禁计算 current + previous 2。
quarter_from_ordinal() {
  local ordinal="$1"
  printf '%dQ%d' "$((ordinal / 4))" "$((ordinal % 4 + 1))"
}

expected_table_for_slot() {
  local slot="$1"
  printf 'inspection_record_q%s' "${slot}"
}

# 读取一条不包含 updated_at 的语义快照。updated_at 在两个独立 MySQL 实例上可能
# 存在毫秒差，不应把无业务意义的时钟差误判为元数据不一致。
read_slot_snapshot() {
  local service="$1"
  local database_name="$2"
  local slot="$3"

  run_mysql "${service}" "
    SELECT CONCAT_WS('|',
      slot_no,
      physical_table,
      COALESCE(bound_quarter, '-'),
      slot_status,
      COALESCE(DATE_FORMAT(activated_at, '%Y-%m-%d %H:%i:%s.%f'), '-'),
      COALESCE(DATE_FORMAT(expired_at, '%Y-%m-%d %H:%i:%s.%f'), '-'),
      COALESCE(DATE_FORMAT(cleanup_after, '%Y-%m-%d %H:%i:%s.%f'), '-')
    )
    FROM ${database_name}.inspection_quarter_slot
    WHERE slot_no = ${slot}
  "
}

read_all_slot_snapshot() {
  local service="$1"
  local database_name="$2"

  run_mysql "${service}" "
    SELECT GROUP_CONCAT(
      CONCAT_WS('|',
        slot_no,
        physical_table,
        COALESCE(bound_quarter, '-'),
        slot_status,
        COALESCE(DATE_FORMAT(activated_at, '%Y-%m-%d %H:%i:%s.%f'), '-'),
        COALESCE(DATE_FORMAT(expired_at, '%Y-%m-%d %H:%i:%s.%f'), '-'),
        COALESCE(DATE_FORMAT(cleanup_after, '%Y-%m-%d %H:%i:%s.%f'), '-')
      )
      ORDER BY slot_no SEPARATOR '||'
    )
    FROM ${database_name}.inspection_quarter_slot
  "
}

assert_slot_metadata_consistent() {
  local slot="$1"
  local ds0_snapshot
  local ds1_snapshot

  ds0_snapshot="$(read_slot_snapshot "${PRIMARY_SERVICES[0]}" "${DATABASE_NAMES[0]}" "${slot}")"
  ds1_snapshot="$(read_slot_snapshot "${PRIMARY_SERVICES[1]}" "${DATABASE_NAMES[1]}" "${slot}")"

  [[ -n "${ds0_snapshot}" ]] || fail "ds0 未找到槽位 q${slot} 元数据"
  [[ -n "${ds1_snapshot}" ]] || fail "ds1 未找到槽位 q${slot} 元数据"
  [[ "${ds0_snapshot}" == "${ds1_snapshot}" ]] || \
    fail "两个主库的 q${slot} 元数据不一致：ds0=${ds0_snapshot}；ds1=${ds1_snapshot}"
}

assert_all_metadata_consistent() {
  local ds0_snapshot
  local ds1_snapshot

  ds0_snapshot="$(read_all_slot_snapshot "${PRIMARY_SERVICES[0]}" "${DATABASE_NAMES[0]}")"
  ds1_snapshot="$(read_all_slot_snapshot "${PRIMARY_SERVICES[1]}" "${DATABASE_NAMES[1]}")"

  [[ -n "${ds0_snapshot}" ]] || fail "ds0 槽位元数据为空"
  [[ "${ds0_snapshot}" == "${ds1_snapshot}" ]] || \
    fail "两个主库的完整槽位元数据不一致，拒绝继续轮转"
  pass "两个主库的完整槽位元数据一致"
}

# 除比较两个分库外，还检查槽位编号、固定表名及季度后缀绑定，防止 q1 错绑 Q2。
assert_metadata_integrity() {
  local index
  local invalid_count

  for index in 0 1; do
    invalid_count="$(run_mysql "${PRIMARY_SERVICES[index]}" "
      SELECT COUNT(*)
      FROM ${DATABASE_NAMES[index]}.inspection_quarter_slot
      WHERE slot_no NOT BETWEEN 1 AND 4
         OR physical_table <> CONCAT('inspection_record_q', slot_no)
         OR (bound_quarter IS NOT NULL AND (
              bound_quarter NOT REGEXP '^[0-9]{4}Q[1-4]$'
              OR RIGHT(bound_quarter, 1) <> CAST(slot_no AS CHAR)
            ))
    ")"
    [[ "${invalid_count}" == "0" ]] || \
      fail "${DATABASE_NAMES[index]} 存在非法固定槽位映射"
  done
}

read_table_row_count() {
  local service="$1"
  local database_name="$2"
  local table_name="$3"
  run_mysql "${service}" "SELECT COUNT(*) FROM ${database_name}.${table_name}"
}

# 固定槽位会跨年复用，因此不能只检查 QUARTER(record_date)。年份和季度任一不匹配，
# 都说明槽位里混入了上一轮残留或错误写入，发布和清理必须立即阻断。
assert_table_rows_match_quarter() {
  local quarter="$1"
  local year="${quarter:0:4}"
  local quarter_no="${quarter:5:1}"
  local slot
  local table_name
  local index
  local invalid_count

  slot="$(quarter_slot "${quarter}")"
  table_name="$(expected_table_for_slot "${slot}")"

  for index in 0 1; do
    invalid_count="$(run_mysql "${PRIMARY_SERVICES[index]}" "
      SELECT COUNT(*)
      FROM ${DATABASE_NAMES[index]}.${table_name}
      WHERE YEAR(record_date) <> ${year}
         OR QUARTER(record_date) <> ${quarter_no}
    ")"
    [[ "${invalid_count}" == "0" ]] || \
      fail "${DATABASE_NAMES[index]}.${table_name} 有 ${invalid_count} 行不属于 ${quarter}"
  done

  pass "${table_name} 中现有数据全部属于 ${quarter}"
}

# 生成忽略表名和 CHECK 约束名的结构签名。这样 q1~q4 虽然约束名称不同，仍可
# 比较字段、索引、CHECK 表达式、引擎和排序规则是否完全同构。
read_table_structure_signature() {
  local service="$1"
  local database_name="$2"
  local table_name="$3"
  local table_signature
  local column_signature
  local index_signature
  local check_signature

  table_signature="$(run_mysql "${service}" "
    SELECT CONCAT_WS('|', ENGINE, TABLE_COLLATION, ROW_FORMAT)
    FROM information_schema.tables
    WHERE table_schema = '${database_name}' AND table_name = '${table_name}'
  ")"

  column_signature="$(run_mysql "${service}" "
    SELECT CONCAT_WS('|',
      ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
      COALESCE(COLUMN_DEFAULT, '<NULL>'), EXTRA,
      COALESCE(CHARACTER_SET_NAME, '-'), COALESCE(COLLATION_NAME, '-'))
    FROM information_schema.columns
    WHERE table_schema = '${database_name}' AND table_name = '${table_name}'
    ORDER BY ORDINAL_POSITION
  ")"

  index_signature="$(run_mysql "${service}" "
    SELECT CONCAT_WS('|', INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
                     COALESCE(SUB_PART, '-'), INDEX_TYPE)
    FROM information_schema.statistics
    WHERE table_schema = '${database_name}' AND table_name = '${table_name}'
    ORDER BY INDEX_NAME, SEQ_IN_INDEX
  ")"

  check_signature="$(run_mysql "${service}" "
    SELECT check_constraints.CHECK_CLAUSE
    FROM information_schema.table_constraints AS table_constraints
    JOIN information_schema.check_constraints AS check_constraints
      ON check_constraints.CONSTRAINT_SCHEMA = table_constraints.CONSTRAINT_SCHEMA
     AND check_constraints.CONSTRAINT_NAME = table_constraints.CONSTRAINT_NAME
    WHERE table_constraints.TABLE_SCHEMA = '${database_name}'
      AND table_constraints.TABLE_NAME = '${table_name}'
      AND table_constraints.CONSTRAINT_TYPE = 'CHECK'
    ORDER BY check_constraints.CHECK_CLAUSE
  ")"

  [[ -n "${table_signature}" && -n "${column_signature}" ]] || \
    fail "未找到 ${database_name}.${table_name} 或无法读取其结构"

  printf 'TABLE\n%s\nCOLUMNS\n%s\nINDEXES\n%s\nCHECKS\n%s' \
    "${table_signature}" "${column_signature}" "${index_signature}" "${check_signature}"
}

assert_all_quarter_tables_isomorphic() {
  local reference_signature
  local current_signature
  local index
  local slot
  local table_name

  reference_signature="$(read_table_structure_signature \
    "${PRIMARY_SERVICES[0]}" "${DATABASE_NAMES[0]}" "inspection_record_q1")"

  for index in 0 1; do
    for slot in 1 2 3 4; do
      table_name="$(expected_table_for_slot "${slot}")"
      current_signature="$(read_table_structure_signature \
        "${PRIMARY_SERVICES[index]}" "${DATABASE_NAMES[index]}" "${table_name}")"
      [[ "${current_signature}" == "${reference_signature}" ]] || \
        fail "${DATABASE_NAMES[index]}.${table_name} 与季度基准表结构不一致"
    done
  done

  pass "两个主库的 q1~q4 字段、索引、约束和表属性完全同构"
}

read_active_quarters() {
  run_mysql "${PRIMARY_SERVICES[0]}" "
    SELECT GROUP_CONCAT(bound_quarter ORDER BY bound_quarter SEPARATOR ',')
    FROM ${DATABASE_NAMES[0]}.inspection_quarter_slot
    WHERE slot_status = 'ACTIVE'
  "
}

assert_consecutive_quarters() {
  local csv="$1"
  local expected_count="$2"
  local -a quarters=()
  local index
  local previous_ordinal
  local current_ordinal

  IFS=',' read -r -a quarters <<<"${csv}"
  [[ "${#quarters[@]}" == "${expected_count}" ]] || \
    fail "ACTIVE 季度数量异常：期望 ${expected_count}，实际 ${#quarters[@]}（${csv:-无}）"

  for index in "${!quarters[@]}"; do
    validate_quarter "${quarters[index]}"
    current_ordinal="$(quarter_ordinal "${quarters[index]}")"
    if ((index > 0)); then
      [[ "$((current_ordinal - previous_ordinal))" == "1" ]] || \
        fail "ACTIVE 季度不连续：${csv}"
    fi
    previous_ordinal="${current_ordinal}"
  done
}

assert_target_is_next_quarter() {
  local target="$1"
  local active_csv
  local newest
  local target_ordinal
  local newest_ordinal

  active_csv="$(read_active_quarters)"
  assert_consecutive_quarters "${active_csv}" 3
  newest="${active_csv##*,}"
  target_ordinal="$(quarter_ordinal "${target}")"
  newest_ordinal="$(quarter_ordinal "${newest}")"

  [[ "$((target_ordinal - newest_ordinal))" == "1" ]] || \
    fail "目标季度 ${target} 不是当前最新热季度 ${newest} 的下一季度"
}

assert_empty_target_ready() {
  local target="$1"
  local slot
  local table_name
  local snapshot
  local index
  local row_count

  slot="$(quarter_slot "${target}")"
  table_name="$(expected_table_for_slot "${slot}")"

  assert_all_metadata_consistent
  assert_metadata_integrity
  assert_slot_metadata_consistent "${slot}"
  snapshot="$(read_slot_snapshot "${PRIMARY_SERVICES[0]}" "${DATABASE_NAMES[0]}" "${slot}")"

  [[ "${snapshot}" == "${slot}|${table_name}|-|EMPTY|-|-|-" ]] || \
    fail "${target} 对应的 ${table_name} 不是可复用 EMPTY 槽位：${snapshot}"

  for index in 0 1; do
    row_count="$(read_table_row_count \
      "${PRIMARY_SERVICES[index]}" "${DATABASE_NAMES[index]}" "${table_name}")"
    [[ "${row_count}" == "0" ]] || \
      fail "${DATABASE_NAMES[index]}.${table_name} 标记为 EMPTY 但仍有 ${row_count} 行"
  done

  assert_all_quarter_tables_isomorphic
  assert_target_is_next_quarter "${target}"
  pass "${target} 对应槽位 ${table_name} 已为空且可安全启用"
}

show_status() {
  local index

  print_title "季度槽位状态（只读）"
  for index in 0 1; do
    echo "${PRIMARY_SERVICES[index]} / ${DATABASE_NAMES[index]}"
    run_mysql "${PRIMARY_SERVICES[index]}" "
      SELECT CONCAT(
        bound.slot_no, '  ',
        RPAD(bound.physical_table, 22, ' '), '  ',
        RPAD(COALESCE(bound.bound_quarter, '-'), 6, ' '), '  ',
        RPAD(bound.slot_status, 7, ' '), '  rows=', counts.row_count,
        '  cleanup_after=', COALESCE(DATE_FORMAT(bound.cleanup_after, '%Y-%m-%d %H:%i:%s.%f'), '-')
      )
      FROM ${DATABASE_NAMES[index]}.inspection_quarter_slot AS bound
      JOIN (
        SELECT 1 AS slot_no, COUNT(*) AS row_count FROM ${DATABASE_NAMES[index]}.inspection_record_q1
        UNION ALL SELECT 2, COUNT(*) FROM ${DATABASE_NAMES[index]}.inspection_record_q2
        UNION ALL SELECT 3, COUNT(*) FROM ${DATABASE_NAMES[index]}.inspection_record_q3
        UNION ALL SELECT 4, COUNT(*) FROM ${DATABASE_NAMES[index]}.inspection_record_q4
      ) AS counts USING (slot_no)
      ORDER BY bound.slot_no
    "
    echo
  done

  assert_all_metadata_consistent
  assert_metadata_integrity
}

print_dry_run_banner() {
  echo
  echo "[DRY-RUN] 上述检查已通过；未修改数据库。追加 --execute 才会执行状态变更。"
}

# 检查主从复制并等待当前 GTID 检查点追平。既用于变更前阻断，也用于变更后验收。
assert_replication_healthy() {
  print_title "确认两组主从复制健康并追平当前 GTID"
  "${SCRIPT_DIR}/check-replication.sh"
}

# 发布平台在修改 CURRENT_QUARTER 前调用此只读门禁。应用路由算法为了保持纯粹，
# 不会在每次请求时查询运维元数据；因此部署流水线必须先证明数据库槽位已经就绪。
assert_release_ready() {
  local target="$1"
  local active_csv
  local newest
  local target_ordinal
  local offset
  local expected_quarter
  local slot
  local table_name
  local snapshot
  local -a active_quarters=()

  print_title "发布 CURRENT_QUARTER=${target} 前的只读门禁"
  assert_all_metadata_consistent
  assert_metadata_integrity
  assert_all_quarter_tables_isomorphic

  active_csv="$(read_active_quarters)"
  IFS=',' read -r -a active_quarters <<<"${active_csv}"
  if [[ "${#active_quarters[@]}" != "3" && "${#active_quarters[@]}" != "4" ]]; then
    fail "发布前只能有 3 个稳定态 ACTIVE，或 4 个轮转过渡态 ACTIVE；实际为 ${active_csv:-无}"
  fi
  assert_consecutive_quarters "${active_csv}" "${#active_quarters[@]}"
  newest="${active_quarters[${#active_quarters[@]} - 1]}"
  [[ "${newest}" == "${target}" ]] || \
    fail "待发布季度 ${target} 不是最新 ACTIVE 季度 ${newest}"

  # 应用在线窗口固定为 current + previous 2；逐个核对绑定和物理数据年份，
  # 防止 q1 槽位残留上一年度 Q1 时被同名路由误读。
  target_ordinal="$(quarter_ordinal "${target}")"
  for offset in 2 1 0; do
    expected_quarter="$(quarter_from_ordinal "$((target_ordinal - offset))")"
    slot="$(quarter_slot "${expected_quarter}")"
    table_name="$(expected_table_for_slot "${slot}")"
    assert_slot_metadata_consistent "${slot}"
    snapshot="$(read_slot_snapshot \
      "${PRIMARY_SERVICES[0]}" "${DATABASE_NAMES[0]}" "${slot}")"
    [[ "${snapshot}" == "${slot}|${table_name}|${expected_quarter}|ACTIVE|"* ]] || \
      fail "在线季度 ${expected_quarter} 的槽位绑定异常：${snapshot}"
    assert_table_rows_match_quarter "${expected_quarter}"
  done

  assert_replication_healthy
  pass "发布门禁通过：可以发布 CURRENT_QUARTER=${target} 并滚动重启"
}

activate_quarter() {
  local target="$1"
  local slot
  local table_name
  local operation_time
  local index
  local changed_rows

  slot="$(quarter_slot "${target}")"
  table_name="$(expected_table_for_slot "${slot}")"

  print_title "检查新季度 ${target} 的待命槽位"
  assert_empty_target_ready "${target}"

  echo
  echo "计划：将两个主库的 q${slot} 从 EMPTY 绑定为 ${target}/ACTIVE。"
  if [[ "${execute_requested}" != true ]]; then
    print_dry_run_banner
    print_post_activate_instructions "${target}"
    return
  fi

  assert_replication_healthy
  operation_time="$(run_mysql "${PRIMARY_SERVICES[0]}" \
    "SELECT DATE_FORMAT(NOW(3), '%Y-%m-%d %H:%i:%s.%f')")"

  for index in 0 1; do
    changed_rows="$(run_mysql "${PRIMARY_SERVICES[index]}" "
      UPDATE ${DATABASE_NAMES[index]}.inspection_quarter_slot
      SET bound_quarter = '${target}',
          slot_status = 'ACTIVE',
          activated_at = '${operation_time}',
          expired_at = NULL,
          cleanup_after = NULL
      WHERE slot_no = ${slot}
        AND physical_table = '${table_name}'
        AND slot_status = 'EMPTY'
        AND bound_quarter IS NULL;
      SELECT ROW_COUNT();
    ")"
    [[ "${changed_rows}" == "1" ]] || \
      fail "${DATABASE_NAMES[index]} 启用 ${target} 影响行数不是 1，请立即停止并核对双库状态"
  done

  assert_all_metadata_consistent
  assert_replication_healthy
  pass "${target} 已在两个主库标记为 ACTIVE"
  print_post_activate_instructions "${target}"
}

print_post_activate_instructions() {
  local target="$1"
  local active_csv
  local oldest

  active_csv="$(read_active_quarters)"
  oldest="${active_csv%%,*}"

  cat <<INSTRUCTIONS

脚本不会修改应用配置或重启应用。请在发布平台人工完成：
  1. 先执行发布门禁：
       ./scripts/quarter-rollover.sh release-check ${target}
  2. 门禁通过后设置部署变量：CURRENT_QUARTER=${target}
  3. 按平台规范滚动重启资产巡检服务，例如：
       kubectl rollout restart deployment/<asset-inspection-service>
  4. 验证新季度读写路由后，再标记最老季度过期：
       ./scripts/quarter-rollover.sh expire ${oldest} --execute
INSTRUCTIONS
}

expire_quarter() {
  local old_quarter="$1"
  local slot
  local table_name
  local snapshot
  local active_csv
  local oldest
  local operation_time
  local index
  local changed_rows

  slot="$(quarter_slot "${old_quarter}")"
  table_name="$(expected_table_for_slot "${slot}")"

  print_title "检查待过期季度 ${old_quarter}"
  assert_all_metadata_consistent
  assert_metadata_integrity
  assert_slot_metadata_consistent "${slot}"
  snapshot="$(read_slot_snapshot "${PRIMARY_SERVICES[0]}" "${DATABASE_NAMES[0]}" "${slot}")"
  [[ "${snapshot}" == "${slot}|${table_name}|${old_quarter}|ACTIVE|"* ]] || \
    fail "${old_quarter} 当前并非 ACTIVE：${snapshot}"

  active_csv="$(read_active_quarters)"
  assert_consecutive_quarters "${active_csv}" 4
  oldest="${active_csv%%,*}"
  [[ "${oldest}" == "${old_quarter}" ]] || \
    fail "只能过期最老 ACTIVE 季度 ${oldest}，不能过期 ${old_quarter}"

  echo "计划：将 ${old_quarter}/${table_name} 标记 EXPIRED，最早清理时间为执行时刻 + ${CLEANUP_GRACE_DAYS} 天。"
  if [[ "${execute_requested}" != true ]]; then
    print_dry_run_banner
    return
  fi

  assert_replication_healthy
  operation_time="$(run_mysql "${PRIMARY_SERVICES[0]}" \
    "SELECT DATE_FORMAT(NOW(3), '%Y-%m-%d %H:%i:%s.%f')")"

  for index in 0 1; do
    changed_rows="$(run_mysql "${PRIMARY_SERVICES[index]}" "
      UPDATE ${DATABASE_NAMES[index]}.inspection_quarter_slot
      SET slot_status = 'EXPIRED',
          expired_at = '${operation_time}',
          cleanup_after = DATE_ADD('${operation_time}', INTERVAL ${CLEANUP_GRACE_DAYS} DAY)
      WHERE slot_no = ${slot}
        AND physical_table = '${table_name}'
        AND bound_quarter = '${old_quarter}'
        AND slot_status = 'ACTIVE';
      SELECT ROW_COUNT();
    ")"
    [[ "${changed_rows}" == "1" ]] || \
      fail "${DATABASE_NAMES[index]} 过期 ${old_quarter} 影响行数不是 1，请立即核对双库状态"
  done

  assert_all_metadata_consistent
  assert_replication_healthy
  pass "${old_quarter} 已标记 EXPIRED；保护期内仍保留物理数据，不执行清空"
  show_status
}

assert_purge_preconditions() {
  local old_quarter="$1"
  local slot
  local table_name
  local snapshot
  local active_csv
  local oldest_active
  local cleanup_ready
  local index

  slot="$(quarter_slot "${old_quarter}")"
  table_name="$(expected_table_for_slot "${slot}")"

  assert_all_metadata_consistent
  assert_metadata_integrity
  assert_slot_metadata_consistent "${slot}"
  snapshot="$(read_slot_snapshot "${PRIMARY_SERVICES[0]}" "${DATABASE_NAMES[0]}" "${slot}")"
  [[ "${snapshot}" == "${slot}|${table_name}|${old_quarter}|EXPIRED|"* ]] || \
    fail "${old_quarter} 当前并非 EXPIRED：${snapshot}"

  active_csv="$(read_active_quarters)"
  assert_consecutive_quarters "${active_csv}" 3
  oldest_active="${active_csv%%,*}"
  [[ "$(quarter_ordinal "${old_quarter}")" -lt "$(quarter_ordinal "${oldest_active}")" ]] || \
    fail "过期季度 ${old_quarter} 不早于当前最老热季度 ${oldest_active}，拒绝清理"

  for index in 0 1; do
    cleanup_ready="$(run_mysql "${PRIMARY_SERVICES[index]}" "
      SELECT IF(cleanup_after IS NOT NULL AND cleanup_after <= NOW(3), 1, 0)
      FROM ${DATABASE_NAMES[index]}.inspection_quarter_slot
      WHERE slot_no = ${slot}
        AND bound_quarter = '${old_quarter}'
        AND slot_status = 'EXPIRED'
    ")"
    [[ "${cleanup_ready}" == "1" ]] || \
      fail "${DATABASE_NAMES[index]} 的 ${old_quarter} 尚未到 cleanup_after，禁止清理"
  done

  assert_table_rows_match_quarter "${old_quarter}"
  pass "${old_quarter} 已过三天保护期，且两个主库元数据一致"
}

purge_quarter() {
  local old_quarter="$1"
  local slot
  local table_name
  local index
  local row_count
  local changed_rows

  slot="$(quarter_slot "${old_quarter}")"
  table_name="$(expected_table_for_slot "${slot}")"

  print_title "检查过期季度 ${old_quarter} 的清理条件"
  assert_purge_preconditions "${old_quarter}"

  for index in 0 1; do
    row_count="$(read_table_row_count \
      "${PRIMARY_SERVICES[index]}" "${DATABASE_NAMES[index]}" "${table_name}")"
    echo "${DATABASE_NAMES[index]}.${table_name} 待清理行数：${row_count}"
  done

  if [[ "${execute_requested}" != true ]]; then
    echo
    echo "[DRY-RUN] 未执行 TRUNCATE，也未修改槽位元数据。"
    echo "真正执行必须同时提供："
    echo "  --execute --confirm-quarter=${old_quarter} --archive-confirmed --reconciliation-confirmed \\"
    echo "  --traffic-fenced-confirmed --backup-confirmed"
    return
  fi

  [[ "${confirm_quarter}" == "${old_quarter}" ]] || \
    fail "缺少或错误的 --confirm-quarter=${old_quarter}"
  [[ "${archive_confirmed}" == true ]] || \
    fail "必须由负责人确认冷数据归档完成，并显式传入 --archive-confirmed"
  [[ "${reconciliation_confirmed}" == true ]] || \
    fail "必须由负责人确认行数/校验和对账完成，并显式传入 --reconciliation-confirmed"
  [[ "${traffic_fenced_confirmed}" == true ]] || \
    fail "必须确认旧实例、定时任务、队列消费者和长事务已停止访问，并传入 --traffic-fenced-confirmed"
  [[ "${backup_confirmed}" == true ]] || \
    fail "必须确认存在经过恢复演练的可用备份，并显式传入 --backup-confirmed"

  assert_replication_healthy

  echo "即将不可逆清空两个主库中的 ${table_name}；确认季度=${confirm_quarter}。"
  for index in 0 1; do
    # TRUNCATE 在 MySQL 中会隐式提交，无法和后续 UPDATE 组成跨实例原子事务。
    # 因而先完成全部前置检查，再逐库执行；任一步失败时脚本立即中止并要求人工核对。
    changed_rows="$(run_mysql "${PRIMARY_SERVICES[index]}" "
      TRUNCATE TABLE ${DATABASE_NAMES[index]}.${table_name};
      UPDATE ${DATABASE_NAMES[index]}.inspection_quarter_slot
      SET bound_quarter = NULL,
          slot_status = 'EMPTY',
          activated_at = NULL,
          expired_at = NULL,
          cleanup_after = NULL
      WHERE slot_no = ${slot}
        AND physical_table = '${table_name}'
        AND bound_quarter = '${old_quarter}'
        AND slot_status = 'EXPIRED';
      SELECT ROW_COUNT();
    ")"
    [[ "${changed_rows}" == "1" ]] || \
      fail "${DATABASE_NAMES[index]} 清空后更新槽位影响行数不是 1；停止操作并立即核对"
  done

  assert_all_metadata_consistent
  for index in 0 1; do
    row_count="$(read_table_row_count \
      "${PRIMARY_SERVICES[index]}" "${DATABASE_NAMES[index]}" "${table_name}")"
    [[ "${row_count}" == "0" ]] || \
      fail "${DATABASE_NAMES[index]}.${table_name} 清理后仍有 ${row_count} 行"
  done

  assert_replication_healthy
  pass "${old_quarter} 已清理；q${slot} 已恢复为 EMPTY，可供下一年度同季度复用"
  show_status
}

action="${1:-}"
if [[ -z "${action}" || "${action}" == "-h" || "${action}" == "--help" ]]; then
  usage
  [[ -n "${action}" ]] && exit 0
  exit 2
fi
shift

case "${action}" in
  status|prepare|activate|release-check|expire|purge)
    ;;
  *)
    usage >&2
    fail "未知操作：${action}"
    ;;
esac

target_quarter=""
if [[ "${action}" != "status" ]]; then
  target_quarter="${1:-}"
  [[ -n "${target_quarter}" ]] || {
    usage >&2
    fail "${action} 必须指定 YYYYQn"
  }
  validate_quarter "${target_quarter}"
  shift
fi

while (($# > 0)); do
  case "$1" in
    --execute)
      execute_requested=true
      ;;
    --archive-confirmed)
      archive_confirmed=true
      ;;
    --reconciliation-confirmed)
      reconciliation_confirmed=true
      ;;
    --traffic-fenced-confirmed)
      traffic_fenced_confirmed=true
      ;;
    --backup-confirmed)
      backup_confirmed=true
      ;;
    --confirm-quarter=*)
      confirm_quarter="${1#*=}"
      validate_quarter "${confirm_quarter}"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      fail "未知参数：$1"
      ;;
  esac
  shift
done

if [[ "${action}" == "status" || "${action}" == "prepare" || "${action}" == "release-check" ]]; then
  [[ "${execute_requested}" == false ]] || fail "${action} 是只读操作，不接受 --execute"
fi

if [[ -n "${confirm_quarter}" && "${action}" != "purge" ]]; then
  fail "--confirm-quarter 只允许用于 purge"
fi
if { [[ "${archive_confirmed}" == true ]] \
  || [[ "${reconciliation_confirmed}" == true ]] \
  || [[ "${traffic_fenced_confirmed}" == true ]] \
  || [[ "${backup_confirmed}" == true ]]; } \
  && [[ "${action}" != "purge" ]]; then
  fail "归档/对账/流量栅栏/备份确认标志只允许用于 purge"
fi

require_docker

case "${action}" in
  status)
    show_status
    ;;
  prepare)
    print_title "准备 ${target_quarter}（只读检查）"
    assert_empty_target_ready "${target_quarter}"
    pass "prepare 完成；数据库未发生任何变更"
    ;;
  activate)
    activate_quarter "${target_quarter}"
    ;;
  release-check)
    assert_release_ready "${target_quarter}"
    ;;
  expire)
    expire_quarter "${target_quarter}"
    ;;
  purge)
    purge_quarter "${target_quarter}"
    ;;
esac
