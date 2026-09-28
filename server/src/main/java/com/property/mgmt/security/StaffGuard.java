package com.property.mgmt.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.common.StaffRoles;
import com.property.mgmt.domain.StaffCommunity;
import com.property.mgmt.mapper.StaffCommunityMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 物业接口门禁：物业身份须本小区 ACTIVE 任职；平台进小区可代管。
 */
public final class StaffGuard {
    private static volatile StaffCommunityMapper staffCommunityMapper;

    private StaffGuard() {}

    public static void init(StaffCommunityMapper mapper) {
        staffCommunityMapper = mapper;
    }

    public static AuthUser requireStaff() {
        AuthUser u = AuthContext.require();
        if (u.getCommunityId() == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先选择小区");
        }
        if (u.isPlatformAdmin() || "PLATFORM".equals(u.getIdentityType())) {
            return u;
        }
        if ("STAFF".equals(u.getIdentityType())) {
            List<String> roles = activeRoles(u.getUserId(), u.getCommunityId());
            if (roles.isEmpty()) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "物业任职已失效，请重新登录或切换身份");
            }
            u.setStaffRole(StaffRoles.primary(roles));
            return u;
        }
        throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换为物业身份并选择小区");
    }

    public static Long communityId() {
        return requireStaff().getCommunityId();
    }

    /** 物业经理，或已进入小区的平台管理员。 */
    public static void requireManagerOrPlatform(AuthUser u) {
        if (u == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "仅物业经理或平台管理员可操作");
        }
        if (u.isPlatformAdmin() || "PLATFORM".equals(u.getIdentityType())) {
            return;
        }
        List<String> roles = activeRoles(u.getUserId(), u.getCommunityId());
        if (roles.contains(StaffRoles.PROPERTY_MANAGER)) {
            u.setStaffRole(StaffRoles.PROPERTY_MANAGER);
            return;
        }
        throw BizException.of(ErrorCodes.FORBIDDEN, "仅物业经理或平台管理员可操作");
    }

    public static boolean isPlatformScope(AuthUser u) {
        return u != null && (u.isPlatformAdmin() || "PLATFORM".equals(u.getIdentityType()));
    }

    /** 可制定巡检计划：物业经理、客服，或进小区的平台管理员。 */
    public static void requirePlanner(AuthUser u) {
        if (u == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "仅物业经理或客服可制定巡检计划");
        }
        if (u.isPlatformAdmin() || "PLATFORM".equals(u.getIdentityType())) {
            return;
        }
        List<String> roles = activeRoles(u.getUserId(), u.getCommunityId());
        if (roles.contains(StaffRoles.PROPERTY_MANAGER) || roles.contains(StaffRoles.CUSTOMER_SERVICE)) {
            return;
        }
        throw BizException.of(ErrorCodes.FORBIDDEN, "仅物业经理或客服可制定巡检计划");
    }

    /** 可执行巡检：保安 / 保洁 / 机电维修 / 绿化 */
    public static boolean canExecuteInspect(Long userId, Long communityId) {
        List<String> roles = activeRoles(userId, communityId);
        for (String r : roles) {
            if (StaffRoles.SECURITY.equals(r) || StaffRoles.CLEANING.equals(r)
                    || StaffRoles.FACILITY_MAINT.equals(r) || StaffRoles.LANDSCAPING.equals(r)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasManagerRole(Long userId, Long communityId) {
        return activeRoles(userId, communityId).contains(StaffRoles.PROPERTY_MANAGER);
    }

    public static List<String> activeRoles(Long userId, Long communityId) {
        if (userId == null || communityId == null || staffCommunityMapper == null) {
            return List.of();
        }
        List<StaffCommunity> rows = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getUserId, userId)
                .eq(StaffCommunity::getCommunityId, communityId)
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        List<String> roles = new ArrayList<>();
        for (StaffCommunity sc : rows) {
            String r = StaffRoles.normalize(sc.getStaffRole());
            if (r != null && !roles.contains(r)) {
                roles.add(r);
            }
        }
        return StaffRoles.sortRoles(roles);
    }
}
