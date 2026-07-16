package com.example.assetinspection.migration.source;

import com.example.assetinspection.domain.InspectionRecord;
import com.example.assetinspection.domain.InspectionStatus;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/** 把旧单表的一行完整映射成目标逻辑表使用的领域对象。 */
public class LegacyInspectionRecordRowMapper implements RowMapper<InspectionRecord> {

    @Override
    public InspectionRecord mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        // 创建一个新对象，确保各行之间没有可变状态复用。
        InspectionRecord record = new InspectionRecord();
        // 保留旧主键；迁移 SQL 不再生成 Snowflake ID。
        record.setId(readNullableLong(resultSet, "id"));
        // tenant_id 是目标分库键，不能丢失或改写。
        record.setTenantId(readNullableLong(resultSet, "tenant_id"));
        // request_id 用于业务幂等，迁移时原样保留。
        record.setRequestId(resultSet.getString("request_id"));
        // 资产与点位信息都是历史业务快照，逐字段复制。
        record.setAssetId(readNullableLong(resultSet, "asset_id"));
        record.setAssetCode(resultSet.getString("asset_code"));
        record.setInspectionPointId(readNullableLong(resultSet, "inspection_point_id"));
        record.setInspectionPointName(resultSet.getString("inspection_point_name"));

        // java.sql.Date 显式转 LocalDate，避免依赖 JDBC 4.2 驱动的具体返回类型。
        Date recordDate = resultSet.getDate("record_date");
        record.setRecordDate(recordDate == null ? null : recordDate.toLocalDate());
        // Timestamp 同理显式转 LocalDateTime。
        Timestamp inspectedAt = resultSet.getTimestamp("inspected_at");
        record.setInspectedAt(inspectedAt == null ? null : inspectedAt.toLocalDateTime());

        // 数据库存枚举名；非法旧值应立即失败并由人工清洗，不能静默映射成 NORMAL。
        String status = resultSet.getString("status");
        record.setStatus(status == null ? null : InspectionStatus.valueOf(status));
        // DECIMAL 直接使用 BigDecimal，避免 double 精度损失。
        record.setMeasuredValue(resultSet.getBigDecimal("measured_value"));
        record.setUnit(resultSet.getString("unit"));
        record.setResultDescription(resultSet.getString("result_description"));
        record.setInspectorId(readNullableLong(resultSet, "inspector_id"));

        // version 是乐观锁版本；为空时先保留 null，交给 Service 的完整性校验拦截。
        int version = resultSet.getInt("version");
        record.setVersion(resultSet.wasNull() ? null : version);

        // 审计时间必须保留，不能用迁移执行时间覆盖历史创建/更新时间。
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        record.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        record.setUpdatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime());
        return record;
    }

    /** ResultSet#getLong 遇到 SQL NULL 会返回 0，因此必须紧接着检查 wasNull。 */
    private Long readNullableLong(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : Long.valueOf(value);
    }
}
