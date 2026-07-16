package com.example.assetinspection.algorithm.quarter;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

/**
 * 将 {@code record_date} 固定映射到四张自然季度表的 ShardingSphere 标准分片算法。
 *
 * <p>映射恒定为 Q1 -> inspection_record_q1、...、Q4 -> inspection_record_q4。
 * 表名不包含年份，下一年同一季度会复用同一张表；复用安全由“三季度热窗口 +
 * 过期表延迟清理”的表生命周期流程保证。算法只负责路由和边界保护，不执行 TRUNCATE。</p>
 *
 * <p>建议在 ShardingSphere YAML 中通过 {@code CLASS_BASED} 加载本类，并传入：
 * {@code current-quarter=2026Q3}。该参数在季度切换发布时修改，应用运行中不热切换。</p>
 */
public final class FixedQuarterShardingAlgorithm
        implements StandardShardingAlgorithm<Comparable<?>> {

    public static final String TYPE = "FIXED_QUARTER_HOT_WINDOW";

    private static final String CURRENT_QUARTER_KEY = "current-quarter";

    private static final String RETAINED_QUARTER_COUNT_KEY = "retained-quarter-count";

    private static final String LOGIC_TABLE_NAME = "inspection_record";

    // init 由 ShardingSphere 在规则初始化时调用；初始化前不允许执行路由。
    private QuarterHotWindow hotWindow;

    @Override
    public void init(Properties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("季度分片算法 properties 不能为空");
        }

        // 当前季度必须显式配置，不能读取服务器时钟，否则各节点切换时刻可能不一致。
        BusinessQuarter configuredCurrent = BusinessQuarter.parse(
                properties.getProperty(CURRENT_QUARTER_KEY));

        // 架构约束固定保留三季度；若误配成 4，将失去“1 张空表待命”的轮转前提。
        int retainedCount = parseRetainedQuarterCount(properties);
        if (retainedCount != QuarterHotWindow.RETAINED_QUARTER_COUNT) {
            throw new IllegalArgumentException(
                    "retained-quarter-count 必须固定为 3，才能保证四张表中一张闲置待命");
        }
        this.hotWindow = new QuarterHotWindow(configuredCurrent);
    }

    @Override
    public String doSharding(Collection<String> availableTargetNames,
                             PreciseShardingValue<Comparable<?>> shardingValue) {
        requireInitialized();
        LocalDate recordDate = toLocalDate(shardingValue.getValue());
        // 先拒绝过期和未来数据，再计算表名，避免旧年份 Q1 误写进当前 q1 表。
        hotWindow.requireDateInWindow(recordDate);
        return findAvailableTarget(availableTargetNames, tableNameOf(recordDate));
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         RangeShardingValue<Comparable<?>> shardingValue) {
        requireInitialized();
        DateRange dateRange = toHalfOpenDateRange(shardingValue.getValueRange());

        // quartersForRange 同时完成热窗口边界校验，并保证最多返回三个季度。
        List<BusinessQuarter> quarters = hotWindow.quartersForRange(
                dateRange.startDateInclusive,
                dateRange.endDateExclusive);

        // LinkedHashSet 既保持时间顺序，也防御跨年时重复物理表名。
        LinkedHashSet<String> routedTables = new LinkedHashSet<String>();
        for (BusinessQuarter quarter : quarters) {
            String expectedTable = LOGIC_TABLE_NAME + "_q" + quarter.getQuarterOfYear();
            routedTables.add(findAvailableTarget(availableTargetNames, expectedTable));
        }
        return new ArrayList<String>(routedTables);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    private int parseRetainedQuarterCount(Properties properties) {
        String configured = properties.getProperty(
                RETAINED_QUARTER_COUNT_KEY,
                String.valueOf(QuarterHotWindow.RETAINED_QUARTER_COUNT));
        try {
            return Integer.parseInt(configured);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("retained-quarter-count 必须是整数", ex);
        }
    }

    /** 把 JDBC / MyBatis 可能传入的日期类型统一转换成 LocalDate。 */
    private LocalDate toLocalDate(Comparable<?> rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("record_date 分片值不能为空");
        }
        if (rawValue instanceof LocalDate) {
            return (LocalDate) rawValue;
        }
        if (rawValue instanceof Date) {
            return ((Date) rawValue).toLocalDate();
        }
        if (rawValue instanceof CharSequence) {
            try {
                return LocalDate.parse(rawValue.toString());
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(
                        "record_date 字符串必须使用 ISO yyyy-MM-dd 格式：" + rawValue,
                        ex);
            }
        }
        throw new IllegalArgumentException(
                "record_date 不支持的分片值类型：" + rawValue.getClass().getName());
    }

    /**
     * 将 Guava Range 的开闭边界转换为统一的 [start, end) 日期区间。
     *
     * <p>无界条件（例如只有 record_date &gt;= ?）会造成不可控扫描，因此直接拒绝；
     * 在线接口必须始终携带完整起止时间。</p>
     */
    private DateRange toHalfOpenDateRange(Range<Comparable<?>> range) {
        if (range == null || !range.hasLowerBound() || !range.hasUpperBound()) {
            throw new IllegalArgumentException("record_date 范围路由必须同时提供上下界");
        }

        LocalDate lower = toLocalDate(range.lowerEndpoint());
        LocalDate upper = toLocalDate(range.upperEndpoint());

        // (2026-01-01, ... 需要从次日开始；闭下界则保留当天。
        LocalDate startInclusive = range.lowerBoundType() == BoundType.CLOSED
                ? lower
                : lower.plusDays(1L);
        // ... <= 2026-03-31 转换成半开边界 2026-04-01；开上界保持原值。
        LocalDate endExclusive = range.upperBoundType() == BoundType.CLOSED
                ? upper.plusDays(1L)
                : upper;
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("record_date 路由范围为空或边界顺序错误");
        }
        return new DateRange(startInclusive, endExclusive);
    }

    private String tableNameOf(LocalDate recordDate) {
        int quarter = BusinessQuarter.from(recordDate).getQuarterOfYear();
        return LOGIC_TABLE_NAME + "_q" + quarter;
    }

    /** 确保规则声明的 actual-data-nodes 中确实存在算法计算出的物理表。 */
    private String findAvailableTarget(Collection<String> availableTargetNames, String expectedTable) {
        if (availableTargetNames == null) {
            throw new IllegalArgumentException("availableTargetNames 不能为空");
        }
        for (String candidate : availableTargetNames) {
            if (expectedTable.equals(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "季度分片目标表 " + expectedTable + " 不在 actual-data-nodes 中，可用表："
                        + availableTargetNames);
    }

    private void requireInitialized() {
        if (hotWindow == null) {
            throw new IllegalStateException("季度分片算法尚未 init，不能执行路由");
        }
    }

    /** 私有半开区间载体，避免在主流程中混用开闭边界。 */
    private static final class DateRange {

        private final LocalDate startDateInclusive;

        private final LocalDate endDateExclusive;

        private DateRange(LocalDate startDateInclusive, LocalDate endDateExclusive) {
            this.startDateInclusive = startDateInclusive;
            this.endDateExclusive = endDateExclusive;
        }
    }
}
