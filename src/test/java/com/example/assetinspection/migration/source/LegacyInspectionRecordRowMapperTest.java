package com.example.assetinspection.migration.source;

import com.example.assetinspection.domain.InspectionRecord;
import com.example.assetinspection.domain.InspectionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 旧表 JDBC 行到领域对象的字段映射测试。 */
class LegacyInspectionRecordRowMapperTest {

    @Test
    void shouldPreserveEveryBusinessAndRoutingField() throws Exception {
        ResultSet resultSet = completeResultSet("ABNORMAL");

        InspectionRecord record = new LegacyInspectionRecordRowMapper().mapRow(resultSet, 0);

        assertThat(record.getId()).isEqualTo(101L);
        assertThat(record.getTenantId()).isEqualTo(3L);
        assertThat(record.getRequestId()).isEqualTo("legacy-request-101");
        assertThat(record.getAssetId()).isEqualTo(2001L);
        assertThat(record.getAssetCode()).isEqualTo("PUMP-001");
        assertThat(record.getInspectionPointId()).isEqualTo(3001L);
        assertThat(record.getInspectionPointName()).isEqualTo("出口压力");
        assertThat(record.getRecordDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(record.getInspectedAt())
                .isEqualTo(LocalDateTime.of(2026, 3, 15, 9, 30));
        assertThat(record.getStatus()).isEqualTo(InspectionStatus.ABNORMAL);
        assertThat(record.getMeasuredValue()).isEqualByComparingTo("1.25");
        assertThat(record.getUnit()).isEqualTo("MPa");
        assertThat(record.getResultDescription()).isEqualTo("压力偏高");
        assertThat(record.getInspectorId()).isEqualTo(4001L);
        assertThat(record.getVersion()).isEqualTo(2);
        assertThat(record.getCreatedAt())
                .isEqualTo(LocalDateTime.of(2026, 3, 15, 9, 31));
        assertThat(record.getUpdatedAt())
                .isEqualTo(LocalDateTime.of(2026, 3, 15, 9, 35));
        // servedBy 字段是查询分片库时的诊断列，旧表迁移读取不会伪造它们。
        assertThat(record.getServedByServerId()).isNull();
        assertThat(record.getServedByDatabase()).isNull();
    }

    @Test
    void shouldFailFastForUnknownLegacyStatusInsteadOfSilentlyChangingMeaning() throws Exception {
        ResultSet resultSet = completeResultSet("UNKNOWN_STATUS");

        assertThatThrownBy(() -> new LegacyInspectionRecordRowMapper().mapRow(resultSet, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN_STATUS");
    }

    /** 构造一行完整 JDBC 结果；wasNull=false 表示所有数值型必填列均非 SQL NULL。 */
    private ResultSet completeResultSet(String status) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.wasNull()).thenReturn(false);
        when(resultSet.getLong("id")).thenReturn(101L);
        when(resultSet.getLong("tenant_id")).thenReturn(3L);
        when(resultSet.getString("request_id")).thenReturn("legacy-request-101");
        when(resultSet.getLong("asset_id")).thenReturn(2001L);
        when(resultSet.getString("asset_code")).thenReturn("PUMP-001");
        when(resultSet.getLong("inspection_point_id")).thenReturn(3001L);
        when(resultSet.getString("inspection_point_name")).thenReturn("出口压力");
        when(resultSet.getDate("record_date"))
                .thenReturn(java.sql.Date.valueOf(LocalDate.of(2026, 3, 15)));
        when(resultSet.getTimestamp("inspected_at"))
                .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 3, 15, 9, 30)));
        when(resultSet.getString("status")).thenReturn(status);
        when(resultSet.getBigDecimal("measured_value")).thenReturn(new BigDecimal("1.25"));
        when(resultSet.getString("unit")).thenReturn("MPa");
        when(resultSet.getString("result_description")).thenReturn("压力偏高");
        when(resultSet.getLong("inspector_id")).thenReturn(4001L);
        when(resultSet.getInt("version")).thenReturn(2);
        when(resultSet.getTimestamp("created_at"))
                .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 3, 15, 9, 31)));
        when(resultSet.getTimestamp("updated_at"))
                .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 3, 15, 9, 35)));
        return resultSet;
    }
}
