package com.example.assetinspection.migration.service;

import com.example.assetinspection.config.ShardingRangeProperties;
import com.example.assetinspection.domain.InspectionRecord;
import com.example.assetinspection.domain.InspectionStatus;
import com.example.assetinspection.exception.BusinessException;
import com.example.assetinspection.mapper.InspectionRecordMapper;
import com.example.assetinspection.migration.dto.LegacyMigrationBatchResult;
import com.example.assetinspection.migration.dto.MigrationReconciliationResult;
import com.example.assetinspection.migration.source.LegacyInspectionRecordSource;
import com.example.assetinspection.service.ShardingRangeValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 迁移批次、检查点、幂等统计和对账的纯单元测试。 */
@ExtendWith(MockitoExtension.class)
class LegacyMigrationServiceTest {

    @Mock
    private LegacyInspectionRecordSource legacySource;

    @Mock
    private InspectionRecordMapper targetMapper;

    private LegacyMigrationService migrationService;

    @BeforeEach
    void setUp() {
        // 使用真实季度校验器覆盖“当前 2026Q3、在线保留 Q1~Q3”的核心规则。
        ShardingRangeProperties properties = new ShardingRangeProperties();
        properties.setCurrentQuarter("2026Q3");
        properties.setRetainedQuarterCount(3);
        ShardingRangeValidator rangeValidator = new ShardingRangeValidator(properties);
        migrationService = new LegacyMigrationService(legacySource, targetMapper, rangeValidator);
    }

    @Test
    void shouldReadOneExtraRowButOnlyCommitRequestedBatchAndReturnCheckpoint() {
        InspectionRecord first = validRecord(11L, 2L, LocalDate.of(2026, 3, 10));
        InspectionRecord second = validRecord(12L, 3L, LocalDate.of(2026, 4, 11));
        InspectionRecord lookAheadOnly = validRecord(13L, 2L, LocalDate.of(2026, 5, 12));
        // batchSize=2 时读取 limit=3，用第三行判断 hasMore。
        when(legacySource.readAfterId(10L, 3))
                .thenReturn(Arrays.asList(first, second, lookAheadOnly));
        // 第一行新增，第二行因目标端已存在被 INSERT IGNORE 跳过。
        when(targetMapper.insertForMigration(first)).thenReturn(1);
        when(targetMapper.insertForMigration(second)).thenReturn(0);

        LegacyMigrationBatchResult result = migrationService.migrateBatch(10L, 2);

        assertThat(result.getRequestedAfterId()).isEqualTo(10L);
        assertThat(result.getReadRows()).isEqualTo(2);
        assertThat(result.getInsertedRows()).isEqualTo(1);
        assertThat(result.getIgnoredRows()).isEqualTo(1);
        assertThat(result.getNextAfterId()).isEqualTo(12L);
        assertThat(result.isHasMore()).isTrue();
        verify(targetMapper).insertForMigration(first);
        verify(targetMapper).insertForMigration(second);
        // 探测行必须留给下一批，不能提前写入或推进游标。
        verify(targetMapper, never()).insertForMigration(lookAheadOnly);
    }

    @Test
    void shouldKeepCheckpointUnchangedForEmptyBatch() {
        when(legacySource.readAfterId(99L, 501)).thenReturn(Collections.emptyList());

        LegacyMigrationBatchResult result = migrationService.migrateBatch(99L, 500);

        assertThat(result.getReadRows()).isZero();
        assertThat(result.getInsertedRows()).isZero();
        assertThat(result.getIgnoredRows()).isZero();
        assertThat(result.getNextAfterId()).isEqualTo(99L);
        assertThat(result.isHasMore()).isFalse();
        verify(targetMapper, never()).insertForMigration(any(InspectionRecord.class));
    }

    @Test
    void shouldRejectSourceRowThatDoesNotAdvancePrimaryKeyCursor() {
        // id 与 afterId 相等，说明源 SQL 没有遵守 WHERE id > ? 的契约。
        InspectionRecord invalid = validRecord(10L, 2L, LocalDate.of(2026, 3, 10));
        when(legacySource.readAfterId(10L, 2)).thenReturn(Collections.singletonList(invalid));

        assertBusinessCode(
                () -> migrationService.migrateBatch(10L, 1),
                "LEGACY_CURSOR_ORDER_INVALID");
        verify(targetMapper, never()).insertForMigration(any(InspectionRecord.class));
    }

    @Test
    void shouldRejectRecordWithoutUsableShardingKeys() {
        InspectionRecord missingTenant = validRecord(11L, null, LocalDate.of(2026, 3, 10));
        when(legacySource.readAfterId(10L, 2))
                .thenReturn(Collections.singletonList(missingTenant));
        assertBusinessCode(
                () -> migrationService.migrateBatch(10L, 1),
                "LEGACY_TENANT_ID_INVALID");

        InspectionRecord outsidePhysicalTables = validRecord(
                12L,
                2L,
                LocalDate.of(2027, 1, 1));
        when(legacySource.readAfterId(11L, 2))
                .thenReturn(Collections.singletonList(outsidePhysicalTables));
        assertBusinessCode(
                () -> migrationService.migrateBatch(11L, 1),
                "LEGACY_RECORD_DATE_INVALID");
    }

    @Test
    void shouldRejectInvalidCursorAndOversizedBatchBeforeReadingLegacyDatabase() {
        assertBusinessCode(
                () -> migrationService.migrateBatch(-1L, 10),
                "INVALID_MIGRATION_CURSOR");
        assertBusinessCode(
                () -> migrationService.migrateBatch(0L, 1_001),
                "INVALID_MIGRATION_BATCH_SIZE");
        verify(legacySource, never()).readAfterId(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldReconcileTheWholeThreeQuarterHotWindow() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate endExclusive = LocalDate.of(2026, 10, 1);
        when(legacySource.count(3L, start, endExclusive)).thenReturn(120L);
        when(targetMapper.countForTenantAndRange(3L, start, endExclusive)).thenReturn(118L);

        MigrationReconciliationResult result = migrationService.reconcile(
                3L,
                start,
                endExclusive);

        assertThat(result.getTenantId()).isEqualTo(3L);
        assertThat(result.getLegacyCount()).isEqualTo(120L);
        assertThat(result.getShardedCount()).isEqualTo(118L);
        assertThat(result.getShardedMinusLegacy()).isEqualTo(-2L);
        assertThat(result.isMatched()).isFalse();
        verify(legacySource).count(3L, start, endExclusive);
        verify(targetMapper).countForTenantAndRange(3L, start, endExclusive);
    }

    @Test
    void shouldReportMatchedWhenBothSidesHaveSameCount() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        LocalDate endExclusive = LocalDate.of(2026, 7, 1);
        when(legacySource.count(2L, start, endExclusive)).thenReturn(20L);
        when(targetMapper.countForTenantAndRange(2L, start, endExclusive)).thenReturn(20L);

        MigrationReconciliationResult result = migrationService.reconcile(
                2L,
                start,
                endExclusive);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getShardedMinusLegacy()).isZero();
    }

    /** 创建一条具备有效路由键和迁移必填字段的旧记录。 */
    private InspectionRecord validRecord(Long id, Long tenantId, LocalDate recordDate) {
        InspectionRecord record = new InspectionRecord();
        record.setId(id);
        record.setTenantId(tenantId);
        record.setRecordDate(recordDate);
        record.setInspectedAt(LocalDateTime.of(recordDate, java.time.LocalTime.of(9, 0)));
        record.setStatus(InspectionStatus.NORMAL);
        record.setVersion(0);
        return record;
    }

    /** 同时断言异常类型与稳定业务码，避免只匹配易变的中文消息。 */
    private void assertBusinessCode(Runnable action, String expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(expectedCode);
    }
}
