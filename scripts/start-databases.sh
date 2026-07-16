#!/usr/bin/env bash
# 启动五个 MySQL 实例，并等待一次性 replication-setup 配置成功。

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/_common.sh
source "${SCRIPT_DIR}/_common.sh"

require_docker

print_title "启动 legacy + 两组 MySQL 主从"
compose up --detach --remove-orphans

# docker compose up -d 不会等待一次性容器退出，因此轮询它的真实 State/ExitCode。
# 最多等待 240 秒；期间每 2 秒检查一次，不使用永不结束的日志跟随。
deadline=$((SECONDS + 240))
while ((SECONDS < deadline)); do
  setup_id="$(compose ps --all --quiet replication-setup)"

  if [[ -n "${setup_id}" ]]; then
    setup_state="$(docker inspect --format '{{.State.Status}}' "${setup_id}")"

    if [[ "${setup_state}" == "exited" ]]; then
      setup_exit_code="$(docker inspect --format '{{.State.ExitCode}}' "${setup_id}")"
      if [[ "${setup_exit_code}" == "0" ]]; then
        pass "replication-setup 已正常完成"
        break
      fi

      compose logs replication-setup >&2
      fail "replication-setup 异常退出，exitCode=${setup_exit_code}"
      exit 1
    fi
  fi

  echo "等待数据库健康检查与 GTID 初始化完成（当前 setup=${setup_state:-created}）..."
  sleep 2
done

if ((SECONDS >= deadline)); then
  compose logs replication-setup >&2
  fail "等待 replication-setup 超过 240 秒"
  exit 1
fi

compose ps --all

# 启动脚本最后做一次语义检查；能启动不代表复制线程一定健康。
"${SCRIPT_DIR}/check-replication.sh"

echo
echo "数据库环境已就绪。下一步可执行："
echo "  ./scripts/verify-physical-data.sh"
echo "  mvn spring-boot:run -Dspring-boot.run.profiles=product"
