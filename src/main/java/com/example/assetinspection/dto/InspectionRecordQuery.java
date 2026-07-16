package com.example.assetinspection.dto;

import com.example.assetinspection.domain.InspectionStatus;

import java.time.LocalDate;

/** Service 传给 Mapper 的稳定游标查询条件。 */
public class InspectionRecordQuery {

    private Long tenantId;
    private LocalDate startDate;
    private LocalDate endDateExclusive;
    private InspectionStatus status;
    private LocalDate cursorDate;
    private Long cursorId;
    private int limit;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDateExclusive() { return endDateExclusive; }
    public void setEndDateExclusive(LocalDate endDateExclusive) { this.endDateExclusive = endDateExclusive; }
    public InspectionStatus getStatus() { return status; }
    public void setStatus(InspectionStatus status) { this.status = status; }
    public LocalDate getCursorDate() { return cursorDate; }
    public void setCursorDate(LocalDate cursorDate) { this.cursorDate = cursorDate; }
    public Long getCursorId() { return cursorId; }
    public void setCursorId(Long cursorId) { this.cursorId = cursorId; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
}
