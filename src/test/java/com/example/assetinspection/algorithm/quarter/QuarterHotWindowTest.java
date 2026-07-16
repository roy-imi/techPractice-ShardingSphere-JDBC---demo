package com.example.assetinspection.algorithm.quarter;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 三个自然季度热窗口在普通季度和跨年季度下的完整边界测试。 */
class QuarterHotWindowTest {

    @Test
    void shouldRetainCurrentAndPreviousTwoQuarters() {
        QuarterHotWindow window = new QuarterHotWindow(BusinessQuarter.parse("2026Q3"));

        assertThat(window.startDateInclusive()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(window.endDateExclusive()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(window.retainedQuarters()).extracting(BusinessQuarter::toString)
                .containsExactly("2026Q1", "2026Q2", "2026Q3");
        assertThatCode(() -> window.requireDateInWindow(LocalDate.of(2026, 1, 1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> window.requireDateInWindow(LocalDate.of(2026, 9, 30)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectExpiredAndFutureQuarterWithoutSilentlyTruncating() {
        QuarterHotWindow window = new QuarterHotWindow(BusinessQuarter.parse("2026Q3"));

        assertThatThrownBy(() -> window.requireDateInWindow(LocalDate.of(2025, 12, 31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已过期");
        assertThatThrownBy(() -> window.requireDateInWindow(LocalDate.of(2026, 10, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未来季度");
        assertThatThrownBy(() -> window.requireRangeInWindow(
                LocalDate.of(2025, 12, 31), LocalDate.of(2026, 4, 1)))
                .hasMessageContaining("已过期");
        assertThatThrownBy(() -> window.requireRangeInWindow(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 2)))
                .hasMessageContaining("超出当前季度");
    }

    @Test
    void shouldCalculateAtMostThreeTouchedQuartersAcrossYear() {
        QuarterHotWindow window = new QuarterHotWindow(BusinessQuarter.parse("2026Q1"));

        assertThat(window.retainedQuarters()).extracting(BusinessQuarter::toString)
                .containsExactly("2025Q3", "2025Q4", "2026Q1");
        assertThat(window.quartersForRange(
                LocalDate.of(2025, 7, 1), LocalDate.of(2026, 4, 1)))
                .extracting(BusinessQuarter::toString)
                .containsExactly("2025Q3", "2025Q4", "2026Q1");
        assertThat(window.quartersForRange(
                LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 2)))
                .extracting(BusinessQuarter::toString)
                .containsExactly("2025Q4", "2026Q1");
    }

    @Test
    void shouldRejectEmptyOrReversedRange() {
        QuarterHotWindow window = new QuarterHotWindow(BusinessQuarter.parse("2026Q3"));

        assertThatThrownBy(() -> window.requireRangeInWindow(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> window.requireRangeInWindow(
                LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
