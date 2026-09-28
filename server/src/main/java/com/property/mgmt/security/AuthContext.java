package com.property.mgmt.security;

public final class AuthContext {
    private static final ThreadLocal<AuthUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(AuthUser user) {
        HOLDER.set(user);
    }

    public static AuthUser get() {
        return HOLDER.get();
    }

    public static AuthUser require() {
        AuthUser u = HOLDER.get();
        if (u == null) {
            throw com.property.mgmt.common.BizException.of(
                    com.property.mgmt.common.ErrorCodes.UNAUTHORIZED, "未登录");
        }
        return u;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
