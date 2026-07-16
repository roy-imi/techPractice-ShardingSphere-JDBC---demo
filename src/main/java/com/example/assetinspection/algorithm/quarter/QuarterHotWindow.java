package com.example.assetinspection.algorithm.quarter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * “当前季度 + 前两个完整季度”的在线热数据窗口。
 *
 * <p>窗口由部署配置 {@code current-quarter} 决定，不读取系统时钟。
 * 这样测试可重复，也明确表达了它是季度切换发布时更新的部署参数，
 * 不是应用运行期间根据当前日期偷偷切换的热开关。它占用三个自然季度表，
 * 但从“今天”向前计算的实际在线时长约为 6~9 个月，并非每天固定九个月。</p>
 */
public final class QuarterHotWindow {

    // 固定保留三个季度，四张物理表中始终有一张不参与在线路由、等待下一轮复用。
    public static final int RETAINED_QUARTER_COUNT = 3;

    // 部署配置指定的当前季度，也是允许在线写入的最新季度。
    private final BusinessQuarter currentQuarter;

    // 热窗口最老季度，等于 currentQuarter - 2。
    private final BusinessQuarter earliestQuarter;

    public QuarterHotWindow(BusinessQuarter currentQuarter) {
        this.currentQuarter = Objects.requireNonNull(currentQuarter, "currentQuarter 不能为空");
        this.earliestQuarter = currentQuarter.plusQuarters(-(RETAINED_QUARTER_COUNT - 1L));
    }

    /** 热窗口首日（包含），例如当前 2026Q3 时为 2026-01-01。 */
    public LocalDate startDateInclusive() {
        return earliestQuarter.startDate();
    }

    /** 热窗口结束边界（不包含），例如当前 2026Q3 时为 2026-10-01。 */
    public LocalDate endDateExclusive() {
        return currentQuarter.endDateExclusive();
    }

    /** 日期是否已经早于在线保留窗口，应转归档查询。 */
    public boolean isExpired(LocalDate date) {
        Objects.requireNonNull(date, "date 不能为空");
        return date.isBefore(startDateInclusive());
    }

    /** 日期是否属于当前季度之后，尚不允许写入或在线查询。 */
    public boolean isFuture(LocalDate date) {
        Objects.requireNonNull(date, "date 不能为空");
        return !date.isBefore(endDateExclusive());
    }

    /** 精确路由前执行“过期 / 未来”双边界保护。 */
    public void requireDateInWindow(LocalDate date) {
        if (isExpired(date)) {
            throw new IllegalArgumentException(
                    "日期 " + date + " 已过期；在线热窗口为 ["
                            + startDateInclusive() + ", " + endDateExclusive() + ")");
        }
        if (isFuture(date)) {
            throw new IllegalArgumentException(
                    "日期 " + date + " 属于未来季度；在线热窗口为 ["
                            + startDateInclusive() + ", " + endDateExclusive() + ")");
        }
    }

    /**
     * 校验半开查询区间完整落在三个季度热窗口内。
     *
     * <p>不允许“截断后继续查”，因为静默截断会让调用方误以为结果完整。</p>
     */
    public void requireRangeInWindow(LocalDate startDate, LocalDate queryEndDateExclusive) {
        Objects.requireNonNull(startDate, "startDate 不能为空");
        Objects.requireNonNull(queryEndDateExclusive, "endDateExclusive 不能为空");
        if (!startDate.isBefore(queryEndDateExclusive)) {
            throw new IllegalArgumentException("查询区间必须满足 startDate < endDateExclusive");
        }
        if (startDate.isBefore(startDateInclusive())) {
            throw new IllegalArgumentException(
                    "查询起点 " + startDate + " 已过期；最早只能查询 " + startDateInclusive());
        }
        if (queryEndDateExclusive.isAfter(endDateExclusive())) {
            throw new IllegalArgumentException(
                    "查询终点 " + queryEndDateExclusive + " 超出当前季度；最大半开边界为 "
                            + endDateExclusive());
        }
    }

    /** 返回查询区间实际覆盖的自然季度，最多三个且按时间升序。 */
    public List<BusinessQuarter> quartersForRange(LocalDate startDate,
                                                   LocalDate queryEndDateExclusive) {
        requireRangeInWindow(startDate, queryEndDateExclusive);

        BusinessQuarter first = BusinessQuarter.from(startDate);
        // 半开区间的结束边界不属于查询，因此先减一天再计算最后季度。
        BusinessQuarter last = BusinessQuarter.from(queryEndDateExclusive.minusDays(1L));
        List<BusinessQuarter> result = new ArrayList<BusinessQuarter>();
        BusinessQuarter cursor = first;
        while (cursor.compareTo(last) <= 0) {
            result.add(cursor);
            cursor = cursor.plusQuarters(1L);
        }
        if (result.size() > RETAINED_QUARTER_COUNT) {
            // 正常情况下边界校验已保证不会发生；这里是防止未来修改窗口逻辑时失去保护。
            throw new IllegalStateException("在线查询最多只能覆盖三个季度");
        }
        return Collections.unmodifiableList(result);
    }

    /** 当前在线保留的三个季度，供监控、表生命周期任务和测试展示。 */
    public List<BusinessQuarter> retainedQuarters() {
        List<BusinessQuarter> result = new ArrayList<BusinessQuarter>();
        for (int offset = 0; offset < RETAINED_QUARTER_COUNT; offset++) {
            result.add(earliestQuarter.plusQuarters(offset));
        }
        return Collections.unmodifiableList(result);
    }

    public BusinessQuarter getCurrentQuarter() {
        return currentQuarter;
    }

    public BusinessQuarter getEarliestQuarter() {
        return earliestQuarter;
    }
}
