package com.example.assetinspection.migration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 历史单表迁移专用的旧库连接配置。
 *
 * <p>这些属性只在 {@code demo.migration.enabled=true} 时被使用。旧库连接与
 * {@code spring.datasource} 完全分离，避免把旧库误当成业务主数据源。</p>
 */
@ConfigurationProperties(prefix = "demo.migration.legacy")
public class LegacyMigrationProperties {

    // 旧单库 asset_legacy 的 JDBC 地址。
    private String jdbcUrl;

    // 旧库只读账号；生产环境应只授予 SELECT 权限。
    private String username;

    // 旧库账号密码；真实项目应从密钥系统注入，不能提交到代码仓库。
    private String password;

    // MySQL 8 JDBC 驱动类名。
    private String driverClassName = "com.mysql.cj.jdbc.Driver";

    // 迁移读取连接池刻意保持很小，避免批处理把线上旧库连接打满。
    private int maximumPoolSize = 2;

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }
}
