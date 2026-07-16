package com.example.assetinspection.migration.dto;

import java.time.LocalDate;

/** 按“租户 + 日期窗口”核对旧单表与新分片表数量。 */
public class MigrationReconciliationResult {

    private final Long tenantId;
    private final LocalDate startDate;
    private final LocalDate endDateExclusive;
    private final long legacyCount;
    private final long shardedCount;
    private final long shardedMinusLegacy;
    private final boolean matched;

    public MigrationReconciliationResult(Long tenantId,
                                         LocalDate startDate,
                                         LocalDate endDateExclusive,
                                         long legacyCount,
                                         long shardedCount) {
        this.tenantId = tenantId;
        this.startDate = startDate;
        this.endDateExclusive = endDateExclusive;
        this.legacyCount = legacyCount;
        this.shardedCount = shardedCount;
        // 正数表示新分片端更多，负数表示仍有旧数据未迁移。
        this.shardedMinusLegacy = shardedCount - legacyCount;
        this.matched = legacyCount == shardedCount;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDateExclusive() {
        return endDateExclusive;
    }

    public long getLegacyCount() {
        return legacyCount;
    }

    public long getShardedCount() {
        return shardedCount;
    }

    public long getShardedMinusLegacy() {
        return shardedMinusLegacy;
    }

    public boolean isMatched() {
        return matched;
    }
}
