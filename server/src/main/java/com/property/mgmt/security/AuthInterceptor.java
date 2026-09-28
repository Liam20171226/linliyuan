package com.property.mgmt.security;

import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.SysUser;
import com.property.mgmt.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final SysUserMapper sysUserMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        AuthUser auth = AuthContext.get();
        if (auth == null) {
            throw BizException.of(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        SysUser user = sysUserMapper.selectById(auth.getUserId());
        if (user == null || !Objects.equals(user.getStatus(), 1)) {
            throw BizException.of(ErrorCodes.UNAUTHORIZED, "账号已停用或无效，请重新登录");
        }
        String path = request.getRequestURI();
        if (mustChangePasswordBlocked(path, auth)) {
            String tip = auth.isAppPasswordSession()
                    ? "请先修改临时密码后再使用 App"
                    : "请先修改临时密码后再使用物业后台";
            throw BizException.of(ErrorCodes.MUST_CHANGE_PASSWORD, tip);
        }
        return true;
    }

    /**
     * 临时密码未改时：拦截物业 Web 密码会话、App 密码会话；小程序微信登录不受影响。
     */
    private boolean mustChangePasswordBlocked(String path, AuthUser auth) {
        if (!auth.isWebStaffSession() && !auth.isAppPasswordSession()) {
            return false;
        }
        if (auth.isPlatformAdmin() || "PLATFORM".equals(auth.getIdentityType())) {
            return false;
        }
        SysUser user = sysUserMapper.selectById(auth.getUserId());
        if (user == null || !Objects.equals(user.getMustChangePassword(), 1)) {
            return false;
        }
        if (path.endsWith("/auth/staff/change-password")
                || path.endsWith("/auth/app/change-password")
                || path.endsWith("/auth/identities")
                || path.endsWith("/auth/profile")) {
            return false;
        }
        if (auth.isAppPasswordSession()) {
            // App：除上方白名单外一律先改密
            return true;
        }
        if (path.contains("/api/v1/staff/") || path.endsWith("/auth/context/switch")) {
            return true;
        }
        return false;
    }
}
