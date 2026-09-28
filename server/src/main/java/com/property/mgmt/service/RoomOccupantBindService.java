package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.Room;
import com.property.mgmt.domain.RoomOccupant;
import com.property.mgmt.domain.SysUser;
import com.property.mgmt.mapper.RoomOccupantMapper;
import com.property.mgmt.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 住户绑定唯一写入口：同一用户对同一房屋同时最多一条 ACTIVE。
 * 已存在则改角色（并折叠重复行），不存在则插入。
 */
@Service
@RequiredArgsConstructor
public class RoomOccupantBindService {

    public static final Set<String> ROLES = Set.of(
            "OWNER", "OWNER_MEMBER", "TENANT", "TENANT_MEMBER");

    private static final List<String> ROLE_PRIORITY = List.of(
            "OWNER", "TENANT", "OWNER_MEMBER", "TENANT_MEMBER");

    private final RoomOccupantMapper roomOccupantMapper;
    private final SysUserMapper sysUserMapper;
    private final CommitteeService committeeService;

    /**
     * 取本房该用户一条 ACTIVE（若历史脏数据有多条，取 id 最小的一条）。
     */
    public RoomOccupant findActive(Long roomId, Long userId) {
        if (roomId == null || userId == null) {
            return null;
        }
        List<RoomOccupant> list = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getRoomId, roomId)
                .eq(RoomOccupant::getUserId, userId)
                .eq(RoomOccupant::getStatus, "ACTIVE")
                .orderByAsc(RoomOccupant::getId));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * @param source     USER_APPLY / STAFF_FIX / ADMIN_FIX / IMPORT / ROOM_CHANGE
     * @param approvedBy 审核人或操作人；可空
     */
    @Transactional
    public RoomOccupant bindActive(Room room, Long userId, String role, String source, Long approvedBy) {
        if (room == null || room.getId() == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "房屋无效");
        }
        if (userId == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "userId 必填");
        }
        if (!ROLES.contains(role)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "role 非法");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在");
        }
        if ("OWNER".equals(role) && !StringUtils.hasText(user.getIdCardNo())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "业主须有身份证");
        }

        Long roomId = room.getId();
        Long cid = room.getCommunityId();
        assertOwnerUnique(roomId, userId, role);
        assertNonOwnerCapacity(roomId, userId, role);

        LocalDateTime now = LocalDateTime.now();
        List<RoomOccupant> actives = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getRoomId, roomId)
                .eq(RoomOccupant::getUserId, userId)
                .eq(RoomOccupant::getStatus, "ACTIVE")
                .orderByAsc(RoomOccupant::getId));

        RoomOccupant kept;
        if (actives.isEmpty()) {
            kept = new RoomOccupant();
            kept.setCommunityId(cid);
            kept.setRoomId(roomId);
            kept.setUserId(userId);
            kept.setResidentRole(role);
            kept.setStatus("ACTIVE");
            kept.setSource(source);
            kept.setApprovedAt(now);
            kept.setApprovedBy(approvedBy);
            kept.setCreatedAt(now);
            kept.setUpdatedAt(now);
            roomOccupantMapper.insert(kept);
        } else {
            kept = actives.get(0);
            for (int i = 1; i < actives.size(); i++) {
                RoomOccupant dup = actives.get(i);
                dup.setStatus("INACTIVE");
                dup.setUpdatedAt(now);
                roomOccupantMapper.updateById(dup);
            }
            kept.setResidentRole(role);
            if (StringUtils.hasText(source)) {
                kept.setSource(source);
            }
            kept.setApprovedAt(now);
            if (approvedBy != null) {
                kept.setApprovedBy(approvedBy);
            }
            kept.setUpdatedAt(now);
            roomOccupantMapper.updateById(kept);
        }

        committeeService.revokeIfNoLongerOwner(cid, userId);
        return kept;
    }

    /** 停用本房该用户全部 ACTIVE（若有多条一并停用）。 */
    @Transactional
    public RoomOccupant deactivateActive(Long roomId, Long communityId, Long userId, Long occupantId) {
        LocalDateTime now = LocalDateTime.now();
        List<RoomOccupant> targets = new ArrayList<>();
        if (occupantId != null) {
            RoomOccupant o = roomOccupantMapper.selectById(occupantId);
            if (o != null && roomId.equals(o.getRoomId()) && "ACTIVE".equals(o.getStatus())) {
                targets.add(o);
            }
        } else {
            targets.addAll(roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                    .eq(RoomOccupant::getRoomId, roomId)
                    .eq(RoomOccupant::getUserId, userId)
                    .eq(RoomOccupant::getStatus, "ACTIVE")));
        }
        if (targets.isEmpty()) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "有效住户绑定不存在");
        }
        for (RoomOccupant o : targets) {
            if (communityId != null && !communityId.equals(o.getCommunityId())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "小区不符");
            }
            o.setStatus("INACTIVE");
            o.setUpdatedAt(now);
            roomOccupantMapper.updateById(o);
        }
        Long uid = targets.get(0).getUserId();
        committeeService.revokeIfNoLongerOwner(
                communityId != null ? communityId : targets.get(0).getCommunityId(), uid);
        return targets.get(0);
    }

    /**
     * 存量：同房同人多条 ACTIVE 折叠为一条。
     * 保留优先级 OWNER &gt; TENANT &gt; OWNER_MEMBER &gt; TENANT_MEMBER，同级留 id 较小者。
     */
    @Transactional
    public int dedupeActiveOccupants() {
        List<RoomOccupant> all = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getStatus, "ACTIVE")
                .orderByAsc(RoomOccupant::getId));
        Map<String, List<RoomOccupant>> groups = new LinkedHashMap<>();
        for (RoomOccupant o : all) {
            String key = o.getRoomId() + ":" + o.getUserId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
        }
        int deactivated = 0;
        LocalDateTime now = LocalDateTime.now();
        for (List<RoomOccupant> group : groups.values()) {
            if (group.size() <= 1) {
                continue;
            }
            group.sort(Comparator
                    .comparingInt((RoomOccupant o) -> roleRank(o.getResidentRole()))
                    .thenComparing(RoomOccupant::getId));
            RoomOccupant keep = group.get(0);
            for (int i = 1; i < group.size(); i++) {
                RoomOccupant dup = group.get(i);
                dup.setStatus("INACTIVE");
                dup.setUpdatedAt(now);
                roomOccupantMapper.updateById(dup);
                deactivated++;
            }
            committeeService.revokeIfNoLongerOwner(keep.getCommunityId(), keep.getUserId());
        }
        return deactivated;
    }

    private void assertOwnerUnique(Long roomId, Long userId, String role) {
        if (!"OWNER".equals(role)) {
            return;
        }
        long others = roomOccupantMapper.selectCount(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getRoomId, roomId)
                .eq(RoomOccupant::getResidentRole, "OWNER")
                .eq(RoomOccupant::getStatus, "ACTIVE")
                .ne(RoomOccupant::getUserId, userId));
        if (others > 0) {
            throw BizException.of(ErrorCodes.OWNER_EXISTS, "该房已有业主");
        }
    }

    private void assertNonOwnerCapacity(Long roomId, Long userId, String role) {
        if ("OWNER".equals(role)) {
            return;
        }
        boolean alreadyNonOwner = roomOccupantMapper.selectCount(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getRoomId, roomId)
                .eq(RoomOccupant::getUserId, userId)
                .ne(RoomOccupant::getResidentRole, "OWNER")
                .eq(RoomOccupant::getStatus, "ACTIVE")) > 0;
        if (alreadyNonOwner) {
            return;
        }
        // 本人当前是业主或尚无绑定：占一个非业主名额
        long others = roomOccupantMapper.selectCount(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getRoomId, roomId)
                .ne(RoomOccupant::getResidentRole, "OWNER")
                .eq(RoomOccupant::getStatus, "ACTIVE")
                .ne(RoomOccupant::getUserId, userId));
        if (others >= 10) {
            throw BizException.of(ErrorCodes.OCCUPANT_LIMIT, "非业主人数已达上限 10");
        }
    }

    private static int roleRank(String role) {
        int i = ROLE_PRIORITY.indexOf(role);
        return i < 0 ? 99 : i;
    }

    /** 角色优先级比较：负数表示 a 优于 b（OWNER 最优）。 */
    public static int compareRolePriority(String a, String b) {
        return Integer.compare(roleRank(a), roleRank(b));
    }
}
