package com.example.assetinspection.migration.service;

import com.example.assetinspection.domain.InspectionRecord;
import com.example.assetinspection.exception.BusinessException;
import com.example.assetinspection.mapper.InspectionRecordMapper;
import com.example.assetinspection.migration.dto.LegacyMigrationBatchResult;
import com.example.assetinspection.migration.dto.MigrationReconciliationResult;
import com.example.assetinspection.migration.source.LegacyInspectionRecordSource;
import com.example.assetinspection.service.ShardingRangeValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * 把旧单表数据按主键游标、有限批次回填到 ShardingSphere 逻辑表。
 *
 * <p>这是一份“可重复执行”的教学实现：源端只读，目标端使用 INSERT IGNORE，调用方持有
 * afterId 检查点。实际生产还应增加限速、分布式任务锁、字段级校验和告警。</p>
 */
@Service
@ConditionalOnProperty(prefix = "demo.migration", name = "enabled", havingValue = "true")
public class LegacyMigrationService {

    // 防止一次请求创建过大的目标侧工作单元，Demo 单批最多一千行。
    private static final int MAX_BATCH_SIZE = 1_000;

    // 旧单表读取端，不经过 ShardingSphere。
    private final LegacyInspectionRecordSource legacySource;

    // 目标端逻辑表 Mapper；tenant_id/record_date 会触发分库分表路由。
    private final InspectionRecordMapper targetMapper;

    // 复用业务日期边界，确保目标日期仍位于三个在线季度槽位内。
    private final ShardingRangeValidator rangeValidator;

    public LegacyMigrationService(LegacyInspectionRecordSource legacySource,
                                  InspectionRecordMapper targetMapper,
                                  ShardingRangeValidator rangeValidator) {
        this.legacySource = legacySource;
        this.targetMapper = targetMapper;
        this.rangeValidator = rangeValidator;
    }

    /**
     * 执行一个可重试的迁移批次。
     *
     * <p>方法使用 LOCAL 事务：在普通 SQL/业务异常路径下，事务管理器会尝试回滚本批已经打开的
     * 目标连接；但如果一个批次同时命中 ds0、ds1，它并不是跨库全局原子事务，提交阶段发生网络或
     * 硬件故障时仍可能部分成功。正因如此，调用方只在方法成功返回后推进检查点，并用
     * INSERT IGNORE、旧 afterId 重试和事后对账来收敛，不能把 {@code @Transactional}
     * 误解为“新旧库以及所有目标库永远一起提交”。</p>
     *
     * @param afterId 上一批成功提交后的检查点；第一次传 0
     * @param batchSize 本批最多真正处理的行数
     * @return 插入/忽略数量和下批游标
     */
    @Transactional
    public LegacyMigrationBatchResult migrateBatch(Long afterId, Integer batchSize) {
        // 请求参数先于任何数据库访问完成校验。
        long validatedAfterId = validateAfterId(afterId);
        int validatedBatchSize = validateBatchSize(batchSize);

        // 多读一行只为判断是否还有下一批；额外行本批绝不写入。
        List<InspectionRecord> fetched = legacySource.readAfterId(
                validatedAfterId,
                validatedBatchSize + 1);
        // 正常实现永远返回非 null；防御性处理让测试替身/异常驱动不造成 NPE。
        if (fetched == null) {
            fetched = Collections.emptyList();
        }

        // 超过 batchSize 就说明至少还有下一页；只处理前 batchSize 行。
        boolean hasMore = fetched.size() > validatedBatchSize;
        int rowsToProcess = Math.min(fetched.size(), validatedBatchSize);
        int insertedRows = 0;
        int ignoredRows = 0;
        long nextAfterId = validatedAfterId;

        // 按源 SQL 返回的 id 升序依次回填，nextAfterId 最终等于最后一条已处理主键。
        for (int index = 0; index < rowsToProcess; index++) {
            InspectionRecord record = fetched.get(index);
            // 校验分片键与游标单调性；坏数据必须停止批次，不能被静默跳过。
            validateRecord(record, nextAfterId);

            // XML 使用 INSERT IGNORE 并显式写旧 id，因此同一 afterId 批次可以重复调用。
            int affectedRows = targetMapper.insertForMigration(record);
            if (affectedRows == 1) {
                // 返回 1 表示目标端本次新增一行。
                insertedRows++;
            } else if (affectedRows == 0) {
                // 返回 0 表示命中主键/唯一键并被 IGNORE；需在最终对账中继续确认。
                ignoredRows++;
            } else {
                // 单行 INSERT 理论上不可能影响多行，出现时应回滚并排查驱动/SQL。
                throw migrationInvariantViolation(
                        "MIGRATION_UNEXPECTED_AFFECTED_ROWS",
                        "单行迁移写入返回了异常 affectedRows=" + affectedRows);
            }
            // 只在该行写入调用成功后推进内存游标。
            nextAfterId = record.getId().longValue();
        }

        // 只有方法正常返回时调用方才推进检查点；若提交结果不确定，仍用旧 afterId 幂等重试并对账。
        return new LegacyMigrationBatchResult(
                validatedAfterId,
                rowsToProcess,
                insertedRows,
                ignoredRows,
                nextAfterId,
                hasMore);
    }

    /**
     * 对某个租户、某个日期窗口执行数量对账。
     *
     * <p>目标端 COUNT 带 tenant_id 和日期范围，ShardingSphere 只路由到该租户分库中被日期
     * 覆盖的季度表。事务内查询按照 YAML 的 PRIMARY 策略读主库，避免复制延迟造成假差异。</p>
     */
    @Transactional(readOnly = true)
    public MigrationReconciliationResult reconcile(Long tenantId,
                                                    LocalDate startDate,
                                                    LocalDate endDateExclusive) {
        // 管理接口没有普通租户请求头，因此必须显式校验参数中的 tenantId。
        validateTenantId(tenantId);
        // 迁移对账可覆盖完整三季度热窗口，不受普通接口单次查询习惯影响。
        validateReconciliationRange(startDate, endDateExclusive);

        // 先统计旧单表；其 JdbcTemplate 使用独立只读连接，不参与目标事务。
        long legacyCount = legacySource.count(tenantId, startDate, endDateExclusive);
        // 再统计新逻辑表；tenant + 日期范围会触发正确的分片裁剪。
        long shardedCount = targetMapper.countForTenantAndRange(
                tenantId,
                startDate,
                endDateExclusive);

        // 返回差值而不是遇到不一致就抛异常，方便迁移控制台展示和告警。
        return new MigrationReconciliationResult(
                tenantId,
                startDate,
                endDateExclusive,
                legacyCount,
                shardedCount);
    }

    /** afterId=0 表示从头开始；负数与 null 都是调用错误。 */
    private long validateAfterId(Long afterId) {
        if (afterId == null || afterId.longValue() < 0L) {
            throw badRequest("INVALID_MIGRATION_CURSOR", "afterId 必须是大于等于 0 的整数");
        }
        return afterId.longValue();
    }

    /** 单批至少一行、至多一千行，控制内存、SQL 次数和事务持续时间。 */
    private int validateBatchSize(Integer batchSize) {
        if (batchSize == null || batchSize.intValue() < 1
                || batchSize.intValue() > MAX_BATCH_SIZE) {
            throw badRequest(
                    "INVALID_MIGRATION_BATCH_SIZE",
                    "batchSize 必须在 1 ~ " + MAX_BATCH_SIZE + " 之间");
        }
        return batchSize.intValue();
    }

    /**
     * 校验一条旧数据是否能安全写入目标分片表。
     *
     * @param record 当前旧记录
     * @param previousId 请求游标或本批上一条记录 id
     */
    private void validateRecord(InspectionRecord record, long previousId) {
        if (record == null) {
            throw migrationInvariantViolation(
                    "LEGACY_NULL_ROW",
                    "旧表读取结果中出现 null 行");
        }
        if (record.getId() == null || record.getId().longValue() <= previousId) {
            throw migrationInvariantViolation(
                    "LEGACY_CURSOR_ORDER_INVALID",
                    "旧表必须按 id 严格升序返回，且每个 id 都必须大于 afterId");
        }
        if (record.getTenantId() == null || record.getTenantId().longValue() <= 0L) {
            throw migrationInvariantViolation(
                    "LEGACY_TENANT_ID_INVALID",
                    "旧记录 id=" + record.getId() + " 缺少有效 tenant_id，无法决定目标分库");
        }

        // record_date 是目标分表键；此校验也会拒绝已过期或未来季度的日期。
        try {
            rangeValidator.validateRecordDate(record.getRecordDate());
        } catch (BusinessException exception) {
            throw migrationInvariantViolation(
                    "LEGACY_RECORD_DATE_INVALID",
                    "旧记录 id=" + record.getId() + " 的 record_date 无可用在线季度槽位："
                            + exception.getMessage());
        }

        if (record.getInspectedAt() == null
                || !record.getRecordDate().equals(record.getInspectedAt().toLocalDate())) {
            throw migrationInvariantViolation(
                    "LEGACY_RECORD_DATE_MISMATCH",
                    "旧记录 id=" + record.getId()
                            + " 的 record_date 必须等于 inspected_at 日期部分");
        }
        if (record.getStatus() == null || record.getVersion() == null) {
            throw migrationInvariantViolation(
                    "LEGACY_REQUIRED_FIELD_MISSING",
                    "旧记录 id=" + record.getId() + " 缺少 status 或 version");
        }
    }

    /** 对账区间采用 [startDate,endDateExclusive)，且必须完整落在当前三季度热窗口内。 */
    private void validateReconciliationRange(LocalDate startDate,
                                             LocalDate endDateExclusive) {
        if (startDate == null || endDateExclusive == null) {
            throw badRequest(
                    "DATE_RANGE_REQUIRED",
                    "startDate 与 endDateExclusive 必须同时提供");
        }
        if (!startDate.isBefore(endDateExclusive)) {
            throw badRequest(
                    "INVALID_DATE_RANGE",
                    "必须满足 startDate < endDateExclusive");
        }

        // 起点必须位于已创建的物理表范围。
        rangeValidator.validateRecordDate(startDate);
        // 半开区间最后实际包含的日期是 endDateExclusive - 1 天。
        rangeValidator.validateRecordDate(endDateExclusive.minusDays(1L));
    }

    /** 管理接口按指定租户做对账，0/null 都不能参与取模路由。 */
    private void validateTenantId(Long tenantId) {
        if (tenantId == null || tenantId.longValue() <= 0L) {
            throw badRequest("INVALID_TENANT_ID", "tenantId 必须大于 0");
        }
    }

    /** 客户端参数错误返回 400。 */
    private BusinessException badRequest(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** 源数据或迁移 SQL 违反约定属于服务端迁移异常，应停止并人工处理。 */
    private BusinessException migrationInvariantViolation(String code, String message) {
        return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, code, message);
    }
}
