package com.example.assetinspection.context;

/**
 * 当前请求的可信租户上下文。
 *
 * <p>真实系统通常由网关签名令牌或登录会话解析 tenant_id。
 * Demo 用 X-Tenant-Id 请求头模拟这个结果，但仍不允许客户端把 tenant_id
 * 塞进请求体后由 Mapper 原样信任。</p>
 */
public final class TenantContextHolder {

    // ThreadLocal 让同一个请求线程中的 Service/Mapper 都能读取当前租户。
    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<Long>();

    // 工具类不需要实例化。
    private TenantContextHolder() {
    }

    /** 保存过滤器已经校验过的租户 ID。 */
    public static void setTenantId(Long tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * 获取当前租户；若上下文不存在则立即失败，防止 SQL 在缺少 tenant_id 时全路由。
     */
    public static Long requireTenantId() {
        Long tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException("当前线程缺少租户上下文");
        }
        return tenantId;
    }

    /**
     * 请求结束必须 remove，而不是 set(null)，否则容器线程复用时可能串租户或泄漏对象。
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
