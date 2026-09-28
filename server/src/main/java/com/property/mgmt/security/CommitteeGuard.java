package com.property.mgmt.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.CommitteeMember;
import com.property.mgmt.mapper.CommitteeMemberMapper;

/**
 * 业委会权限：本小区须有 ACTIVE 业委会任职。
 * 身份类型不限（住户/业委会/物业均可）：同一人常兼物业岗与业委会，
 * 只要 JWT 小区下有有效任职即可查名册等业委接口。
 */
public final class CommitteeGuard {
    private static volatile CommitteeMemberMapper committeeMemberMapper;

    private CommitteeGuard() {}

    public static void init(CommitteeMemberMapper mapper) {
        committeeMemberMapper = mapper;
    }

    public static AuthUser requireCommittee() {
        AuthUser u = AuthContext.require();
        if (u.getCommunityId() == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先选择小区");
        }
        if ("PLATFORM".equals(u.getIdentityType()) || u.isPlatformAdmin()) {
            return u;
        }
        if (hasActiveMembership(u.getUserId(), u.getCommunityId())) {
            return u;
        }
        throw BizException.of(ErrorCodes.FORBIDDEN, "需要本小区业委会任职");
    }

    public static Long communityId() {
        return requireCommittee().getCommunityId();
    }

    public static boolean hasActiveMembership(Long userId, Long communityId) {
        if (userId == null || communityId == null || committeeMemberMapper == null) {
            return false;
        }
        Long cnt = committeeMemberMapper.selectCount(new LambdaQueryWrapper<CommitteeMember>()
                .eq(CommitteeMember::getUserId, userId)
                .eq(CommitteeMember::getCommunityId, communityId)
                .eq(CommitteeMember::getStatus, "ACTIVE"));
        return cnt != null && cnt > 0;
    }
}
