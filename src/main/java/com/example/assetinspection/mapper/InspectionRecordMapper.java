package com.example.assetinspection.mapper;

import com.example.assetinspection.domain.InspectionRecord;
import com.example.assetinspection.domain.InspectionStatus;
import com.example.assetinspection.dto.InspectionRecordQuery;
import com.example.assetinspection.dto.StatusCount;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 点巡检逻辑表 Mapper。
 *
 * <p>所有 SQL 都在 XML 中写 inspection_record；绝不在 Java 代码中拼接 q1~q4 物理表名。</p>
 */
public interface InspectionRecordMapper {

    int insert(InspectionRecord record);

    int insertForMigration(InspectionRecord record);

    InspectionRecord selectByRequestId(@Param("tenantId") Long tenantId,
                                       @Param("recordDate") LocalDate recordDate,
                                       @Param("requestId") String requestId);

    InspectionRecord selectByRoutingKeys(@Param("tenantId") Long tenantId,
                                         @Param("recordDate") LocalDate recordDate,
                                         @Param("id") Long id);

    List<InspectionRecord> selectPage(InspectionRecordQuery query);

    List<StatusCount> countByStatus(@Param("tenantId") Long tenantId,
                                    @Param("startDate") LocalDate startDate,
                                    @Param("endDateExclusive") LocalDate endDateExclusive);

    int updateResult(@Param("tenantId") Long tenantId,
                     @Param("recordDate") LocalDate recordDate,
                     @Param("id") Long id,
                     @Param("status") InspectionStatus status,
                     @Param("resultDescription") String resultDescription,
                     @Param("expectedVersion") Integer expectedVersion,
                     @Param("updatedAt") LocalDateTime updatedAt);

    InspectionRecord unsafeSelectWithoutDate(@Param("tenantId") Long tenantId,
                                             @Param("id") Long id);

    long countForTenantAndRange(@Param("tenantId") Long tenantId,
                               @Param("startDate") LocalDate startDate,
                               @Param("endDateExclusive") LocalDate endDateExclusive);
}
