package com.example.assetinspection.algorithm.quarter;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BusinessQuarter 的季度边界与跨年计算测试。 */
class BusinessQuarterTest {

    @Test
    void shouldParseConfiguredQuarterAndCalculateBoundaries() {
        BusinessQuarter quarter = BusinessQuarter.parse("2026Q3");

        assertThat(quarter.getYear()).isEqualTo(2026);
        assertThat(quarter.getQuarterOfYear()).isEqualTo(3);
        assertThat(quarter.startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(quarter.endDateExclusive()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(quarter.toString()).isEqualTo("2026Q3");
    }

    @Test
    void shouldMapEveryNaturalQuarterAndMoveAcrossYear() {
        assertThat(BusinessQuarter.from(LocalDate.of(2026, 3, 31)).toString()).isEqualTo("2026Q1");
        assertThat(BusinessQuarter.from(LocalDate.of(2026, 4, 1)).toString()).isEqualTo("2026Q2");
        assertThat(BusinessQuarter.from(LocalDate.of(2026, 9, 30)).toString()).isEqualTo("2026Q3");
        assertThat(BusinessQuarter.from(LocalDate.of(2026, 10, 1)).toString()).isEqualTo("2026Q4");
        assertThat(BusinessQuarter.parse("2026Q1").plusQuarters(-1L).toString()).isEqualTo("2025Q4");
        assertThat(BusinessQuarter.parse("2026Q4").plusQuarters(1L).toString()).isEqualTo("2027Q1");
    }

    @Test
    void shouldFailFastForAmbiguousOrInvalidDeploymentText() {
        assertThatThrownBy(() -> BusinessQuarter.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BusinessQuarter.parse("2026-Q3"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BusinessQuarter.parse("2026Q5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BusinessQuarter.parse("2026q3"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BusinessQuarter.parse("2026Q3 "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
