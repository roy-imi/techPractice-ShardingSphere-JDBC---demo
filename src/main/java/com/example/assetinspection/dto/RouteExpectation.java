package com.example.assetinspection.dto;

import java.util.List;

/** 根据当前部署规则手算出的预期路由，仅用于学习与验收。 */
public class RouteExpectation {

    // 本次推演使用的租户。
    private final Long tenantId;

    // LEGACY_SINGLE_TABLE、SHARED_DATABASE 或 TENANT_DATABASE。
    private final String deploymentMode;

    // true 才会用 tenant_id 选择 ds_0/ds_1；false 时租户仍作为 SQL 隔离条件。
    private final boolean tenantDatabaseShardingEnabled;

    // 分片和读写分离规则共同引用的逻辑数据源名。
    private final String logicalDataSource;

    // 日期范围理论上命中的物理表；季度模式最多三张。
    private final List<String> physicalTables;

    // 写请求目标主库。
    private final String writeTarget;

    // 非事务普通读目标从库。
    private final String normalReadTarget;

    // 事务内强一致读目标主库。
    private final String transactionalReadTarget;

    // 提醒调用方不要把手算结果当成真实运行证据。
    private final String warning;

    public RouteExpectation(Long tenantId,
                            String deploymentMode,
                            boolean tenantDatabaseShardingEnabled,
                            String logicalDataSource,
                            List<String> physicalTables,
                            String writeTarget,
                            String normalReadTarget,
                            String transactionalReadTarget,
                            String warning) {
        this.tenantId = tenantId;
        this.deploymentMode = deploymentMode;
        this.tenantDatabaseShardingEnabled = tenantDatabaseShardingEnabled;
        this.logicalDataSource = logicalDataSource;
        this.physicalTables = physicalTables;
        this.writeTarget = writeTarget;
        this.normalReadTarget = normalReadTarget;
        this.transactionalReadTarget = transactionalReadTarget;
        this.warning = warning;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getDeploymentMode() {
        return deploymentMode;
    }

    public boolean isTenantDatabaseShardingEnabled() {
        return tenantDatabaseShardingEnabled;
    }

    public String getLogicalDataSource() {
        return logicalDataSource;
    }

    public List<String> getPhysicalTables() {
        return physicalTables;
    }

    public String getWriteTarget() {
        return writeTarget;
    }

    public String getNormalReadTarget() {
        return normalReadTarget;
    }

    public String getTransactionalReadTarget() {
        return transactionalReadTarget;
    }

    public String getWarning() {
        return warning;
    }
}
