package com.property.mgmt.security;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthUser {
    private Long userId;
    private String identityType;
    private Long communityId;
    private Long roomId;
    private String staffRole;
    private boolean platformAdmin;
    /** 物业 Web 密码登录会话：临时密码未改时仅拦截此类会话，小程序微信登录不受影响 */
    private boolean webStaffSession;
    /** App 密码登录会话：临时密码未改时拦截业务 API（改密/身份列表除外） */
    private boolean appPasswordSession;
}
