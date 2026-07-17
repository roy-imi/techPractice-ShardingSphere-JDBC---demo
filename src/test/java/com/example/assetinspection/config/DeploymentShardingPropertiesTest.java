package com.example.assetinspection.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 部署开关只允许两种稳定产品形态，数据库分片键不能被字符串配置偷换。 */
class DeploymentShardingPropertiesTest {

    @Test
    void shouldBindExplicitDatabaseShardingKeyProperty() {
        Map<String, Object> deploymentValues = new HashMap<>();
        deploymentValues.put("sharding.enabled", true);
        deploymentValues.put("sharding.database-sharding-key", "tenant_id");

        DeploymentShardingProperties properties = new Binder(
                new MapConfigurationPropertySource(deploymentValues))
                .bind("sharding", Bindable.of(DeploymentShardingProperties.class))
                .get();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getDatabaseShardingKey()).isEqualTo("tenant_id");
        assertThatCode(properties::validateForStartup).doesNotThrowAnyException();
    }

    @Test
    void shouldDescribeSharedAndTenantDatabaseModes() {
        DeploymentShardingProperties properties = new DeploymentShardingProperties();
        assertThat(properties.deploymentMode()).isEqualTo("SHARED_DATABASE");
        assertThatCode(properties::validateForStartup).doesNotThrowAnyException();

        properties.setEnabled(true);
        assertThat(properties.deploymentMode()).isEqualTo("TENANT_DATABASE");
    }

    @Test
    void shouldRejectChangingDatabaseShardingKeyThroughDeploymentConfiguration() {
        DeploymentShardingProperties properties = new DeploymentShardingProperties();
        properties.setDatabaseShardingKey("asset_id");

        assertThatThrownBy(properties::validateForStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id");
    }
}
