package com.example.assetinspection.algorithm.quarter;

import com.google.common.collect.Range;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 固定四张季度表算法的精确路由、范围路由和失败保护测试。 */
class FixedQuarterShardingAlgorithmTest {

    private final Collection<String> availableTables = Arrays.asList(
            "inspection_record_q1",
            "inspection_record_q2",
            "inspection_record_q3",
            "inspection_record_q4");

    private FixedQuarterShardingAlgorithm algorithm;

    @BeforeEach
    void setUp() {
        algorithm = initializedAlgorithm("2026Q3");
    }

    @Test
    void shouldRoutePreciseLocalDateAndJdbcDateToFixedQuarterTable() {
        assertThat(algorithm.doSharding(
                availableTables,
                precise(LocalDate.of(2026, 8, 15))))
                .isEqualTo("inspection_record_q3");

        assertThat(algorithm.doSharding(
                availableTables,
                precise(Date.valueOf("2026-01-01"))))
                .isEqualTo("inspection_record_q1");

        assertThat(algorithm.doSharding(
                availableTables,
                precise("2026-06-30")))
                .isEqualTo("inspection_record_q2");
        assertThat(algorithm.getType()).isEqualTo(FixedQuarterShardingAlgorithm.TYPE);
    }

    @Test
    void shouldRejectExpiredFutureUnsupportedTypeAndMissingTable() {
        assertThatThrownBy(() -> algorithm.doSharding(
                availableTables, precise(LocalDate.of(2025, 12, 31))))
                .hasMessageContaining("已过期");
        assertThatThrownBy(() -> algorithm.doSharding(
                availableTables, precise(LocalDate.of(2026, 10, 1))))
                .hasMessageContaining("未来季度");
        assertThatThrownBy(() -> algorithm.doSharding(
                availableTables, precise(Integer.valueOf(20260701))))
                .hasMessageContaining("不支持的分片值类型");
        assertThatThrownBy(() -> algorithm.doSharding(
                Arrays.asList("inspection_record_q1"), precise(LocalDate.of(2026, 8, 1))))
                .hasMessageContaining("不在 actual-data-nodes");
    }

    @Test
    void shouldRouteClosedAndHalfOpenRangesWithoutIncludingBoundaryQuarter() {
        Collection<String> fullWindow = algorithm.doSharding(
                availableTables,
                range(Range.closed(
                        comparable(LocalDate.of(2026, 1, 1)),
                        comparable(LocalDate.of(2026, 9, 30)))));
        assertThat(fullWindow).containsExactly(
                "inspection_record_q1",
                "inspection_record_q2",
                "inspection_record_q3");

        // [2026-03-31, 2026-04-01) 只包含 Q1，不能因结束边界落在 4 月而误路由 Q2。
        Collection<String> oneDay = algorithm.doSharding(
                availableTables,
                range(Range.closedOpen(
                        comparable(LocalDate.of(2026, 3, 31)),
                        comparable(LocalDate.of(2026, 4, 1)))));
        assertThat(oneDay).containsExactly("inspection_record_q1");
    }

    @Test
    void shouldRouteCrossYearWindowToQ3Q4Q1InChronologicalOrder() {
        FixedQuarterShardingAlgorithm q1Algorithm = initializedAlgorithm("2026Q1");

        Collection<String> result = q1Algorithm.doSharding(
                availableTables,
                range(Range.closedOpen(
                        comparable(LocalDate.of(2025, 7, 1)),
                        comparable(LocalDate.of(2026, 4, 1)))));

        assertThat(result).containsExactly(
                "inspection_record_q3",
                "inspection_record_q4",
                "inspection_record_q1");
    }

    @Test
    void shouldRejectUnboundedEmptyExpiredAndFutureRanges() {
        assertThatThrownBy(() -> algorithm.doSharding(
                availableTables,
                range(Range.atLeast(comparable(LocalDate.of(2026, 1, 1))))))
                .hasMessageContaining("同时提供上下界");
        assertThatThrownBy(() -> algorithm.doSharding(
                availableTables,
                range(Range.open(
                        comparable(LocalDate.of(2026, 3, 1)),
                        comparable(LocalDate.of(2026, 3, 2))))))
                .hasMessageContaining("为空");
        assertThatThrownBy(() -> algorithm.doSharding(
                availableTables,
                range(Range.closedOpen(
                        comparable(LocalDate.of(2025, 12, 31)),
                        comparable(LocalDate.of(2026, 4, 1))))))
                .hasMessageContaining("已过期");
        assertThatThrownBy(() -> algorithm.doSharding(
                availableTables,
                range(Range.closedOpen(
                        comparable(LocalDate.of(2026, 7, 1)),
                        comparable(LocalDate.of(2026, 10, 2))))))
                .hasMessageContaining("超出当前季度");
    }

    @Test
    void shouldFailInitializationForMissingMalformedOrNonThreeQuarterConfig() {
        FixedQuarterShardingAlgorithm missing = new FixedQuarterShardingAlgorithm();
        assertThatThrownBy(() -> missing.init(new Properties()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current-quarter");

        Properties malformedProperties = new Properties();
        malformedProperties.setProperty("current-quarter", "2026-3");
        assertThatThrownBy(() -> new FixedQuarterShardingAlgorithm().init(malformedProperties))
                .hasMessageContaining("格式必须");

        Properties wrongRetention = new Properties();
        wrongRetention.setProperty("current-quarter", "2026Q3");
        wrongRetention.setProperty("retained-quarter-count", "4");
        assertThatThrownBy(() -> new FixedQuarterShardingAlgorithm().init(wrongRetention))
                .hasMessageContaining("必须固定为 3");
    }

    private FixedQuarterShardingAlgorithm initializedAlgorithm(String currentQuarter) {
        Properties properties = new Properties();
        properties.setProperty("current-quarter", currentQuarter);
        properties.setProperty("retained-quarter-count", "3");
        FixedQuarterShardingAlgorithm result = new FixedQuarterShardingAlgorithm();
        result.init(properties);
        return result;
    }

    private PreciseShardingValue<Comparable<?>> precise(Comparable<?> value) {
        return new PreciseShardingValue<Comparable<?>>(
                "inspection_record", "record_date", null, value);
    }

    private RangeShardingValue<Comparable<?>> range(Range<Comparable<?>> value) {
        return new RangeShardingValue<Comparable<?>>(
                "inspection_record", "record_date", null, value);
    }

    private Comparable<?> comparable(Comparable<?> value) {
        return value;
    }
}
