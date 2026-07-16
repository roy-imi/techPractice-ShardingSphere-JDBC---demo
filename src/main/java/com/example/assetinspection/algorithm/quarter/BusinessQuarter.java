package com.example.assetinspection.algorithm.quarter;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 业务季度值对象，例如 {@code 2026Q3}。
 *
 * <p>这里把“年份 + 季度”封装成一个不可变对象，是为了避免在分片算法、
 * 参数校验和测试中到处复制月份除以 3、跨年减季度等容易出错的计算。</p>
 */
public final class BusinessQuarter implements Comparable<BusinessQuarter> {

    // 部署配置统一使用 2026Q3 这种格式；Q 必须大写，减少同一参数的多种写法。
    private static final Pattern TEXT_PATTERN = Pattern.compile("^(\\d{4})Q([1-4])$");

    // 年份参与季度先后比较。
    private final int year;

    // 自然季度编号，只允许 1、2、3、4。
    private final int quarterOfYear;

    private BusinessQuarter(int year, int quarterOfYear) {
        // 业务系统使用四位正年份，拒绝 0000Q1 这类虽然 LocalDate 支持、但没有业务意义的值。
        if (year < 1 || year > 9999) {
            throw new IllegalArgumentException("季度年份必须位于 0001 ~ 9999");
        }
        // 构造入口再次做防御式校验，避免未来新增工厂方法时绕过格式校验。
        if (quarterOfYear < 1 || quarterOfYear > 4) {
            throw new IllegalArgumentException("季度编号必须位于 1 ~ 4");
        }
        this.year = year;
        this.quarterOfYear = quarterOfYear;
    }

    /** 从部署配置文本解析当前季度。 */
    public static BusinessQuarter parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("current-quarter 不能为空，例如：2026Q3");
        }
        // 不主动 trim：配置中多一个空格也应在启动阶段暴露，而不是被静默修正。
        Matcher matcher = TEXT_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "current-quarter 格式必须为 yyyyQn，且 n 只能是 1~4，例如：2026Q3");
        }
        int parsedYear = Integer.parseInt(matcher.group(1));
        int parsedQuarter = Integer.parseInt(matcher.group(2));
        return new BusinessQuarter(parsedYear, parsedQuarter);
    }

    /** 根据业务日期计算其所属自然季度。 */
    public static BusinessQuarter from(LocalDate date) {
        Objects.requireNonNull(date, "date 不能为空");
        // 月份 1~3 -> Q1，4~6 -> Q2，7~9 -> Q3，10~12 -> Q4。
        int calculatedQuarter = (date.getMonthValue() - 1) / 3 + 1;
        return new BusinessQuarter(date.getYear(), calculatedQuarter);
    }

    /** 当前季度首日，例如 2026Q3 -> 2026-07-01。 */
    public LocalDate startDate() {
        int firstMonth = (quarterOfYear - 1) * 3 + 1;
        return LocalDate.of(year, firstMonth, 1);
    }

    /** 当前季度结束的半开边界，例如 2026Q3 -> 2026-10-01。 */
    public LocalDate endDateExclusive() {
        return startDate().plusMonths(3L);
    }

    /**
     * 跨年移动季度。
     *
     * <p>使用 LocalDate.plusMonths 处理跨年，避免手写 Q1 - 1 = 上一年 Q4 的分支。</p>
     */
    public BusinessQuarter plusQuarters(long amount) {
        long months = Math.multiplyExact(amount, 3L);
        return from(startDate().plusMonths(months));
    }

    public int getYear() {
        return year;
    }

    public int getQuarterOfYear() {
        return quarterOfYear;
    }

    @Override
    public int compareTo(BusinessQuarter other) {
        int yearResult = Integer.compare(year, other.year);
        return yearResult != 0 ? yearResult : Integer.compare(quarterOfYear, other.quarterOfYear);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BusinessQuarter)) {
            return false;
        }
        BusinessQuarter that = (BusinessQuarter) other;
        return year == that.year && quarterOfYear == that.quarterOfYear;
    }

    @Override
    public int hashCode() {
        return Objects.hash(year, quarterOfYear);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%04dQ%d", year, quarterOfYear);
    }
}
