package com.example.assetinspection.service;

import com.example.assetinspection.algorithm.quarter.BusinessQuarter;
import com.example.assetinspection.config.DeploymentShardingProperties;
import com.example.assetinspection.dto.RouteExpectation;
import com.example.assetinspection.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 把两种产品部署规则翻译成可以手算的预期路由。
 *
 * <p>本服务不参与真实 JDBC 路由；它只是面试学习辅助工具。
 * 最终结果仍必须以 ShardingSphere 日志中的 Actual SQL 和接口返回的 server-id 为准。</p>
 */
@Service
public class ExpectedRouteService {

    private final ShardingRangeValidator rangeValidator;

    private final DeploymentShardingProperties deploymentProperties;

    private final boolean legacyMode;

    public ExpectedRouteService(ShardingRangeValidator rangeValidator,
                                DeploymentShardingProperties deploymentProperties,
                                @Value("${demo.legacy-mode:false}") boolean legacyMode) {
        this.rangeValidator = rangeValidator;
        this.deploymentProperties = deploymentProperties;
        this.legacyMode = legacyMode;
    }

    /**
     * 计算某租户、某日期半开区间理论上会命中的库表。
     *
     * @param tenantId 租户 ID；即使标准版不按它分库，也必须用于行级隔离
     * @param startDate 查询起点，包含
     * @param endDateExclusive 查询终点，不包含
     * @return 预期物理路由说明
     */
    public RouteExpectation explain(Long tenantId,
                                    LocalDate startDate,
                                    LocalDate endDateExclusive) {
        if (tenantId == null || tenantId.longValue() <= 0L) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_TENANT_ID",
                    "tenantId 必须大于 0");
        }

        // 与真实业务入口复用同一窗口规则，避免推演出线上本来就会拒绝的路由。
        rangeValidator.validateOnlineRange(startDate, endDateExclusive);

        // legacy 是改造前对照组：没有季度表、租户分库或只读副本。
        if (legacyMode) {
            List<String> legacyTable = new ArrayList<String>();
            legacyTable.add("inspection_record");
            return new RouteExpectation(
                    tenantId,
                    "LEGACY_SINGLE_TABLE",
                    false,
                    "legacy_mysql",
                    legacyTable,
                    "legacy-mysql(server-id=10)",
                    "legacy-mysql(server-id=10，无只读副本)",
                    "legacy-mysql(server-id=10)",
                    "legacy 只用于演示改造前基线，不是产品交付模式");
        }

        // 标准版固定使用共享 ds_0；大客户版才按 tenant_id 对分片数取模。
        int shardIndex = deploymentProperties.isEnabled()
                ? (int) Math.floorMod(tenantId.longValue(), 2)
                : 0;
        String logicalDataSource = "ds_" + shardIndex;

        // 半开区间的结束日期不属于查询，因此用 end-1 计算最后一个自然季度。
        BusinessQuarter firstQuarter = BusinessQuarter.from(startDate);
        BusinessQuarter lastQuarter = BusinessQuarter.from(endDateExclusive.minusDays(1L));

        // 用 LinkedHashSet 保证表名去重且保持时间顺序，再转成便于 JSON 展示的 List。
        LinkedHashSet<String> routedTableSet = new LinkedHashSet<String>();
        BusinessQuarter cursor = firstQuarter;
        while (cursor.compareTo(lastQuarter) <= 0) {
            routedTableSet.add("inspection_record_q" + cursor.getQuarterOfYear());
            cursor = cursor.plusQuarters(1L);
        }
        List<String> physicalTables = new ArrayList<String>(routedTableSet);

        // server-id 与 docker-compose.yml 保持一致，用于核对真正访问了哪台 MySQL。
        String primary = shardIndex == 0
                ? "ds0_primary(server-id=100)"
                : "ds1_primary(server-id=101)";
        String replica = shardIndex == 0
                ? "ds0_replica(server-id=200)"
                : "ds1_replica(server-id=201)";

        return new RouteExpectation(
                tenantId,
                deploymentProperties.deploymentMode(),
                deploymentProperties.isEnabled(),
                logicalDataSource,
                physicalTables,
                primary,
                replica,
                primary,
                "这是规则推演；最终请核对 Actual SQL 与 servedByServerId");
    }
}
