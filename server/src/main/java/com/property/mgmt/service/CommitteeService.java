package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.CommitteeMember;
import com.property.mgmt.domain.RoomOccupant;
import com.property.mgmt.domain.SysUser;
import com.property.mgmt.mapper.CommitteeMemberMapper;
import com.property.mgmt.mapper.RoomOccupantMapper;
import com.property.mgmt.mapper.SysUserMapper;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommitteeService {

    private final CommitteeMemberMapper committeeMemberMapper;
    private final SysUserMapper sysUserMapper;
    private final RoomOccupantMapper roomOccupantMapper;

    public List<CommitteeMember> list() {
        Long cid = StaffGuard.communityId();
        return committeeMemberMapper.selectList(new LambdaQueryWrapper<CommitteeMember>()
                .eq(CommitteeMember::getCommunityId, cid)
                .orderByDesc(CommitteeMember::getId));
    }

    /** userId → ACTIVE title（本小区） */
    public Map<Long, String> activeTitleByUser(Long communityId) {
        List<CommitteeMember> list = committeeMemberMapper.selectList(new LambdaQueryWrapper<CommitteeMember>()
                .eq(CommitteeMember::getCommunityId, communityId)
                .eq(CommitteeMember::getStatus, "ACTIVE"));
        Map<Long, String> map = new HashMap<>();
        for (CommitteeMember cm : list) {
            map.put(cm.getUserId(), cm.getTitle());
        }
        return map;
    }

    public boolean isActiveOwner(Long communityId, Long userId) {
        if (communityId == null || userId == null) {
            return false;
        }
        return roomOccupantMapper.selectCount(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getCommunityId, communityId)
                .eq(RoomOccupant::getUserId, userId)
                .eq(RoomOccupant::getResidentRole, "OWNER")
                .eq(RoomOccupant::getStatus, "ACTIVE")) > 0;
    }

    public void requireActiveOwner(Long communityId, Long userId) {
        if (!isActiveOwner(communityId, userId)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "业委会成员须为本小区业主");
        }
    }

    /**
     * 小区后台任命/调整业委会：须为本小区 ACTIVE 业主；title 空则解除。
     */
    @Transactional
    public Map<String, Object> assignForOwner(Long userId, String titleOrNull) {
        Long cid = StaffGuard.communityId();
        if (userId == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "userId 必填");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在");
        }
        if (!StringUtils.hasText(titleOrNull)) {
            revokeInCommunity(cid, userId);
            return Map.of("cleared", true, "userId", userId);
        }
        String title = titleOrNull.trim();
        validateTitle(title);
        requireActiveOwner(cid, userId);
        return upsert(cid, userId, title);
    }

    @Transactional
    public CommitteeMember create(Long userId, String mobile, String realName, String title) {
        Long cid = StaffGuard.communityId();
        validateTitle(title);
        SysUser user;
        if (userId != null) {
            user = sysUserMapper.selectById(userId);
            if (user == null) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在");
            }
        } else {
            if (!StringUtils.hasText(mobile) || !StringUtils.hasText(realName)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "请提供 userId 或 mobile+name");
            }
            user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, mobile.trim()));
            if (user == null) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "请先绑定本小区业主，再任命业委会");
            } else if (!StringUtils.hasText(user.getRealName())) {
                user.setRealName(realName.trim());
                user.setUpdatedAt(LocalDateTime.now());
                sysUserMapper.updateById(user);
            }
        }
        requireActiveOwner(cid, user.getId());
        Map<String, Object> r = upsert(cid, user.getId(), title);
        Long id = ((Number) r.get("id")).longValue();
        return committeeMemberMapper.selectById(id);
    }

    public void update(Long id, String title, String status) {
        Long cid = StaffGuard.communityId();
        CommitteeMember cm = committeeMemberMapper.selectById(id);
        if (cm == null || !cm.getCommunityId().equals(cid)) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "职务不存在");
        }
        if (StringUtils.hasText(status) && !"ACTIVE".equals(status)) {
            cm.setStatus(status.trim());
            cm.setUpdatedAt(LocalDateTime.now());
            committeeMemberMapper.updateById(cm);
            return;
        }
        if (StringUtils.hasText(title)) {
            validateTitle(title);
            requireActiveOwner(cid, cm.getUserId());
            if ("DIRECTOR".equals(title) && !"DIRECTOR".equals(cm.getTitle())) {
                assertNoOtherDirector(cid, cm.getId());
            }
            cm.setTitle(title);
        }
        if (StringUtils.hasText(status)) {
            cm.setStatus(status);
        }
        cm.setUpdatedAt(LocalDateTime.now());
        committeeMemberMapper.updateById(cm);
    }

    /** 若该用户在本小区已无 ACTIVE 业主，则解除业委会（解绑/改角色后调用）。 */
    @Transactional
    public void revokeIfNoLongerOwner(Long communityId, Long userId) {
        if (!isActiveOwner(communityId, userId)) {
            revokeInCommunity(communityId, userId);
        }
    }

    /**
     * 存量清理：ACTIVE 业委会但非本小区业主 → 停用业委会任职（无其他住户绑定时小程序表现为游客）。
     */
    @Transactional
    public int purgeNonOwnerCommittees() {
        List<CommitteeMember> actives = committeeMemberMapper.selectList(new LambdaQueryWrapper<CommitteeMember>()
                .eq(CommitteeMember::getStatus, "ACTIVE"));
        int n = 0;
        LocalDateTime now = LocalDateTime.now();
        for (CommitteeMember cm : actives) {
            if (isActiveOwner(cm.getCommunityId(), cm.getUserId())) {
                continue;
            }
            cm.setStatus("INACTIVE");
            cm.setUpdatedAt(now);
            committeeMemberMapper.updateById(cm);
            n++;
        }
        return n;
    }

    private Map<String, Object> upsert(Long communityId, Long userId, String title) {
        LocalDateTime now = LocalDateTime.now();
        CommitteeMember active = committeeMemberMapper.selectOne(new LambdaQueryWrapper<CommitteeMember>()
                .eq(CommitteeMember::getCommunityId, communityId)
                .eq(CommitteeMember::getUserId, userId)
                .eq(CommitteeMember::getStatus, "ACTIVE"));
        if ("DIRECTOR".equals(title)) {
            assertNoOtherDirector(communityId, active == null ? null : active.getId());
        }
        if (active != null) {
            active.setTitle(title);
            active.setUpdatedAt(now);
            committeeMemberMapper.updateById(active);
            return Map.of("updated", true, "id", active.getId(), "title", title);
        }
        CommitteeMember cm = new CommitteeMember();
        cm.setCommunityId(communityId);
        cm.setUserId(userId);
        cm.setTitle(title);
        cm.setStatus("ACTIVE");
        cm.setCreatedAt(now);
        cm.setUpdatedAt(now);
        committeeMemberMapper.insert(cm);
        return Map.of("created", true, "id", cm.getId(), "title", title);
    }

    private void revokeInCommunity(Long communityId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<CommitteeMember> list = committeeMemberMapper.selectList(new LambdaQueryWrapper<CommitteeMember>()
                .eq(CommitteeMember::getCommunityId, communityId)
                .eq(CommitteeMember::getUserId, userId)
                .eq(CommitteeMember::getStatus, "ACTIVE"));
        for (CommitteeMember cm : list) {
            cm.setStatus("INACTIVE");
            cm.setUpdatedAt(now);
            committeeMemberMapper.updateById(cm);
        }
    }

    private void assertNoOtherDirector(Long communityId, Long excludeId) {
        Long directors = committeeMemberMapper.selectCount(new LambdaQueryWrapper<CommitteeMember>()
                .eq(CommitteeMember::getCommunityId, communityId)
                .eq(CommitteeMember::getTitle, "DIRECTOR")
                .eq(CommitteeMember::getStatus, "ACTIVE")
                .ne(excludeId != null, CommitteeMember::getId, excludeId));
        if (directors > 0) {
            throw BizException.of(ErrorCodes.DIRECTOR_EXISTS, "小区已有有效主任");
        }
    }

    private void validateTitle(String title) {
        if (!"ACTIVIST".equals(title) && !"DIRECTOR".equals(title) && !"MEMBER".equals(title)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "title 无效");
        }
    }
}
