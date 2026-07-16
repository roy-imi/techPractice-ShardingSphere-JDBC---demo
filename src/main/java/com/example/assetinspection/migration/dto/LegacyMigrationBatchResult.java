package com.example.assetinspection.migration.dto;

/** 单个迁移批次的结果；调用方只在 HTTP 成功后保存 nextAfterId。 */
public class LegacyMigrationBatchResult {

    // 本次请求传入的检查点。
    private final long requestedAfterId;

    // 本批实际处理的旧表行数，不包含用于探测 hasMore 的额外一行。
    private final int readRows;

    // 真正插入目标分片表的行数。
    private final int insertedRows;

    // 因 INSERT IGNORE 已存在而跳过的行数。
    private final int ignoredRows;

    // 下批应传入的检查点；空批时与 requestedAfterId 相同。
    private final long nextAfterId;

    // 旧表是否至少还有一行未处理。
    private final boolean hasMore;

    public LegacyMigrationBatchResult(long requestedAfterId,
                                      int readRows,
                                      int insertedRows,
                                      int ignoredRows,
                                      long nextAfterId,
                                      boolean hasMore) {
        this.requestedAfterId = requestedAfterId;
        this.readRows = readRows;
        this.insertedRows = insertedRows;
        this.ignoredRows = ignoredRows;
        this.nextAfterId = nextAfterId;
        this.hasMore = hasMore;
    }

    public long getRequestedAfterId() {
        return requestedAfterId;
    }

    public int getReadRows() {
        return readRows;
    }

    public int getInsertedRows() {
        return insertedRows;
    }

    public int getIgnoredRows() {
        return ignoredRows;
    }

    public long getNextAfterId() {
        return nextAfterId;
    }

    public boolean isHasMore() {
        return hasMore;
    }
}
