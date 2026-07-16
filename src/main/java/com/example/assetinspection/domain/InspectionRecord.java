package com.example.assetinspection.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 点巡检记录领域对象。
 *
 * <p>它映射的是逻辑表 inspection_record。到底落到共享库/租户分片库的哪张季度表，
 * 由 SQL 里的 tenantId 和 recordDate 决定，而不是在这个类里拼表名。</p>
 */
public class InspectionRecord {

    private Long id;
    private Long tenantId;
    private String requestId;
    private Long assetId;
    private String assetCode;
    private Long inspectionPointId;
    private String inspectionPointName;
    private LocalDate recordDate;
    private LocalDateTime inspectedAt;
    private InspectionStatus status;
    private BigDecimal measuredValue;
    private String unit;
    private String resultDescription;
    private Long inspectorId;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 以下两个字段来自 SELECT @@server_id、DATABASE()，不属于业务表。
    private Long servedByServerId;
    private String servedByDatabase;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getAssetCode() { return assetCode; }
    public void setAssetCode(String assetCode) { this.assetCode = assetCode; }
    public Long getInspectionPointId() { return inspectionPointId; }
    public void setInspectionPointId(Long inspectionPointId) { this.inspectionPointId = inspectionPointId; }
    public String getInspectionPointName() { return inspectionPointName; }
    public void setInspectionPointName(String inspectionPointName) { this.inspectionPointName = inspectionPointName; }
    public LocalDate getRecordDate() { return recordDate; }
    public void setRecordDate(LocalDate recordDate) { this.recordDate = recordDate; }
    public LocalDateTime getInspectedAt() { return inspectedAt; }
    public void setInspectedAt(LocalDateTime inspectedAt) { this.inspectedAt = inspectedAt; }
    public InspectionStatus getStatus() { return status; }
    public void setStatus(InspectionStatus status) { this.status = status; }
    public BigDecimal getMeasuredValue() { return measuredValue; }
    public void setMeasuredValue(BigDecimal measuredValue) { this.measuredValue = measuredValue; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getResultDescription() { return resultDescription; }
    public void setResultDescription(String resultDescription) { this.resultDescription = resultDescription; }
    public Long getInspectorId() { return inspectorId; }
    public void setInspectorId(Long inspectorId) { this.inspectorId = inspectorId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getServedByServerId() { return servedByServerId; }
    public void setServedByServerId(Long servedByServerId) { this.servedByServerId = servedByServerId; }
    public String getServedByDatabase() { return servedByDatabase; }
    public void setServedByDatabase(String servedByDatabase) { this.servedByDatabase = servedByDatabase; }
}
