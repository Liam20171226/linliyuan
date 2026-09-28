package com.property.mgmt.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (StringUtils.hasText(token)) {
                try {
                    Claims claims = jwtService.parse(token);
                    if (!"SESSION".equals(claims.get("type", String.class))) {
                        AuthUser user = jwtService.toAuthUser(claims);
                        if (user != null) {
                            AuthContext.set(user);
                        }
                    }
                } catch (Exception ignored) {
                    // 无效 token 不在此拦死；受保护接口由拦截器校验
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    /** Bearer 头，或图片等无法带 Header 的场景用 ?token= */
    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        String q = request.getParameter("token");
        return StringUtils.hasText(q) ? q.trim() : null;
    }
}
