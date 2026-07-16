-- ============================================================================
-- 改造前：单库单表基线
-- ============================================================================
-- Docker 官方 MySQL 镜像只会在数据卷“第一次初始化”时执行本文件。
-- 如果修改了这里却看不到效果，需要明确删除卷后重建：docker compose down -v。

-- MySQL CLI 的默认客户端字符集受镜像/环境影响；显式声明可防止中文种子被按 latin1 写成乱码。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS inspection_record (
    -- 改造前只有一个写库，可依赖 AUTO_INCREMENT；迁移时仍保留这些旧 ID，避免外部引用重写。
    -- 改造成多库后不能再让各库独立自增，否则会碰撞，因此 product 形态改用 Snowflake。
    id                    BIGINT          NOT NULL AUTO_INCREMENT COMMENT '旧系统单库自增主键',
    -- tenant_id 是未来的分库键；即使旧系统未分库，也必须先补齐且禁止为 NULL。
    tenant_id             BIGINT          NOT NULL COMMENT '租户 ID，未来按 MOD 2 分库',
    -- request_id 用于接口重试幂等；旧单表可以用唯一索引实现租户内全局唯一。
    request_id            VARCHAR(64)     NOT NULL COMMENT '客户端请求幂等号',
    asset_id              BIGINT          NOT NULL COMMENT '资产 ID',
    asset_code            VARCHAR(64)     NOT NULL COMMENT '资产编码快照',
    inspection_point_id   BIGINT          NOT NULL COMMENT '巡检点 ID',
    inspection_point_name VARCHAR(128)    NOT NULL COMMENT '巡检点名称快照',
    -- record_date 是未来的分表键；单独保存 DATE 比每次对 DATETIME 做函数计算更利于索引。
    record_date           DATE            NOT NULL COMMENT '业务记录日期，未来按自然季度分表',
    inspected_at          DATETIME(3)     NOT NULL COMMENT '实际巡检时间',
    status                VARCHAR(20)     NOT NULL COMMENT 'NORMAL/ABNORMAL/REPAIRED',
    measured_value        DECIMAL(18,4)       NULL COMMENT '测量值',
    unit                  VARCHAR(32)         NULL COMMENT '计量单位',
    result_description    VARCHAR(500)        NULL COMMENT '结果说明',
    inspector_id          BIGINT          NOT NULL COMMENT '巡检人员 ID',
    version               INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at            DATETIME(3)     NOT NULL COMMENT '创建时间',
    updated_at            DATETIME(3)     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_request (tenant_id, request_id),
    KEY idx_tenant_date_id (tenant_id, record_date, id),
    KEY idx_tenant_status_date (tenant_id, status, record_date),
    KEY idx_tenant_asset_date (tenant_id, asset_id, record_date),
    CONSTRAINT chk_legacy_version_nonnegative CHECK (version >= 0),
    CONSTRAINT chk_legacy_status CHECK (status IN ('NORMAL', 'ABNORMAL', 'REPAIRED'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='改造前的巡检记录单表';

-- 六条样例故意混合偶数/奇数租户和不同日期，方便观察迁移后的季度落点。
-- ON DUPLICATE KEY UPDATE 让初始化 SQL 在手工重放时仍保持幂等。
INSERT INTO inspection_record (
    id, tenant_id, request_id, asset_id, asset_code,
    inspection_point_id, inspection_point_name, record_date, inspected_at,
    status, measured_value, unit, result_description, inspector_id,
    version, created_at, updated_at
) VALUES
    (8000000000000001, 2, 'legacy-t2-202601-001', 2001, 'PUMP-DS0-01',
     2101, '出口压力', '2026-01-15', '2026-01-15 09:00:00.000',
     'NORMAL', 1.2500, 'MPa', '压力正常', 9001,
     0, '2026-01-15 09:00:01.000', '2026-01-15 09:00:01.000'),
    (8000000000000002, 2, 'legacy-t2-202602-001', 2001, 'PUMP-DS0-01',
     2102, '轴承温度', '2026-02-18', '2026-02-18 10:20:00.000',
     'ABNORMAL', 86.3000, '℃', '温度偏高，已创建维修单', 9002,
     0, '2026-02-18 10:20:02.000', '2026-02-18 10:20:02.000'),
    (8000000000000003, 2, 'legacy-t2-202603-001', 2002, 'VALVE-DS0-02',
     2201, '阀位反馈', '2026-03-08', '2026-03-08 08:30:00.000',
     'REPAIRED', 100.0000, '%', '复检通过', 9003,
     1, '2026-03-08 08:30:03.000', '2026-03-09 14:00:00.000'),
    (8000000000000004, 3, 'legacy-t3-202601-001', 3001, 'BOILER-DS1-01',
     3101, '炉膛温度', '2026-01-21', '2026-01-21 11:00:00.000',
     'NORMAL', 920.0000, '℃', '运行平稳', 9101,
     0, '2026-01-21 11:00:01.000', '2026-01-21 11:00:01.000'),
    (8000000000000005, 3, 'legacy-t3-202602-001', 3002, 'FAN-DS1-02',
     3201, '振动速度', '2026-02-25', '2026-02-25 15:40:00.000',
     'ABNORMAL', 8.9000, 'mm/s', '超过告警阈值', 9102,
     0, '2026-02-25 15:40:02.000', '2026-02-25 15:40:02.000'),
    (8000000000000006, 3, 'legacy-t3-202604-001', 3002, 'FAN-DS1-02',
     3201, '振动速度', '2026-04-02', '2026-04-02 07:50:00.000',
     'REPAIRED', 3.1000, 'mm/s', '更换轴承后复检正常', 9103,
     1, '2026-04-02 07:50:01.000', '2026-04-02 08:30:00.000')
ON DUPLICATE KEY UPDATE
    updated_at = VALUES(updated_at);
