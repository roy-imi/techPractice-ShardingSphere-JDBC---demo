package com.example.assetinspection.migration.config;

import com.example.assetinspection.migration.source.JdbcLegacyInspectionRecordSource;
import com.example.assetinspection.migration.source.LegacyInspectionRecordSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 只在执行历史迁移时创建旧库读取客户端。
 *
 * <p>这里有一个容易踩坑的设计点：不能直接声明第二个 {@link javax.sql.DataSource}
 * Bean。Spring Boot 的主数据源自动配置带有“容器里还没有 DataSource”的条件，过早注册
 * legacyDataSource 可能让真正的 ShardingSphere DataSource 不再创建。因此本配置把
 * HikariDataSource 封装进 LegacyInspectionRecordSource，对 Spring 只暴露“旧表读取能力”。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "demo.migration", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(LegacyMigrationProperties.class)
public class LegacyMigrationConfiguration {

    /**
     * 创建一个与业务分片数据源完全隔离的小型只读连接池。
     *
     * @param properties application-sharding.properties 中的旧库连接属性
     * @return 仅提供游标读取与数量统计的旧表访问对象
     */
    @Bean(destroyMethod = "close")
    public LegacyInspectionRecordSource legacyInspectionRecordSource(
            LegacyMigrationProperties properties) {
        // 在启动阶段尽早发现漏配，避免迁移跑到一半才因空 JDBC URL 失败。
        requireText(properties.getJdbcUrl(), "demo.migration.legacy.jdbc-url");
        requireText(properties.getUsername(), "demo.migration.legacy.username");

        // HikariConfig 只属于旧库读取端，不会污染主数据源的连接池参数。
        HikariConfig hikariConfig = new HikariConfig();
        // 设置旧单库地址，例如 127.0.0.1:13306/asset_legacy。
        hikariConfig.setJdbcUrl(properties.getJdbcUrl());
        // 使用旧库迁移账号。
        hikariConfig.setUsername(properties.getUsername());
        // 密码允许为空是为了兼容本地临时库；生产环境不应使用空密码。
        hikariConfig.setPassword(properties.getPassword());
        // 显式设置驱动，连接失败时错误信息更容易定位。
        hikariConfig.setDriverClassName(properties.getDriverClassName());
        // 池名会出现在日志和监控指标里，便于与四个分片连接池区分。
        hikariConfig.setPoolName("legacy-migration-read-pool");
        // 迁移任务不能无限扩张连接数冲击仍在承载流量的旧库。
        hikariConfig.setMaximumPoolSize(validatePoolSize(properties.getMaximumPoolSize()));
        // 不长期保留空闲连接；批次之间可释放旧库资源。
        hikariConfig.setMinimumIdle(0);
        // 旧端只允许读取，能防止迁移代码误更新历史单表。
        hikariConfig.setReadOnly(true);
        // 每次 SELECT 自身提交，不尝试加入目标分片库的本地事务。
        hikariConfig.setAutoCommit(true);

        // 数据源由读取对象持有；Bean 销毁时 close() 会关闭这个连接池。
        HikariDataSource legacyDataSource = new HikariDataSource(hikariConfig);
        return new JdbcLegacyInspectionRecordSource(legacyDataSource);
    }

    /** 校验必填字符串，错误消息直接指出应补哪个配置键。 */
    private void requireText(String value, String propertyName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(propertyName + " 不能为空");
        }
    }

    /** 限制连接池范围，Demo 最多允许五个旧库读取连接。 */
    private int validatePoolSize(int value) {
        if (value < 1 || value > 5) {
            throw new IllegalStateException(
                    "demo.migration.legacy.maximum-pool-size 必须在 1 ~ 5 之间");
        }
        return value;
    }
}
