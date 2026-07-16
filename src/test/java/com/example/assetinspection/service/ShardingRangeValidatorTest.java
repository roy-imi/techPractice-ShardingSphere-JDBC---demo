package com.example.assetinspection.service;

import com.example.assetinspection.config.ShardingRangeProperties;
import com.example.assetinspection.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分表日期边界和在线查询保护规则测试。 */
class ShardingRangeValidatorTest {

    private ShardingRangeValidator validator;

    @BeforeEach
    void setUp() {
        ShardingRangeProperties properties = new ShardingRangeProperties();
        properties.setSupportedStartDate(LocalDate.of(2026, 1, 1));
        properties.setSupportedEndDate(LocalDate.of(2026, 12, 31));
        validator = new ShardingRangeValidator(properties);
    }

    @Test
    void shouldAcceptBothPhysicalTableBoundaryDates() {
        assertThatCode(() -> validator.validateRecordDate(LocalDate.of(2026, 1, 1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateRecordDate(LocalDate.of(2026, 12, 31)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectDateOutsideCreatedMonthlyTables() {
        assertBusinessCode(
                () -> validator.validateRecordDate(LocalDate.of(2025, 12, 31)),
                "RECORD_DATE_OUT_OF_RANGE");
        assertBusinessCode(
                () -> validator.validateRecordDate(LocalDate.of(2027, 1, 1)),
                "RECORD_DATE_OUT_OF_RANGE");
    }

    @Test
    void shouldAllowExclusiveEndAtFirstDayOfNextYear() {
        // [2026-12-01, 2027-01-01) 实际只访问 202612 表。
        assertThatCode(() -> validator.validateOnlineRange(
                LocalDate.of(2026, 12, 1),
                LocalDate.of(2027, 1, 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectReversedOrOverlargeOnlineRange() {
        assertBusinessCode(
                () -> validator.validateOnlineRange(
                        LocalDate.of(2026, 3, 2),
                        LocalDate.of(2026, 3, 2)),
                "INVALID_DATE_RANGE");

        LocalDate start = LocalDate.of(2026, 1, 1);
        assertBusinessCode(
                () -> validator.validateOnlineRange(start, start.plusDays(94L)),
                "DATE_RANGE_TOO_LARGE");
    }

    @Test
    void shouldRequireDateAndIdCursorAsAPair() {
        assertBusinessCode(
                () -> validator.validateCursor(LocalDate.of(2026, 3, 1), null),
                "INCOMPLETE_CURSOR");
        assertBusinessCode(
                () -> validator.validateCursor(null, 100L),
                "INCOMPLETE_CURSOR");
        assertThatCode(() -> validator.validateCursor(LocalDate.of(2026, 3, 1), 100L))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldUseConfiguredThreeQuarterHotWindowInsteadOfLegacyYearRange() {
        ShardingRangeProperties properties = new ShardingRangeProperties();
        properties.setCurrentQuarter("2026Q3");
        properties.setRetainedQuarterCount(3);
        ShardingRangeValidator quarterValidator = new ShardingRangeValidator(properties);

        // 整个三季度热窗口可以一次查询，会路由 Q1、Q2、Q3 三张表。
        assertThatCode(() -> quarterValidator.validateOnlineRange(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> quarterValidator.validateRecordDate(LocalDate.of(2026, 9, 30)))
                .doesNotThrowAnyException();

        assertBusinessCode(
                () -> quarterValidator.validateRecordDate(LocalDate.of(2025, 12, 31)),
                "RECORD_DATE_EXPIRED");
        assertBusinessCode(
                () -> quarterValidator.validateRecordDate(LocalDate.of(2026, 10, 1)),
                "RECORD_DATE_FUTURE_QUARTER");
        assertBusinessCode(
                () -> quarterValidator.validateOnlineRange(
                        LocalDate.of(2025, 12, 31), LocalDate.of(2026, 4, 1)),
                "DATE_RANGE_EXPIRED");
        assertBusinessCode(
                () -> quarterValidator.validateOnlineRange(
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 2)),
                "DATE_RANGE_FUTURE_QUARTER");
    }

    @Test
    void shouldAllowHistoricalReadsButOnlyCurrentQuarterOnlineCreates() {
        ShardingRangeProperties properties = new ShardingRangeProperties();
        properties.setCurrentQuarter("2026Q3");
        properties.setRetainedQuarterCount(3);
        ShardingRangeValidator quarterValidator = new ShardingRangeValidator(properties);

        // Q2 仍属于热数据，因此详情和更新可以路由。
        assertThatCode(() -> quarterValidator.validateRecordDate(LocalDate.of(2026, 6, 30)))
                .doesNotThrowAnyException();
        // 但普通新增只准写 Q3，防止向即将淘汰的旧槽位继续灌入迟到数据。
        assertBusinessCode(
                () -> quarterValidator.validateOnlineCreateDate(LocalDate.of(2026, 6, 30)),
                "WRITE_NOT_CURRENT_QUARTER");
        assertThatCode(() -> quarterValidator.validateOnlineCreateDate(LocalDate.of(2026, 7, 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFailFastWhenQuarterDeploymentConfigurationBreaksRotationContract() {
        ShardingRangeProperties wrongRetention = new ShardingRangeProperties();
        wrongRetention.setCurrentQuarter("2026Q3");
        wrongRetention.setRetainedQuarterCount(4);
        assertThatThrownBy(() -> new ShardingRangeValidator(wrongRetention))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须固定为 3");

        ShardingRangeProperties wrongGrace = new ShardingRangeProperties();
        wrongGrace.setCurrentQuarter("2026Q3");
        wrongGrace.setRetainedQuarterCount(3);
        wrongGrace.setPurgeGraceDays(1);
        assertThatThrownBy(() -> new ShardingRangeValidator(wrongGrace))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("purge-grace-days");

        ShardingRangeProperties malformedQuarter = new ShardingRangeProperties();
        malformedQuarter.setCurrentQuarter("2026-3");
        assertThatThrownBy(() -> new ShardingRangeValidator(malformedQuarter))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current-quarter");
    }

    /** AssertJ 的辅助断言：同时验证异常类型和稳定业务码。 */
    private void assertBusinessCode(Runnable action, String expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(expectedCode);
    }
}
