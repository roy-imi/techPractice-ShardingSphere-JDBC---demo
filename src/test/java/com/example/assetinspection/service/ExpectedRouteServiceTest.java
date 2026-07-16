package com.example.assetinspection.service;

import com.example.assetinspection.config.DeploymentShardingProperties;
import com.example.assetinspection.config.ShardingRangeProperties;
import com.example.assetinspection.dto.RouteExpectation;
import com.example.assetinspection.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ExpectedRouteService 的纯单元测试，不启动 Spring 或 MySQL。 */
class ExpectedRouteServiceTest {

    @Test
    void shouldKeepAllTenantsInDs0WhenTenantDatabaseShardingIsDisabled() {
        // 即使租户 3 是奇数，标准版也必须留在共享库 ds_0。
        ExpectedRouteService service = productService(false, "2026Q3");

        RouteExpectation result = service.explain(
                3L,
                LocalDate.of(2026, 3, 20),
                LocalDate.of(2026, 4, 2));

        assertThat(result.getDeploymentMode()).isEqualTo("SHARED_DATABASE");
        assertThat(result.isTenantDatabaseShardingEnabled()).isFalse();
        assertThat(result.getLogicalDataSource()).isEqualTo("ds_0");
        assertThat(result.getPhysicalTables()).containsExactly(
                "inspection_record_q1",
                "inspection_record_q2");
        assertThat(result.getWriteTarget()).contains("server-id=100");
        assertThat(result.getNormalReadTarget()).contains("server-id=200");
    }

    @Test
    void shouldRouteOddTenantToDs1WhenTenantDatabaseShardingIsEnabled() {
        // 大客户部署开启分库后，租户 3 对 2 取模为 1。
        ExpectedRouteService service = productService(true, "2026Q3");

        RouteExpectation result = service.explain(
                3L,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 1));

        assertThat(result.getDeploymentMode()).isEqualTo("TENANT_DATABASE");
        assertThat(result.isTenantDatabaseShardingEnabled()).isTrue();
        assertThat(result.getLogicalDataSource()).isEqualTo("ds_1");
        assertThat(result.getPhysicalTables()).containsExactly("inspection_record_q3");
        assertThat(result.getWriteTarget()).contains("server-id=101");
        assertThat(result.getNormalReadTarget()).contains("server-id=201");
    }

    @Test
    void shouldExplainLegacyAsOnePhysicalTable() {
        ShardingRangeProperties range = new ShardingRangeProperties();
        range.setSupportedStartDate(LocalDate.of(2026, 1, 1));
        range.setSupportedEndDate(LocalDate.of(2026, 12, 31));
        DeploymentShardingProperties deployment = new DeploymentShardingProperties();
        ExpectedRouteService service = new ExpectedRouteService(
                new ShardingRangeValidator(range),
                deployment,
                true);

        RouteExpectation result = service.explain(
                2L,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 4, 1));

        assertThat(result.getDeploymentMode()).isEqualTo("LEGACY_SINGLE_TABLE");
        assertThat(result.getPhysicalTables()).containsExactly("inspection_record");
        assertThat(result.getWriteTarget()).contains("server-id=10");
    }

    @Test
    void shouldRejectNonPositiveTenantBeforeCalculatingRoute() {
        ExpectedRouteService service = productService(true, "2026Q3");

        assertThatThrownBy(() -> service.explain(
                0L,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 2)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("INVALID_TENANT_ID");
    }

    /** 组装与 product profile 等价的测试对象。 */
    private ExpectedRouteService productService(boolean tenantShardingEnabled,
                                                String currentQuarter) {
        ShardingRangeProperties range = new ShardingRangeProperties();
        range.setCurrentQuarter(currentQuarter);
        range.setRetainedQuarterCount(3);

        DeploymentShardingProperties deployment = new DeploymentShardingProperties();
        deployment.setEnabled(tenantShardingEnabled);
        deployment.setShardingKey("tenant_id");

        return new ExpectedRouteService(
                new ShardingRangeValidator(range),
                deployment,
                false);
    }
}
