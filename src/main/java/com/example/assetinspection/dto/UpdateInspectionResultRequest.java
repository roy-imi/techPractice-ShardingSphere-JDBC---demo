package com.example.assetinspection.dto;

import com.example.assetinspection.domain.InspectionStatus;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;
import javax.validation.constraints.Size;

/** 更新结果时只允许改业务状态和描述，禁止修改两个分片键。 */
public class UpdateInspectionResultRequest {

    @NotNull(message = "status 不能为空")
    private InspectionStatus status;

    @Size(max = 500, message = "resultDescription 最长 500 个字符")
    private String resultDescription;

    @NotNull(message = "version 不能为空，必须携带查询时得到的乐观锁版本")
    @PositiveOrZero(message = "version 不能为负数")
    private Integer version;

    public InspectionStatus getStatus() { return status; }
    public void setStatus(InspectionStatus status) { this.status = status; }
    public String getResultDescription() { return resultDescription; }
    public void setResultDescription(String resultDescription) { this.resultDescription = resultDescription; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
