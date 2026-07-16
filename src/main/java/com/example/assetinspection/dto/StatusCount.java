package com.example.assetinspection.dto;

import com.example.assetinspection.domain.InspectionStatus;

/** 跨季度统计结果；ShardingSphere 会归并各命中季度表的 GROUP BY 结果。 */
public class StatusCount {

    private InspectionStatus status;
    private long count;

    public InspectionStatus getStatus() { return status; }
    public void setStatus(InspectionStatus status) { this.status = status; }
    public long getCount() { return count; }
    public void setCount(long count) { this.count = count; }
}
