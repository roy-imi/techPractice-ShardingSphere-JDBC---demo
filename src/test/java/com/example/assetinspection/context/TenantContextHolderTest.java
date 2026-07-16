package com.example.assetinspection.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证租户 ThreadLocal 不会在请求/线程之间泄漏。 */
class TenantContextHolderTest {

    @AfterEach
    void alwaysClearCurrentTestThread() {
        // 即使断言失败，也不能让当前测试线程的租户污染下一条用例。
        TenantContextHolder.clear();
    }

    @Test
    void shouldReturnTenantStoredInCurrentThreadAndRemoveIt() {
        TenantContextHolder.setTenantId(2L);
        assertThat(TenantContextHolder.requireTenantId()).isEqualTo(2L);

        TenantContextHolder.clear();
        assertThatThrownBy(TenantContextHolder::requireTenantId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少租户上下文");
    }

    @Test
    void shouldNotExposeParentThreadTenantToAnotherThread() throws InterruptedException {
        TenantContextHolder.setTenantId(3L);
        AtomicReference<Throwable> childFailure = new AtomicReference<Throwable>();

        Thread child = new Thread(() -> {
            try {
                TenantContextHolder.requireTenantId();
            } catch (Throwable throwable) {
                childFailure.set(throwable);
            } finally {
                TenantContextHolder.clear();
            }
        });
        child.start();
        child.join();

        assertThat(childFailure.get()).isInstanceOf(IllegalStateException.class);
        // 子线程的 clear 不应清除父线程上下文。
        assertThat(TenantContextHolder.requireTenantId()).isEqualTo(3L);
    }
}
