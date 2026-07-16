package com.example.assetinspection.service;

import com.example.assetinspection.algorithm.quarter.BusinessQuarter;
import com.example.assetinspection.algorithm.quarter.QuarterHotWindow;
import com.example.assetinspection.config.ShardingRangeProperties;
import com.example.assetinspection.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 把“必须能精确路由”的约束放在业务入口统一校验。 */
@Component
public class ShardingRangeValidator {

    // legacy 单表对照环境的兼容限制；配置 current-quarter 后改由三季度热窗口约束。
    private static final long MAX_ONLINE_RANGE_DAYS = 93L;

    private final ShardingRangeProperties properties;

    // 非空表示启用新的固定四张季度表方案；为空时兼容原 legacy 日期边界。
    private final QuarterHotWindow quarterHotWindow;

    public ShardingRangeValidator(ShardingRangeProperties properties) {
        this.properties = properties;
        this.quarterHotWindow = createQuarterHotWindow(properties);
    }

    /** 校验单条记录的分表键位于当前在线热窗口。 */
    public void validateRecordDate(LocalDate recordDate) {
        if (recordDate == null) {
            throw badRequest("RECORD_DATE_REQUIRED", "recordDate 不能为空，它是分表键");
        }
        if (quarterHotWindow != null) {
            if (quarterHotWindow.isExpired(recordDate)) {
                throw badRequest("RECORD_DATE_EXPIRED",
                        "recordDate 已超出三个自然季度在线热窗口；历史数据应走归档查询，最早支持 "
                                + quarterHotWindow.startDateInclusive());
            }
            if (quarterHotWindow.isFuture(recordDate)) {
                throw badRequest("RECORD_DATE_FUTURE_QUARTER",
                        "recordDate 属于 current-quarter 之后，当前最大半开边界为 "
                                + quarterHotWindow.endDateExclusive());
            }
            return;
        }

        requireLegacyDateBoundaries();
        if (recordDate.isBefore(properties.getSupportedStartDate())
                || recordDate.isAfter(properties.getSupportedEndDate())) {
            throw badRequest("RECORD_DATE_OUT_OF_RANGE",
                    "recordDate 超出已创建物理表范围 "
                            + properties.getSupportedStartDate() + " ~ "
                            + properties.getSupportedEndDate());
        }
    }

    /**
     * 在线新增只允许写当前季度。
     *
     * <p>前两个季度仍可详情查询、更新和迁移，但普通新增不能把迟到数据悄悄写进即将清理的槽位。
     * 若业务确有迟到上报，应设计独立补录流程、审批和归档同步，而不是放宽所有在线写入。</p>
     */
    public void validateOnlineCreateDate(LocalDate recordDate) {
        // 先复用空值、过期和未来边界校验。
        validateRecordDate(recordDate);
        if (quarterHotWindow == null) {
            // legacy 对照环境没有“当前季度槽位”的概念，只保留原年度边界校验。
            return;
        }
        BusinessQuarter recordQuarter = BusinessQuarter.from(recordDate);
        if (!quarterHotWindow.getCurrentQuarter().equals(recordQuarter)) {
            throw badRequest(
                    "WRITE_NOT_CURRENT_QUARTER",
                    "在线新增只允许写当前季度 " + quarterHotWindow.getCurrentQuarter()
                            + "；历史补录必须走受控迁移/补录流程");
        }
    }

    /** 校验在线查询使用半开区间，且最多覆盖当前及前两个季度。 */
    public void validateOnlineRange(LocalDate startDate, LocalDate endDateExclusive) {
        if (startDate == null || endDateExclusive == null) {
            throw badRequest("DATE_RANGE_REQUIRED", "startDate 与 endDateExclusive 必须同时提供");
        }
        if (!startDate.isBefore(endDateExclusive)) {
            throw badRequest("INVALID_DATE_RANGE", "必须满足 startDate < endDateExclusive");
        }

        if (quarterHotWindow != null) {
            if (startDate.isBefore(quarterHotWindow.startDateInclusive())) {
                throw badRequest("DATE_RANGE_EXPIRED",
                        "查询起点已过期；在线热数据最早从 "
                                + quarterHotWindow.startDateInclusive() + " 开始");
            }
            if (endDateExclusive.isAfter(quarterHotWindow.endDateExclusive())) {
                throw badRequest("DATE_RANGE_FUTURE_QUARTER",
                        "查询终点超出 current-quarter；最大半开边界为 "
                                + quarterHotWindow.endDateExclusive());
            }

            // 在热窗口内天然最多覆盖三个季度；调用一次还能复用领域层的防御式约束。
            quarterHotWindow.quartersForRange(startDate, endDateExclusive);
            return;
        }

        // endDateExclusive 可以等于最后支持日期的下一天，例如查询完整的 2026-12-31。
        requireLegacyDateBoundaries();
        LocalDate maximumExclusive = properties.getSupportedEndDate().plusDays(1L);
        if (startDate.isBefore(properties.getSupportedStartDate())
                || endDateExclusive.isAfter(maximumExclusive)) {
            throw badRequest("DATE_RANGE_OUT_OF_RANGE",
                    "查询范围必须位于 [" + properties.getSupportedStartDate()
                            + ", " + maximumExclusive + ")");
        }

        long rangeDays = ChronoUnit.DAYS.between(startDate, endDateExclusive);
        if (rangeDays > MAX_ONLINE_RANGE_DAYS) {
            throw badRequest("DATE_RANGE_TOO_LARGE",
                    "在线接口最多查询 " + MAX_ONLINE_RANGE_DAYS
                            + " 天；年度报表应走预聚合/离线任务，而不是在线全路由");
        }
    }

    /** 游标的两个字段必须成对出现，否则排序位置不完整。 */
    public void validateCursor(LocalDate cursorDate, Long cursorId) {
        boolean bothNull = cursorDate == null && cursorId == null;
        boolean bothPresent = cursorDate != null && cursorId != null;
        if (!bothNull && !bothPresent) {
            throw badRequest("INCOMPLETE_CURSOR", "cursorDate 与 cursorId 必须同时提供或同时省略");
        }
        if (cursorId != null && cursorId <= 0L) {
            throw badRequest("INVALID_CURSOR", "cursorId 必须大于 0");
        }
        // 游标日期同样参与 record_date 路由，不能借游标绕过热窗口校验。
        if (cursorDate != null) {
            validateRecordDate(cursorDate);
        }
    }

    private QuarterHotWindow createQuarterHotWindow(ShardingRangeProperties configuredProperties) {
        String currentQuarter = configuredProperties.getCurrentQuarter();
        if (currentQuarter == null || currentQuarter.isEmpty()) {
            return null;
        }
        if (configuredProperties.getRetainedQuarterCount()
                != QuarterHotWindow.RETAINED_QUARTER_COUNT) {
            throw new IllegalStateException(
                    "demo.sharding.retained-quarter-count 必须固定为 3，才能保证一张季度表闲置待命");
        }
        if (configuredProperties.getPurgeGraceDays() != 3) {
            throw new IllegalStateException(
                    "demo.sharding.purge-grace-days 在本 Demo 中必须为 3；"
                            + "修改生命周期策略需同步评审运维脚本和数据保留制度");
        }
        try {
            return new QuarterHotWindow(BusinessQuarter.parse(currentQuarter));
        } catch (IllegalArgumentException ex) {
            // 这是部署配置错误，应让应用启动失败，而不是转换成某次请求的 400。
            throw new IllegalStateException("demo.sharding.current-quarter 配置错误：" + ex.getMessage(), ex);
        }
    }

    private void requireLegacyDateBoundaries() {
        if (properties.getSupportedStartDate() == null || properties.getSupportedEndDate() == null) {
            throw new IllegalStateException(
                    "未配置 current-quarter 时，必须提供 supported-start-date 和 supported-end-date");
        }
    }

    private BusinessException badRequest(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }
}
