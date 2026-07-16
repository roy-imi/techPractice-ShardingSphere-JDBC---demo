-- ============================================================================
-- 固定季度槽位环境的最小种子数据
-- ============================================================================
-- 两个主库都会执行本文件，因此通过 DATABASE() 条件把偶数租户放入 ds0、奇数租户
-- 放入 ds1。初始化状态模拟 2026Q3 在线期间：Q1/Q2/Q3 为三个季度槽位，Q4 为空闲
-- 待命槽位。进入 2026Q4 时，轮转脚本会先启用 q4，再把最老的 2026Q1 标记过期。
--
-- 注意：Docker 官方 MySQL 镜像只在空数据卷首次初始化时执行本文件；它不是生产环境
-- 的日常补数脚本。生产轮转请使用 scripts/quarter-rollover.sh 的显式安全流程。

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 将前三个物理槽位绑定到 2026 年前三季度。WHERE 条件防止人工误重放时覆盖已轮转状态。
UPDATE inspection_quarter_slot
SET bound_quarter = CONCAT('2026Q', slot_no),
    slot_status = 'ACTIVE',
    activated_at = CASE slot_no
      WHEN 1 THEN '2026-01-01 00:00:00.000'
      WHEN 2 THEN '2026-04-01 00:00:00.000'
      WHEN 3 THEN '2026-07-01 00:00:00.000'
    END,
    expired_at = NULL,
    cleanup_after = NULL
WHERE slot_no IN (1, 2, 3)
  AND slot_status = 'EMPTY'
  AND bound_quarter IS NULL;

-- ------------------------------ ds0：偶数租户 ------------------------------
INSERT INTO inspection_record_q1 (
    id, tenant_id, request_id, asset_id, asset_code,
    inspection_point_id, inspection_point_name, record_date, inspected_at,
    status, measured_value, unit, result_description, inspector_id,
    version, created_at, updated_at
)
SELECT
    8100000000000001, 2, 'seed-ds0-2026q1-001', 2001, 'PUMP-DS0-01',
    2101, '出口压力', '2026-01-16', '2026-01-16 09:10:00.000',
    'NORMAL', 1.2800, 'MPa', 'ds0 第一季度种子数据', 9001,
    0, '2026-01-16 09:10:01.000', '2026-01-16 09:10:01.000'
FROM DUAL
WHERE DATABASE() = 'asset_ds_0'
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

INSERT INTO inspection_record_q2 (
    id, tenant_id, request_id, asset_id, asset_code,
    inspection_point_id, inspection_point_name, record_date, inspected_at,
    status, measured_value, unit, result_description, inspector_id,
    version, created_at, updated_at
)
SELECT
    8100000000000002, 2, 'seed-ds0-2026q2-001', 2002, 'VALVE-DS0-02',
    2201, '阀位反馈', '2026-05-12', '2026-05-12 14:00:00.000',
    'ABNORMAL', 72.0000, '%', 'ds0 第二季度种子数据', 9002,
    0, '2026-05-12 14:00:01.000', '2026-05-12 14:00:01.000'
FROM DUAL
WHERE DATABASE() = 'asset_ds_0'
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

INSERT INTO inspection_record_q3 (
    id, tenant_id, request_id, asset_id, asset_code,
    inspection_point_id, inspection_point_name, record_date, inspected_at,
    status, measured_value, unit, result_description, inspector_id,
    version, created_at, updated_at
)
SELECT
    8100000000000003, 2, 'seed-ds0-2026q3-001', 2003, 'MOTOR-DS0-03',
    2301, '轴承温度', '2026-07-08', '2026-07-08 10:20:00.000',
    'NORMAL', 61.5000, '℃', 'ds0 第三季度种子数据', 9003,
    0, '2026-07-08 10:20:01.000', '2026-07-08 10:20:01.000'
FROM DUAL
WHERE DATABASE() = 'asset_ds_0'
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

-- ------------------------------ ds1：奇数租户 ------------------------------
INSERT INTO inspection_record_q1 (
    id, tenant_id, request_id, asset_id, asset_code,
    inspection_point_id, inspection_point_name, record_date, inspected_at,
    status, measured_value, unit, result_description, inspector_id,
    version, created_at, updated_at
)
SELECT
    8200000000000001, 3, 'seed-ds1-2026q1-001', 3001, 'BOILER-DS1-01',
    3101, '炉膛温度', '2026-03-20', '2026-03-20 11:30:00.000',
    'NORMAL', 918.0000, '℃', 'ds1 第一季度种子数据', 9101,
    0, '2026-03-20 11:30:01.000', '2026-03-20 11:30:01.000'
FROM DUAL
WHERE DATABASE() = 'asset_ds_1'
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

INSERT INTO inspection_record_q2 (
    id, tenant_id, request_id, asset_id, asset_code,
    inspection_point_id, inspection_point_name, record_date, inspected_at,
    status, measured_value, unit, result_description, inspector_id,
    version, created_at, updated_at
)
SELECT
    8200000000000002, 3, 'seed-ds1-2026q2-001', 3002, 'FAN-DS1-02',
    3201, '振动速度', '2026-04-03', '2026-04-03 08:00:00.000',
    'REPAIRED', 3.2000, 'mm/s', 'ds1 第二季度种子数据', 9102,
    1, '2026-04-03 08:00:01.000', '2026-04-03 08:30:00.000'
FROM DUAL
WHERE DATABASE() = 'asset_ds_1'
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

INSERT INTO inspection_record_q3 (
    id, tenant_id, request_id, asset_id, asset_code,
    inspection_point_id, inspection_point_name, record_date, inspected_at,
    status, measured_value, unit, result_description, inspector_id,
    version, created_at, updated_at
)
SELECT
    8200000000000003, 3, 'seed-ds1-2026q3-001', 3003, 'COMPRESSOR-DS1-03',
    3301, '入口温度', '2026-09-18', '2026-09-18 13:40:00.000',
    'ABNORMAL', 79.8000, '℃', 'ds1 第三季度种子数据', 9103,
    0, '2026-09-18 13:40:01.000', '2026-09-18 13:40:01.000'
FROM DUAL
WHERE DATABASE() = 'asset_ds_1'
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
