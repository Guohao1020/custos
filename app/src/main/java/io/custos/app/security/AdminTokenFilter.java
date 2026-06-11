package io.custos.app.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** 简易 Bearer 保护：/operator、/policy、/audit、/resources、/token、/approvals、/leases、/monitor、
 *  /actuator/prometheus 路径需匹配 CUSTOS_ADMIN_TOKEN。/actuator/health 故意不门控（开放探活）。 */
public final class AdminTokenFilter implements Filter {
    private final String expected;
    public AdminTokenFilter(String expected) { this.expected = expected; }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest r = (HttpServletRequest) req;
        // CORS 预检（OPTIONS）必须在鉴权前直接放行：预检不带 Authorization，交由 Spring CORS 处理回带头，
        // 否则会被下面的 adminPath 判定拦成 401，浏览器永远拿不到 Access-Control-Allow-Origin。
        if ("OPTIONS".equalsIgnoreCase(r.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        String path = r.getRequestURI();
        boolean adminPath = path.startsWith("/operator") || path.startsWith("/policy")
                || path.startsWith("/audit") || path.startsWith("/resources") || path.startsWith("/token")
                || path.startsWith("/approvals") || path.startsWith("/leases") || path.startsWith("/monitor")
                // 只门控 prometheus，不写 /actuator 前缀以免连带拦住开放的 /actuator/health
                || path.startsWith("/actuator/prometheus");
        if (adminPath && (expected == null || expected.isBlank() || !("Bearer " + expected).equals(r.getHeader("Authorization")))) {
            ((HttpServletResponse) res).sendError(HttpServletResponse.SC_UNAUTHORIZED, "admin token required");
            return;
        }
        chain.doFilter(req, res);
    }
}
