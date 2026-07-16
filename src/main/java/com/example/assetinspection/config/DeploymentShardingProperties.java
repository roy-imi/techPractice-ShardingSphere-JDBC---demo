package com.example.assetinspection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 产品部署形态配置。
 *
 * <p>注意：历史原因沿用用户简历中的 {@code sharding.enabled} 名称，
 * 它只控制“是否按 tenant_id 分库”，并不关闭 ShardingSphere-JDBC 本身。
 * 即使值为 false，标准版仍会通过 ShardingSphere 完成季度分表与读写分离。</p>
 *
 * <p>该配置只在应用启动时读取。部署完成后不能把它当成功能开关在线切换，
 * 因为从共享库切到租户分库必须先完成存量数据迁移和对账。</p>
 */
@ConfigurationProperties(prefix = "sharding")
public class DeploymentShardingProperties {

    // false：标准版单库共享；true：大客户按 tenant_id 分库。
    private boolean enabled;

    // 面试方案的分库键固定为 tenant_id；保留配置项只为部署自检，不允许随意改列名。
    private String shardingKey = "tenant_id";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getShardingKey() {
        return shardingKey;
    }

    public void setShardingKey(String shardingKey) {
        this.shardingKey = shardingKey;
    }

    /** 启动前校验部署参数，避免“配置写了但规则实际不是那一列”的假开关。 */
    public void validateForStartup() {
        if (!"tenant_id".equals(shardingKey)) {
            throw new IllegalStateException(
                    "sharding.sharding-key 只允许 tenant_id；更换分片键属于数据架构迁移，"
                            + "不能通过部署配置直接完成");
        }
    }

    /** 返回适合日志和调试接口展示的部署模式名称。 */
    public String deploymentMode() {
        return enabled ? "TENANT_DATABASE" : "SHARED_DATABASE";
    }
}
