package com.example.assetinspection.migration.api;

import com.example.assetinspection.migration.dto.LegacyMigrationBatchResult;
import com.example.assetinspection.migration.dto.MigrationReconciliationResult;
import com.example.assetinspection.migration.service.LegacyMigrationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 历史单表回填与对账管理接口。
 *
 * <p>Demo 为了便于 curl 演示没有接认证；生产环境必须放在内网管理面、增加管理员鉴权、
 * 审计、任务互斥和限流，不能直接暴露到公网。</p>
 */
@RestController
@RequestMapping("/api/admin/migrations/legacy")
@ConditionalOnProperty(prefix = "demo.migration", name = "enabled", havingValue = "true")
public class LegacyMigrationController {

    // Controller 只负责 HTTP 参数绑定，迁移规则全部在 Service 中。
    private final LegacyMigrationService migrationService;

    public LegacyMigrationController(LegacyMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    /**
     * 执行一批迁移。
     *
     * <p>首次明确传 {@code afterId=0}；之后把成功响应中的 nextAfterId 持久化，再传给下一次。
     * 若请求超时或失败，不得猜测新游标，应使用上一次已确认游标重试。</p>
     */
    @PostMapping("/batches")
    public LegacyMigrationBatchResult migrateBatch(
            @RequestParam("afterId") Long afterId,
            @RequestParam(value = "batchSize", defaultValue = "500") Integer batchSize) {
        // Service 会校验游标、批大小、旧数据分片键和目标写入结果。
        return migrationService.migrateBatch(afterId, batchSize);
    }

    /** 按租户、半开日期区间比较旧单表和新逻辑表的 COUNT(*)。 */
    @GetMapping("/reconciliation")
    public MigrationReconciliationResult reconcile(
            @RequestParam("tenantId") Long tenantId,
            @RequestParam("startDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam("endDateExclusive")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDateExclusive) {
        // 不使用 X-Tenant-Id，因为管理员需要逐租户核对；生产环境应校验管理员授权范围。
        return migrationService.reconcile(tenantId, startDate, endDateExclusive);
    }
}
