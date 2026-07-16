package com.example.assetinspection;

import com.example.assetinspection.config.DeploymentShardingProperties;
import com.example.assetinspection.config.ShardingRangeProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 资产点巡检 Demo 的唯一启动入口。
 *
 * <p>最重要的学习点：业务代码只依赖标准 DataSource 和逻辑表名，
 * 从 legacy 基线切到 product 产品形态时，Controller、Service、Mapper 都不需要改。</p>
 */
@SpringBootApplication
// 扫描 MyBatis Mapper 接口，避免在每个接口上重复写 @Mapper。
@MapperScan("com.example.assetinspection.mapper")
// 把部署形态和季度窗口都绑定成强类型对象，避免业务代码到处读取字符串配置。
@EnableConfigurationProperties({
        ShardingRangeProperties.class,
        DeploymentShardingProperties.class
})
public class AssetInspectionApplication {

    /**
     * JVM 启动入口。
     *
     * @param args Spring Boot 命令行参数，例如 --spring.profiles.active=legacy
     */
    public static void main(String[] args) {
        // 交给 Spring Boot 创建 Web 容器、DataSource、事务管理器和 MyBatis 代理。
        SpringApplication.run(AssetInspectionApplication.class, args);
    }
}
