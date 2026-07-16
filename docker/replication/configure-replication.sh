#!/usr/bin/env bash
# ============================================================================
# 为 ds0、ds1 建立 MySQL 8 GTID 主从复制。
# ============================================================================
# 为什么用 GTID + SOURCE_AUTO_POSITION：
# 1. 不需要把易变化的 binlog 文件名/位置写死在脚本中；
# 2. 副本重启后可以依据已执行 GTID 集合继续追赶；
# 3. 本脚本可重复执行，RESET REPLICA ALL 不会清空 gtid_executed 或业务数据。
#
# 注意：这只是本机教学环境的自动配置。生产故障切换、选主、脑裂防护与复制延迟
# 告警应由 MySQL 高可用方案/云数据库承担，ShardingSphere-JDBC 本身不会搭建复制。

set -Eeuo pipefail

readonly ROOT_PASSWORD="${ROOT_PASSWORD:?ROOT_PASSWORD 未设置}"
readonly REPLICATION_PASSWORD="${REPLICATION_PASSWORD:?REPLICATION_PASSWORD 未设置}"
readonly APP_PASSWORD="${APP_PASSWORD:?APP_PASSWORD 未设置}"

# root 命令只在 Compose 私有网络内使用；MYSQL_PWD 避免密码出现在 ps 参数中。
mysql_root() {
  local host="$1"
  local sql="$2"

  MYSQL_PWD="${ROOT_PASSWORD}" mysql \
    --host="${host}" \
    --port=3306 \
    --user=root \
    --connect-timeout=5 \
    --batch \
    --raw \
    --skip-column-names \
    --execute="${sql}"
}

wait_for_mysql() {
  local host="$1"
  local attempt

  for ((attempt = 1; attempt <= 60; attempt++)); do
    if mysql_root "${host}" "SELECT 1" >/dev/null 2>&1; then
      return 0
    fi
    echo "等待 ${host}:3306 可连接（${attempt}/60）..."
    sleep 2
  done

  echo "${host}:3306 在超时前仍不可连接" >&2
  return 1
}

wait_for_replica_threads() {
  local replica_host="$1"
  local attempt
  local thread_states
  local io_state
  local sql_state

  for ((attempt = 1; attempt <= 60; attempt++)); do
    # Performance Schema 的两个 SERVICE_STATE 分别对应接收线程与应用线程。
    # 值为 ON 才表示线程正在运行；用明确列查询比依赖 SHOW 输出列序号更稳健。
    thread_states="$(mysql_root "${replica_host}" "
      SELECT CONCAT(
        COALESCE((SELECT SERVICE_STATE
                  FROM performance_schema.replication_connection_status
                  WHERE CHANNEL_NAME = ''), 'MISSING'),
        '|',
        COALESCE((SELECT SERVICE_STATE
                  FROM performance_schema.replication_applier_status
                  WHERE CHANNEL_NAME = ''), 'MISSING')
      )
    ")"
    io_state="${thread_states%%|*}"
    sql_state="${thread_states##*|}"

    if [[ "${io_state}" == "ON" && "${sql_state}" == "ON" ]]; then
      return 0
    fi

    echo "等待 ${replica_host} 复制线程启动（IO=${io_state:-MISSING}, SQL=${sql_state:-MISSING}，${attempt}/60）..."
    sleep 2
  done

  echo "${replica_host} 的复制线程未正常启动" >&2
  MYSQL_PWD="${ROOT_PASSWORD}" mysql \
    --host="${replica_host}" --user=root --execute="SHOW REPLICA STATUS\G" || true
  return 1
}

configure_pair() {
  local label="$1"
  local source_host="$2"
  local replica_host="$3"
  local database_name="$4"
  local source_gtid
  local wait_result

  echo
  echo "========== 配置 ${label}: ${source_host} -> ${replica_host} =========="
  wait_for_mysql "${source_host}"
  wait_for_mysql "${replica_host}"

  # STOP 在第一次配置时可能只返回“尚未初始化”的提示，因此允许这一条单独失败。
  mysql_root "${replica_host}" "STOP REPLICA" >/dev/null 2>&1 || true

  # RESET REPLICA ALL 只删除旧的连接参数和 relay log，不会删除业务表，也不会清空
  # gtid_executed。保留 GTID 是脚本安全重跑而不重复执行事务的关键。
  mysql_root "${replica_host}" "RESET REPLICA ALL"

  mysql_root "${replica_host}" "
    CHANGE REPLICATION SOURCE TO
      SOURCE_HOST='${source_host}',
      SOURCE_PORT=3306,
      SOURCE_USER='replicator',
      SOURCE_PASSWORD='${REPLICATION_PASSWORD}',
      SOURCE_AUTO_POSITION=1,
      GET_SOURCE_PUBLIC_KEY=1;
    START REPLICA;
  "

  wait_for_replica_threads "${replica_host}"

  # 记录“此刻主库已经提交的全部 GTID”，再让副本等待执行完这一集合。
  # 返回 0=追平，1=超时，NULL=参数错误；只有 0 才算初始化成功。
  source_gtid="$(mysql_root "${source_host}" "SELECT @@GLOBAL.gtid_executed")"
  wait_result="$(mysql_root "${replica_host}" \
    "SELECT WAIT_FOR_EXECUTED_GTID_SET('${source_gtid}', 60)")"
  if [[ "${wait_result}" != "0" ]]; then
    echo "${label} 在 60 秒内未追平主库 GTID，返回值=${wait_result}" >&2
    return 1
  fi

  # 官方镜像在副本初始为空时未必会本地创建业务账号。
  # 先等源端 DDL/DCL 重放完，再以 sql_log_bin=0 幂等补齐账号，避免制造本地 GTID。
  # 即便主库的账号已经复制过来，IF NOT EXISTS + ALTER 也能安全执行。
  mysql_root "${replica_host}" "
    SET SESSION sql_log_bin=0;
    CREATE USER IF NOT EXISTS 'asset_app'@'%'
      IDENTIFIED WITH caching_sha2_password BY '${APP_PASSWORD}';
    ALTER USER 'asset_app'@'%'
      IDENTIFIED WITH caching_sha2_password BY '${APP_PASSWORD}';
    GRANT ALL PRIVILEGES ON \`${database_name}\`.* TO 'asset_app'@'%';
    FLUSH PRIVILEGES;
  "

  # SET PERSIST 同时修改当前值并写入 mysqld-auto.cnf，容器重启后仍保持只读。
  # super_read_only 连拥有 SUPER 权限的普通会话也禁止写，复制 SQL 线程不受影响。
  mysql_root "${replica_host}" "
    SET PERSIST read_only=ON;
    SET PERSIST super_read_only=ON;
  "

  echo "${label} 已建立 GTID 复制，并追平初始化数据。"
}

configure_pair "ds0" "ds0-primary" "ds0-replica" "asset_ds_0"
configure_pair "ds1" "ds1-primary" "ds1-replica" "asset_ds_1"

echo
echo "两组复制配置完成。replication-setup 现在以退出码 0 正常结束。"
