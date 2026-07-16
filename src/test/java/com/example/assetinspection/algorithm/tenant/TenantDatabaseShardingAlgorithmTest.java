package com.example.assetinspection.algorithm.tenant;

import com.google.common.collect.Range;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** tenant_id 自定义分库算法测试。 */
class TenantDatabaseShardingAlgorithmTest {

    private final Collection<String> availableDatabases = Arrays.asList("ds_0", "ds_1");

    private TenantDatabaseShardingAlgorithm algorithm;

    @BeforeEach
    void setUp() {
        Properties properties = new Properties();
        properties.setProperty("shard-count", "2");
        algorithm = new TenantDatabaseShardingAlgorithm();
        algorithm.init(properties);
    }

    @Test
    void shouldRouteEvenTenantToDs0AndOddTenantToDs1() {
        assertThat(algorithm.doSharding(availableDatabases, precise(2L)))
                .isEqualTo("ds_0");
        assertThat(algorithm.doSharding(availableDatabases, precise(3L)))
                .isEqualTo("ds_1");
        assertThat(algorithm.getType()).isEqualTo(TenantDatabaseShardingAlgorithm.TYPE);
    }

    @Test
    void shouldBroadcastTenantRangeBecauseModuloCannotBeSafelyPruned() {
        Collection<String> result = algorithm.doSharding(
                availableDatabases,
                new RangeShardingValue<Long>(
                        "inspection_record",
                        "tenant_id",
                        null,
                        Range.closed(1L, 10L)));

        assertThat(result).containsExactly("ds_0", "ds_1");
    }

    @Test
    void shouldRejectInvalidConfigurationTenantAndMissingTarget() {
        assertThatThrownBy(() -> new TenantDatabaseShardingAlgorithm().init(new Properties()))
                .hasMessageContaining("shard-count");
        assertThatThrownBy(() -> algorithm.doSharding(availableDatabases, precise(0L)))
                .hasMessageContaining("正整数");
        assertThatThrownBy(() -> algorithm.doSharding(
                Arrays.asList("ds_0"),
                precise(3L)))
                .hasMessageContaining("不在 actual-data-nodes");
    }

    private PreciseShardingValue<Long> precise(Long tenantId) {
        return new PreciseShardingValue<Long>(
                "inspection_record",
                "tenant_id",
                null,
                tenantId);
    }
}
