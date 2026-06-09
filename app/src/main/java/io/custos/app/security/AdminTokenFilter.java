package io.custos.app.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** 简易 Bearer 保护：/operator、/policy、/audit 路径需匹配 CUSTOS_ADMIN_TOKEN。 */
public final class AdminTokenFilter implements Filter {
    private final String expected;
    public AdminTokenFilter(String expected) { this.expected = expected; }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest r = (HttpServletRequest) req;
        String path = r.getRequestURI();
        boolean adminPath = path.startsWith("/operator") || path.startsWith("/policy") || path.startsWith("/audit");
        if (adminPath && (expected == null || expected.isBlank() || !("Bearer " + expected).equals(r.getHeader("Authorization")))) {
            ((HttpServletResponse) res).sendError(HttpServletResponse.SC_UNAUTHORIZED, "admin token required");
            return;
        }
        chain.doFilter(req, res);
    }
}
