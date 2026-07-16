-- ============================================================================
-- 创建四张固定季度槽位表与生命周期元数据
-- ============================================================================
-- 使用 SQL 而不是可执行 .sh，是为了兼容 macOS Docker Desktop：只读 bind mount
-- 可能带 noexec，MySQL 官方入口直接执行挂载脚本时会报 bad interpreter。
-- 官方入口会把本文件交给 mysql 客户端，因此不依赖宿主机脚本执行权限。
--
-- 物理映射永久固定：Q1 -> q1、Q2 -> q2、Q3 -> q3、Q4 -> q4。
-- 表名不带年份；inspection_quarter_slot 负责记录每个槽位当前绑定的 YYYYQn。

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 先创建一个同构模板，再用 CREATE TABLE ... LIKE 复制四份。
-- CHECK 约束不显式命名，让 MySQL 为每张目标表生成不冲突的约束名。
CREATE TABLE IF NOT EXISTS inspection_record_quarter_template (
    id                    BIGINT          NOT NULL COMMENT 'Snowflake 全局主键',
    tenant_id             BIGINT          NOT NULL COMMENT '可选分库键：tenant_id MOD 2',
    request_id            VARCHAR(64)     NOT NULL COMMENT '租户内请求幂等号',
    asset_id              BIGINT          NOT NULL COMMENT '资产 ID',
    asset_code            VARCHAR(64)     NOT NULL COMMENT '资产编码快照',
    inspection_point_id   BIGINT          NOT NULL COMMENT '巡检点 ID',
    inspection_point_name VARCHAR(128)    NOT NULL COMMENT '巡检点名称快照',
    record_date           DATE            NOT NULL COMMENT '分表键：按自然季度落入固定槽位',
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
    -- 该唯一约束只在一个季度槽位内生效；跨季度幂等需要全局幂等表或缓存。
    UNIQUE KEY uk_tenant_request (tenant_id, request_id),
    -- 高频列表、状态统计和资产历史都以 tenant_id 开头，兼顾租户隔离与索引选择性。
    KEY idx_tenant_date_id (tenant_id, record_date, id),
    KEY idx_tenant_status_date (tenant_id, status, record_date),
    KEY idx_tenant_asset_date (tenant_id, asset_id, record_date),
    CHECK (version >= 0),
    CHECK (status IN ('NORMAL', 'ABNORMAL', 'REPAIRED'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='固定季度巡检记录表结构模板';

-- 四张表从同一模板复制，保证列、索引与约束完全同构。
CREATE TABLE IF NOT EXISTS inspection_record_q1 LIKE inspection_record_quarter_template;
CREATE TABLE IF NOT EXISTS inspection_record_q2 LIKE inspection_record_quarter_template;
CREATE TABLE IF NOT EXISTS inspection_record_q3 LIKE inspection_record_quarter_template;
CREATE TABLE IF NOT EXISTS inspection_record_q4 LIKE inspection_record_quarter_template;

-- 修改物理表注释，方便 DBA 在元数据平台识别这些表是可轮转复用的槽位。
ALTER TABLE inspection_record_q1 COMMENT='自然季度 Q1 固定复用槽位';
ALTER TABLE inspection_record_q2 COMMENT='自然季度 Q2 固定复用槽位';
ALTER TABLE inspection_record_q3 COMMENT='自然季度 Q3 固定复用槽位';
ALTER TABLE inspection_record_q4 COMMENT='自然季度 Q4 固定复用槽位';

-- 模板不是业务表，复制完成后立即删除，避免被验收脚本或业务人员误用。
DROP TABLE inspection_record_quarter_template;

-- 运维元数据把“固定物理槽位”与“当前承载的年份季度”显式绑定。
-- EMPTY：表必须为空，可供下一轮使用；ACTIVE：在线热数据；
-- EXPIRED：退出在线路由，但仍处于默认三天的延迟清理保护期。
CREATE TABLE IF NOT EXISTS inspection_quarter_slot (
    slot_no         TINYINT UNSIGNED NOT NULL COMMENT '固定槽位编号 1~4',
    physical_table  VARCHAR(64)      NOT NULL COMMENT '固定物理表名',
    bound_quarter   CHAR(6)              NULL COMMENT '当前绑定季度，格式 YYYYQn',
    slot_status     VARCHAR(16)      NOT NULL DEFAULT 'EMPTY' COMMENT 'EMPTY/ACTIVE/EXPIRED',
    activated_at    DATETIME(3)          NULL COMMENT '本次季度启用时间',
    expired_at      DATETIME(3)          NULL COMMENT '退出在线路由并标记过期时间',
    cleanup_after   DATETIME(3)          NULL COMMENT '最早允许清理的时间',
    updated_at      DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                      ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (slot_no),
    UNIQUE KEY uk_physical_table (physical_table),
    UNIQUE KEY uk_bound_quarter (bound_quarter),
    CHECK (slot_no BETWEEN 1 AND 4),
    CHECK (slot_status IN ('EMPTY', 'ACTIVE', 'EXPIRED')),
    CHECK (
      (slot_status = 'EMPTY' AND bound_quarter IS NULL)
      OR (slot_status IN ('ACTIVE', 'EXPIRED') AND bound_quarter IS NOT NULL)
    )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='固定季度表的轮转与延迟清理控制元数据';

-- 初始化脚本可安全重放：已存在的槽位只校正固定表名，不覆盖运行中的季度绑定。
INSERT INTO inspection_quarter_slot (slot_no, physical_table, bound_quarter, slot_status)
VALUES
    (1, 'inspection_record_q1', NULL, 'EMPTY'),
    (2, 'inspection_record_q2', NULL, 'EMPTY'),
    (3, 'inspection_record_q3', NULL, 'EMPTY'),
    (4, 'inspection_record_q4', NULL, 'EMPTY')
ON DUPLICATE KEY UPDATE physical_table = VALUES(physical_table);
