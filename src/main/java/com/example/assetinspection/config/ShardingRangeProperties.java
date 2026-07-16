package com.example.assetinspection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Demo 的在线分片时间范围配置。
 *
 * <p>新季度方案通过 currentQuarter 固定“当前 + 前两个季度”的在线热窗口；
 * supportedStartDate / supportedEndDate 仅为 legacy 单表对照模式保留兼容兜底。
 * ShardingSphere 只负责路由，不负责建表、归档或清空过期物理表。</p>
 */
@ConfigurationProperties(prefix = "demo.sharding")
public class ShardingRangeProperties {

    // 部署时指定当前业务季度，例如 2026Q3；季度切换发布时修改并重启，不支持运行期热切换。
    private String currentQuarter;

    // 架构固定保留当前季度及前两个季度；值必须为 3，防止误配破坏“一表闲置”前提。
    private int retainedQuarterCount = 3;

    // 过期槽位默认保留三天再允许清理；真正清理仍需归档、对账和人工确认。
    private int purgeGraceDays = 3;

    // legacy 对照接口允许访问的最早业务日期。
    // 显式指定 ISO 格式，避免中文 Locale 把 2026-01-01 当成本地化短日期解析。
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate supportedStartDate;

    // legacy 对照接口允许访问的最晚业务日期。
    // 配置文件和 HTTP API 统一使用 yyyy-MM-dd，便于跨开发机复现。
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate supportedEndDate;

    public String getCurrentQuarter() {
        return currentQuarter;
    }

    public void setCurrentQuarter(String currentQuarter) {
        this.currentQuarter = currentQuarter;
    }

    public int getRetainedQuarterCount() {
        return retainedQuarterCount;
    }

    public void setRetainedQuarterCount(int retainedQuarterCount) {
        this.retainedQuarterCount = retainedQuarterCount;
    }

    public int getPurgeGraceDays() {
        return purgeGraceDays;
    }

    public void setPurgeGraceDays(int purgeGraceDays) {
        this.purgeGraceDays = purgeGraceDays;
    }

    public LocalDate getSupportedStartDate() {
        return supportedStartDate;
    }

    public void setSupportedStartDate(LocalDate supportedStartDate) {
        this.supportedStartDate = supportedStartDate;
    }

    public LocalDate getSupportedEndDate() {
        return supportedEndDate;
    }

    public void setSupportedEndDate(LocalDate supportedEndDate) {
        this.supportedEndDate = supportedEndDate;
    }
}
