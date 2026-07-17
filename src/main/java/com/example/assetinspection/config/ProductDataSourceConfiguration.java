package com.example.assetinspection.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * 根据“部署时开关”选择一份不可变的 ShardingSphere 规则。
 *
 * <p>这里故意不在一次运行中动态替换 DataSource。连接池、分片元数据和存量数据位置
 * 都是在启动阶段确定的；若客户要从标准版升级到分库版，应走迁移、对账、停写切换和回滚预案。</p>
 */
@Configuration
@Profile({"product", "sharding"})
@EnableConfigurationProperties(DeploymentShardingProperties.class)
public class ProductDataSourceConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductDataSourceConfiguration.class);

    private static final String SHARDING_SPHERE_DRIVER =
            "org.apache.shardingsphere.driver.ShardingSphereDriver";

    // Spring 先解析 CURRENT_QUARTER，再通过此 JVM 属性把同一值交给 ShardingSphere YAML。
    private static final String CURRENT_QUARTER_SYSTEM_PROPERTY =
            "asset.demo.current-quarter";

    private static final String SHARED_DATABASE_RULE =
            "jdbc:shardingsphere:classpath:shardingsphere-standard.yaml?placeholder-type=system_props";

    private static final String TENANT_DATABASE_RULE =
            "jdbc:shardingsphere:classpath:shardingsphere-tenant.yaml?placeholder-type=system_props";

    /**
     * Spring 容器启动时只创建一次逻辑 DataSource。
     *
     * @param properties 产品部署参数
     * @return 交给 MyBatis 和事务管理器使用的唯一逻辑数据源
     */
    @Bean(destroyMethod = "close")
    public DataSource dataSource(DeploymentShardingProperties properties,
                                 ShardingRangeProperties rangeProperties) {
        // 先做失败即停机的配置校验，绝不带着错误数据库分片键继续提供服务。
        properties.validateForStartup();

        // 两层配置必须共用一个 current-quarter，不能让业务校验认为 Q4、路由算法仍认为 Q3。
        if (rangeProperties.getCurrentQuarter() == null
                || rangeProperties.getCurrentQuarter().trim().isEmpty()) {
            throw new IllegalStateException("demo.sharding.current-quarter 不能为空");
        }
        System.setProperty(
                CURRENT_QUARTER_SYSTEM_PROPERTY,
                rangeProperties.getCurrentQuarter().trim());

        // true 选择两分片库规则；false 选择一个共享库规则。
        String selectedRule = properties.isEnabled()
                ? TENANT_DATABASE_RULE
                : SHARED_DATABASE_RULE;

        // 外层 Hikari 只持有 ShardingSphere 逻辑连接；真实四/两个物理池由 YAML 分别管理。
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName(SHARDING_SPHERE_DRIVER);
        hikariConfig.setJdbcUrl(selectedRule);
        hikariConfig.setPoolName("product-logical-pool");
        hikariConfig.setMaximumPoolSize(8);
        hikariConfig.setMinimumIdle(1);

        LOGGER.info("产品部署模式已锁定为 {}，tenant database sharding enabled={}，规则={}",
                properties.deploymentMode(), properties.isEnabled(), selectedRule);
        return new HikariDataSource(hikariConfig);
    }
}
