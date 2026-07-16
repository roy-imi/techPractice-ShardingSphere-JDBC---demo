package com.example.assetinspection.migration.source;

import com.example.assetinspection.domain.InspectionRecord;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

/** 使用独立 Hikari 连接池读取旧库 inspection_record 单表。 */
public class JdbcLegacyInspectionRecordSource implements LegacyInspectionRecordSource {

    /**
     * 主键游标 SQL。
     *
     * <p>当表有上亿行时，OFFSET 越大需要跳过的行越多；id &gt; ? 可以从主键索引位置继续。
     * ORDER BY id 与游标方向一致，LIMIT 则把单批内存和事务大小限制住。</p>
     */
    private static final String READ_AFTER_ID_SQL =
            "SELECT id, tenant_id, request_id, asset_id, asset_code, "
                    + "inspection_point_id, inspection_point_name, record_date, inspected_at, "
                    + "status, measured_value, unit, result_description, inspector_id, "
                    + "version, created_at, updated_at "
                    + "FROM inspection_record "
                    + "WHERE id > ? "
                    + "ORDER BY id ASC "
                    + "LIMIT ?";

    /** 旧表对账 SQL，同样带 tenant_id 与日期范围，便于使用组合索引。 */
    private static final String COUNT_SQL =
            "SELECT COUNT(*) "
                    + "FROM inspection_record "
                    + "WHERE tenant_id = ? "
                    + "AND record_date >= ? "
                    + "AND record_date < ?";

    // 保存连接池引用，以便应用关闭时主动释放连接。
    private final HikariDataSource dataSource;

    // JdbcTemplate 只绑定旧库连接池，不会经过 ShardingSphere。
    private final JdbcTemplate jdbcTemplate;

    // RowMapper 无状态，可安全复用。
    private final LegacyInspectionRecordRowMapper rowMapper;

    public JdbcLegacyInspectionRecordSource(HikariDataSource dataSource) {
        // 配置类已经把这个 DataSource 标记为只读。
        this.dataSource = dataSource;
        // 所有旧库 SQL 都通过这个模板执行。
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        // 显式构造映射器，保证读取字段与目标写入字段一一对应。
        this.rowMapper = new LegacyInspectionRecordRowMapper();
    }

    @Override
    public List<InspectionRecord> readAfterId(long afterId, int limit) {
        // 参数化查询避免字符串拼接，也便于数据库复用执行计划。
        return jdbcTemplate.query(READ_AFTER_ID_SQL, rowMapper, afterId, limit);
    }

    @Override
    public long count(Long tenantId, LocalDate startDate, LocalDate endDateExclusive) {
        // java.sql.Date 明确表达“只有日期、没有时区”。
        java.sql.Date sqlStartDate = java.sql.Date.valueOf(startDate);
        java.sql.Date sqlEndDate = java.sql.Date.valueOf(endDateExclusive);
        Long count = jdbcTemplate.queryForObject(
                COUNT_SQL,
                Long.class,
                tenantId,
                sqlStartDate,
                sqlEndDate);
        // COUNT(*) 正常不会返回 null；防御性处理让接口契约始终返回基本类型 long。
        return count == null ? 0L : count.longValue();
    }

    @Override
    public void close() {
        // Spring 销毁 Bean 时调用，关闭旧库连接池及其中的物理连接。
        dataSource.close();
    }
}
