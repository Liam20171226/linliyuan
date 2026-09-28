package com.property.mgmt.common;

/**
 * 登录密码默认值（Web 管理端与 App 共用 password_hash）：
 * 新建账号、授予客服岗、重置 Web/App 密码时若不指定，即使用该密码。
 * 设置/重置后一律置 must_change_password=1，当事人首次登录 Web 或 App 会被强制改密。
 */
public final class DefaultWebPassword {

    public static final String VALUE = "linliyuan123";

    private DefaultWebPassword() {}
}
