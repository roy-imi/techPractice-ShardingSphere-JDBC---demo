package com.example.assetinspection.context;

import com.example.assetinspection.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 为正常点巡检 API 建立租户上下文。
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    // Demo 约定的租户头；生产应由鉴权信息解析，而不是直接相信任意请求头。
    public static final String TENANT_HEADER = "X-Tenant-Id";

    // Filter 运行在 DispatcherServlet 之前；显式调用 MVC 异常解析器，才能复用 GlobalExceptionHandler 的 JSON 格式。
    private final HandlerExceptionResolver exceptionResolver;

    public TenantContextFilter(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        // Spring MVC 已注册名为 handlerExceptionResolver 的组合解析器，其中包含 @RestControllerAdvice。
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只保护正常业务路径；debug/admin 接口有自己的显式参数和安全提示。
        return !request.getRequestURI().startsWith("/api/inspection-records");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 从请求头读取网关已经解析出的租户标识。
            String rawTenantId = request.getHeader(TENANT_HEADER);
            if (rawTenantId == null || rawTenantId.trim().isEmpty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "TENANT_HEADER_REQUIRED",
                        "缺少 X-Tenant-Id 请求头；正常查询必须明确租户，避免跨库全路由");
            }

            final long tenantId;
            try {
                // Long.parseLong 同时拒绝小数和非数字字符。
                tenantId = Long.parseLong(rawTenantId.trim());
            } catch (NumberFormatException ex) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "INVALID_TENANT_ID",
                        "X-Tenant-Id 必须是正整数");
            }

            if (tenantId <= 0L) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "INVALID_TENANT_ID",
                        "X-Tenant-Id 必须大于 0");
            }

            // 只有通过格式和取值校验后才写入上下文。
            TenantContextHolder.setTenantId(tenantId);
            // 继续执行 Controller、Service 和 MyBatis。
            filterChain.doFilter(request, response);
        } catch (BusinessException ex) {
            // 直接从 Filter 抛异常不会自动进入 @RestControllerAdvice，因此在此显式委托给 MVC 解析器。
            exceptionResolver.resolveException(request, response, null, ex);
        } finally {
            // 无论业务成功、异常还是客户端断开，都必须清理 ThreadLocal。
            TenantContextHolder.clear();
        }
    }
}
