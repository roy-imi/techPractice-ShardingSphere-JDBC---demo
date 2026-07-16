package com.example.assetinspection.dto;

import com.example.assetinspection.domain.InspectionStatus;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 创建点巡检记录的请求体；tenant_id 故意不在请求体中。 */
public class CreateInspectionRecordRequest {

    @NotBlank(message = "requestId 不能为空，它用于识别同一租户、同一季度槽位内的重复请求")
    @Size(max = 64, message = "requestId 最长 64 个字符")
    private String requestId;

    @NotNull(message = "assetId 不能为空")
    @Positive(message = "assetId 必须大于 0")
    private Long assetId;

    @NotBlank(message = "assetCode 不能为空")
    @Size(max = 64, message = "assetCode 最长 64 个字符")
    private String assetCode;

    @NotNull(message = "inspectionPointId 不能为空")
    @Positive(message = "inspectionPointId 必须大于 0")
    private Long inspectionPointId;

    @NotBlank(message = "inspectionPointName 不能为空")
    @Size(max = 128, message = "inspectionPointName 最长 128 个字符")
    private String inspectionPointName;

    @NotNull(message = "recordDate 不能为空，它是分表键")
    private LocalDate recordDate;

    @NotNull(message = "inspectedAt 不能为空")
    private LocalDateTime inspectedAt;

    @NotNull(message = "status 不能为空，可选 NORMAL/ABNORMAL/REPAIRED")
    private InspectionStatus status;

    @DecimalMin(value = "-99999999999999.9999", message = "measuredValue 过小")
    @DecimalMax(value = "99999999999999.9999", message = "measuredValue 过大")
    private BigDecimal measuredValue;

    @Size(max = 32, message = "unit 最长 32 个字符")
    private String unit;

    @Size(max = 500, message = "resultDescription 最长 500 个字符")
    private String resultDescription;

    @NotNull(message = "inspectorId 不能为空")
    @Positive(message = "inspectorId 必须大于 0")
    private Long inspectorId;

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
}
