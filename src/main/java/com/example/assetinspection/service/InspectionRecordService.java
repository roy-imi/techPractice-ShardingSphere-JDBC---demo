package com.example.assetinspection.service;

import com.example.assetinspection.context.TenantContextHolder;
import com.example.assetinspection.domain.InspectionRecord;
import com.example.assetinspection.domain.InspectionStatus;
import com.example.assetinspection.dto.CreateInspectionRecordRequest;
import com.example.assetinspection.dto.InspectionRecordPage;
import com.example.assetinspection.dto.InspectionRecordQuery;
import com.example.assetinspection.dto.StatusCount;
import com.example.assetinspection.dto.UpdateInspectionResultRequest;
import com.example.assetinspection.exception.BusinessException;
import com.example.assetinspection.mapper.InspectionRecordMapper;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 点巡检核心业务服务。
 *
 * <p>核心原则只有一个：每条在线 SQL 都显式携带 tenant_id + record_date/时间范围。
 * 这样 ShardingSphere 才能从“最多 2 库 × 每库 4 个季度槽位”缩小到最少节点。</p>
 */
@Service
public class InspectionRecordService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final InspectionRecordMapper mapper;
    private final ShardingRangeValidator rangeValidator;
    private final boolean unsafeQueryEnabled;

    public InspectionRecordService(InspectionRecordMapper mapper,
                                   ShardingRangeValidator rangeValidator,
                                   @Value("${demo.unsafe-query-enabled:false}") boolean unsafeQueryEnabled) {
        this.mapper = mapper;
        this.rangeValidator = rangeValidator;
        this.unsafeQueryEnabled = unsafeQueryEnabled;
    }

    /**
     * 创建记录：legacy profile 写改造前单表；product profile 先按部署形态和 record_date
     * 精确路由，再由读写规则落到主库。
     */
    @Transactional
    public InspectionRecord create(CreateInspectionRecordRequest request) {
        // tenant_id 来自过滤器建立的可信上下文，而不是请求体。
        Long tenantId = TenantContextHolder.requireTenantId();

        // 物理表必须提前存在；先校验可返回比“table not found”更清晰的错误。
        rangeValidator.validateOnlineCreateDate(request.getRecordDate());

        // record_date 是 inspected_at 的冗余路由列，必须一致，否则会落错季度槽位。
        if (!request.getRecordDate().equals(request.getInspectedAt().toLocalDate())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "RECORD_DATE_MISMATCH",
                    "recordDate 必须等于 inspectedAt 的日期部分，避免路由季度与业务发生时间不一致");
        }

        // 在内存中组装领域对象，不允许 Controller/客户端直接控制租户、版本或审计时间。
        InspectionRecord record = new InspectionRecord();
        record.setTenantId(tenantId);
        record.setRequestId(request.getRequestId().trim());
        record.setAssetId(request.getAssetId());
        record.setAssetCode(request.getAssetCode().trim());
        record.setInspectionPointId(request.getInspectionPointId());
        record.setInspectionPointName(request.getInspectionPointName().trim());
        record.setRecordDate(request.getRecordDate());
        record.setInspectedAt(request.getInspectedAt());
        record.setStatus(request.getStatus());
        record.setMeasuredValue(request.getMeasuredValue());
        record.setUnit(trimToNull(request.getUnit()));
        record.setResultDescription(trimToNull(request.getResultDescription()));
        record.setInspectorId(request.getInspectorId());
        record.setVersion(0);
        LocalDateTime now = LocalDateTime.now();
        record.setCreatedAt(now);
        record.setUpdatedAt(now);

        try {
            // XML 中没有 id 列：legacy 由 AUTO_INCREMENT 生成，sharding 由 Snowflake 在改写阶段补全。
            mapper.insert(record);
        } catch (DuplicateKeyException ex) {
            // 同租户、同一物理季度表、同 request_id 的重试命中局部唯一索引，转成 409 而不是 500。
            throw new BusinessException(HttpStatus.CONFLICT,
                    "DUPLICATE_REQUEST",
                    "同一租户、同一季度槽位下 requestId 已存在，请勿重复提交；跨季度全局幂等需独立幂等表/缓存");
        }
        // 不依赖 JDBC generated keys；同一写事务内按唯一业务键回读，会固定走主库并取得生成后的 ID。
        return requireRecord(mapper.selectByRequestId(tenantId, record.getRecordDate(), record.getRequestId()));
    }

    /**
     * 普通详情读：不打开事务，product 形态下会优先读 replica，允许短暂延迟。
     */
    public InspectionRecord findEventuallyConsistent(Long id, LocalDate recordDate) {
        Long tenantId = TenantContextHolder.requireTenantId();
        validateIdentityAndDate(id, recordDate);
        return requireRecord(mapper.selectByRoutingKeys(tenantId, recordDate, id));
    }

    /**
     * 强一致详情读：事务内读按 YAML 的 transactionalReadQueryStrategy=PRIMARY 路由主库。
     */
    @Transactional(readOnly = true)
    public InspectionRecord findStrongInTransaction(Long id, LocalDate recordDate) {
        Long tenantId = TenantContextHolder.requireTenantId();
        validateIdentityAndDate(id, recordDate);
        return requireRecord(mapper.selectByRoutingKeys(tenantId, recordDate, id));
    }

    /**
     * 非事务场景也可用 Hint 强制读主库；try-with-resources 保证 ThreadLocal Hint 被清理。
     */
    public InspectionRecord findStrongWithHint(Long id, LocalDate recordDate) {
        Long tenantId = TenantContextHolder.requireTenantId();
        validateIdentityAndDate(id, recordDate);
        try (HintManager hintManager = HintManager.getInstance()) {
            // 只影响当前线程/当前代码块内的读写分离选择，不改变分库分表键。
            hintManager.setWriteRouteOnly();
            return requireRecord(mapper.selectByRoutingKeys(tenantId, recordDate, id));
        }
    }

    /** 使用 record_date + id 的复合游标做稳定分页。 */
    public InspectionRecordPage list(LocalDate startDate,
                                     LocalDate endDateExclusive,
                                     InspectionStatus status,
                                     LocalDate cursorDate,
                                     Long cursorId,
                                     Integer requestedPageSize) {
        Long tenantId = TenantContextHolder.requireTenantId();
        rangeValidator.validateOnlineRange(startDate, endDateExclusive);
        rangeValidator.validateCursor(cursorDate, cursorId);

        int pageSize = requestedPageSize == null ? DEFAULT_PAGE_SIZE : requestedPageSize;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE_SIZE",
                    "pageSize 必须在 1 ~ " + MAX_PAGE_SIZE + " 之间");
        }

        InspectionRecordQuery query = new InspectionRecordQuery();
        query.setTenantId(tenantId);
        query.setStartDate(startDate);
        query.setEndDateExclusive(endDateExclusive);
        query.setStatus(status);
        query.setCursorDate(cursorDate);
        query.setCursorId(cursorId);
        // 多取一条只为判断 hasMore，不把额外行返回客户端。
        query.setLimit(pageSize + 1);

        List<InspectionRecord> fetched = mapper.selectPage(query);
        boolean hasMore = fetched.size() > pageSize;
        List<InspectionRecord> items = hasMore
                ? new ArrayList<InspectionRecord>(fetched.subList(0, pageSize))
                : fetched;

        InspectionRecord last = items.isEmpty() ? null : items.get(items.size() - 1);
        return new InspectionRecordPage(
                items,
                last == null ? null : last.getRecordDate(),
                last == null ? null : last.getId(),
                hasMore);
    }

    /**
     * 更新结果并在同一事务内回读；读会留在主库，因此返回的是刚更新后的版本。
     */
    @Transactional
    public InspectionRecord updateResult(Long id,
                                         LocalDate recordDate,
                                         UpdateInspectionResultRequest request) {
        Long tenantId = TenantContextHolder.requireTenantId();
        validateIdentityAndDate(id, recordDate);

        int affectedRows = mapper.updateResult(
                tenantId,
                recordDate,
                id,
                request.getStatus(),
                trimToNull(request.getResultDescription()),
                request.getVersion(),
                LocalDateTime.now());
        if (affectedRows == 0) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "OPTIMISTIC_LOCK_CONFLICT",
                    "记录不存在或 version 已变化，请重新查询后再更新");
        }
        return requireRecord(mapper.selectByRoutingKeys(tenantId, recordDate, id));
    }

    /** 跨季度聚合演示：范围越大，执行和结果归并成本越高。 */
    public List<StatusCount> statistics(LocalDate startDate, LocalDate endDateExclusive) {
        Long tenantId = TenantContextHolder.requireTenantId();
        rangeValidator.validateOnlineRange(startDate, endDateExclusive);
        return mapper.countByStatus(tenantId, startDate, endDateExclusive);
    }

    /**
     * 反例查询：缺 record_date，会命中目标数据库的全部 4 张季度槽位表。
     */
    public InspectionRecord unsafeFindWithoutRecordDate(Long tenantId, Long id) {
        if (!unsafeQueryEnabled) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "UNSAFE_QUERY_DISABLED",
                    "危险实验接口已关闭");
        }
        if (tenantId == null || tenantId <= 0L || id == null || id <= 0L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "INVALID_ROUTE_KEY",
                    "tenantId 与 id 必须是正整数");
        }
        return requireRecord(mapper.unsafeSelectWithoutDate(tenantId, id));
    }

    private void validateIdentityAndDate(Long id, LocalDate recordDate) {
        if (id == null || id <= 0L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ID", "id 必须大于 0");
        }
        rangeValidator.validateRecordDate(recordDate);
    }

    private InspectionRecord requireRecord(InspectionRecord record) {
        if (record == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND,
                    "INSPECTION_RECORD_NOT_FOUND",
                    "未找到记录；普通读刚写后查不到时，请考虑主从复制延迟并尝试 strong/hint 接口");
        }
        return record;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
