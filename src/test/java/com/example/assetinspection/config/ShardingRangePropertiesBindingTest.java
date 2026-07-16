package com.example.assetinspection.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDate;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证日期配置不会因为开发机的中文 Locale 而改变解析结果。 */
class ShardingRangePropertiesBindingTest {

    @Test
    void shouldBindIsoDateUnderChineseLocale() {
        // 保存进程原始 Locale，避免本测试污染同 JVM 中的其他测试。
        Locale originalLocale = Locale.getDefault();
        try {
            // 主动模拟中文 macOS/JDK 环境，覆盖真实联调时发现的可移植性问题。
            Locale.setDefault(Locale.CHINA);

            // MockEnvironment 只承载待绑定的两项配置，不需要启动完整 Spring 容器或数据库。
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("demo.sharding.current-quarter", "2026Q3")
                    .withProperty("demo.sharding.retained-quarter-count", "3")
                    .withProperty("demo.sharding.purge-grace-days", "3")
                    .withProperty("demo.sharding.supported-start-date", "2026-01-01")
                    .withProperty("demo.sharding.supported-end-date", "2026-12-31");

            // Binder 使用与 Spring Boot @ConfigurationProperties 相同的属性绑定机制。
            ShardingRangeProperties properties = Binder.get(environment)
                    .bind("demo.sharding", Bindable.of(ShardingRangeProperties.class))
                    .orElseThrow(() -> new AssertionError("分片日期配置应当能够绑定"));

            // 两个断言证明输入始终按 ISO yyyy-MM-dd 解释，而不是中文短日期格式。
            assertThat(properties.getCurrentQuarter()).isEqualTo("2026Q3");
            assertThat(properties.getRetainedQuarterCount()).isEqualTo(3);
            assertThat(properties.getPurgeGraceDays()).isEqualTo(3);
            assertThat(properties.getSupportedStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(properties.getSupportedEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        } finally {
            // 无论断言是否成功都恢复全局 Locale，确保测试可独立、可重复执行。
            Locale.setDefault(originalLocale);
        }
    }
}
