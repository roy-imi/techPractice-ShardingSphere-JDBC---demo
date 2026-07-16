#!/usr/bin/env bash
# 暂停副本 SQL 应用线程，用来模拟复制延迟并观察“写后立即读”一致性问题。
# IO 接收线程仍运行，因此主库 binlog 会继续下载到 relay log，不会故意断开连接。

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "${SCRIPT_DIR}/_common.sh"

require_docker

target="${1:-all}"
case "${target}" in
  ds0) replicas=(ds0-replica) ;;
  ds1) replicas=(ds1-replica) ;;
  all) replicas=(ds0-replica ds1-replica) ;;
  *)
    echo "用法：$0 [ds0|ds1|all]" >&2
    exit 2
    ;;
esac

for replica in "${replicas[@]}"; do
  print_title "暂停 ${replica} 的 SQL_THREAD"
  run_mysql "${replica}" "STOP REPLICA SQL_THREAD"
  status="$(compose exec -T "${replica}" env MYSQL_PWD="${ROOT_PASSWORD}" \
    mysql --host=127.0.0.1 --user=root --execute="SHOW REPLICA STATUS\G")"
  awk '$1 == "Replica_IO_Running:" || $1 == "Replica_SQL_Running:" {print}' <<<"${status}"
  pass "${replica} 已暂停应用 relay log"
done

echo
echo "现在可向主库写入一条记录，再比较普通读（可能走副本）与事务/Hint 强制主库读。"
echo "实验结束后务必执行：./scripts/resume-replication.sh ${target}"
