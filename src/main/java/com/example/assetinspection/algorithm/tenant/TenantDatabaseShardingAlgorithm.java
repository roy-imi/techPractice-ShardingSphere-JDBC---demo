package com.example.assetinspection.algorithm.tenant;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Properties;

/**
 * 按 tenant_id 选择物理分片库的自定义标准算法。
 *
 * <p>Demo 使用 {@code floorMod(tenantId, shardCount)}：偶数进入 ds_0，奇数进入 ds_1。
 * 生产环境若直接修改 shard-count，会导致大批租户重映射，因此分片数也是部署后不可热改的架构参数。</p>
 */
public final class TenantDatabaseShardingAlgorithm
        implements StandardShardingAlgorithm<Long> {

    public static final String TYPE = "TENANT_DATABASE_MOD";

    // 由 YAML 在 ShardingSphere 初始化算法时注入；Demo 固定为 2。
    private int shardCount;

    @Override
    public void init(Properties properties) {
        String rawShardCount = properties == null ? null : properties.getProperty("shard-count");
        if (rawShardCount == null) {
            throw new IllegalArgumentException("tenant 分库算法必须配置 shard-count");
        }
        try {
            shardCount = Integer.parseInt(rawShardCount);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("shard-count 必须是正整数", ex);
        }
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shard-count 必须大于 0");
        }
    }

    @Override
    public String doSharding(Collection<String> availableTargetNames,
                             PreciseShardingValue<Long> shardingValue) {
        requireInitialized();
        Long tenantId = shardingValue == null ? null : shardingValue.getValue();
        if (tenantId == null || tenantId.longValue() <= 0L) {
            throw new IllegalArgumentException("tenant_id 分片值必须是正整数");
        }

        // floorMod 比 % 更稳健；即使上游漏掉负数校验也不会生成 ds_-1 这种非法名称。
        int shardIndex = (int) Math.floorMod(tenantId.longValue(), shardCount);
        String expectedTarget = "ds_" + shardIndex;
        if (availableTargetNames != null && availableTargetNames.contains(expectedTarget)) {
            return expectedTarget;
        }
        throw new IllegalArgumentException(
                "tenant_id=" + tenantId + " 计算出的目标 " + expectedTarget
                        + " 不在 actual-data-nodes 中，可用目标：" + availableTargetNames);
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         RangeShardingValue<Long> shardingValue) {
        requireInitialized();
        // tenant_id 范围条件无法安全裁剪取模分片，只能返回全部候选库；业务 SQL 应使用等值 tenant_id。
        if (availableTargetNames == null) {
            return Collections.emptyList();
        }
        return new ArrayList<String>(availableTargetNames);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    private void requireInitialized() {
        if (shardCount <= 0) {
            throw new IllegalStateException("tenant 分库算法尚未 init");
        }
    }
}
