package com.example.assetinspection.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 部署开关只允许两种稳定产品形态，分片键不能被字符串配置偷换。 */
class DeploymentShardingPropertiesTest {

    @Test
    void shouldDescribeSharedAndTenantDatabaseModes() {
        DeploymentShardingProperties properties = new DeploymentShardingProperties();
        assertThat(properties.deploymentMode()).isEqualTo("SHARED_DATABASE");
        assertThatCode(properties::validateForStartup).doesNotThrowAnyException();

        properties.setEnabled(true);
        assertThat(properties.deploymentMode()).isEqualTo("TENANT_DATABASE");
    }

    @Test
    void shouldRejectChangingShardingKeyThroughDeploymentConfiguration() {
        DeploymentShardingProperties properties = new DeploymentShardingProperties();
        properties.setShardingKey("asset_id");

        assertThatThrownBy(properties::validateForStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant_id");
    }
}
