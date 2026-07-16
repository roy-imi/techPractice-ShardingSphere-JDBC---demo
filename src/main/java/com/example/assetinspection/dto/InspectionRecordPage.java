package com.example.assetinspection.dto;

import com.example.assetinspection.domain.InspectionRecord;

import java.time.LocalDate;
import java.util.List;

/** Keyset/seek 分页响应；不使用数据越深越慢的 OFFSET。 */
public class InspectionRecordPage {

    private final List<InspectionRecord> items;
    private final LocalDate nextCursorDate;
    private final Long nextCursorId;
    private final boolean hasMore;

    public InspectionRecordPage(List<InspectionRecord> items,
                                LocalDate nextCursorDate,
                                Long nextCursorId,
                                boolean hasMore) {
        this.items = items;
        this.nextCursorDate = nextCursorDate;
        this.nextCursorId = nextCursorId;
        this.hasMore = hasMore;
    }

    public List<InspectionRecord> getItems() { return items; }
    public LocalDate getNextCursorDate() { return nextCursorDate; }
    public Long getNextCursorId() { return nextCursorId; }
    public boolean isHasMore() { return hasMore; }
}
