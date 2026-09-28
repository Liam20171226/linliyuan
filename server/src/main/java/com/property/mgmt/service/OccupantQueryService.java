package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.DefaultWebPassword;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.common.RoomPaths;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.CommitteeGuard;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OccupantQueryService {

    private final RoomOccupantMapper roomOccupantMapper;
    private final SysUserMapper sysUserMapper;
    private final RoomMapper roomMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final FloorMapper floorMapper;
    private final CommunityMapper communityMapper;
    private final CommitteeService committeeService;
    private final RoomOccupantBindService roomOccupantBindService;
    private final StaffCommunityMapper staffCommunityMapper;
    private final CommitteeMemberMapper committeeMemberMapper;
    private final PasswordEncoder passwordEncoder;

    public Map<String, Object> staffList(Long buildingId, Long unitId, Long roomId, String roomNo, String mobile,
                                         String realName, String role, int page, int pageSize) {
        return list(StaffGuard.communityId(), buildingId, unitId, roomId, roomNo, mobile, realName, null, role, false, page, pageSize);
    }

    public Map<String, Object> committeeList(Long buildingId, Long unitId, Long roomId, String roomNo, String mobile,
                                             String realName, String role, int page, int pageSize) {
        return list(CommitteeGuard.communityId(), buildingId, unitId, roomId, roomNo, mobile, realName, null, role, true, page, pageSize);
    }

    public Map<String, Object> platformList(Long communityId, String mobile, String roomNo, String realName,
                                            String idCardNo, int page, int pageSize) {
        var u = AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台管理员权限");
        }
        return list(communityId, null, null, null, roomNo, mobile, realName, idCardNo, null, false, page, pageSize);
    }

    private Map<String, Object> list(Long communityId, Long buildingId, Long unitId, Long roomId, String roomNo,
                                     String mobile, String realName, String idCardNo, String role,
                                     boolean maskIdCard, int page, int pageSize) {
        Set<Long> roomFilter = null;
        if (roomId != null) {
            roomFilter = Set.of(roomId);
        } else if (buildingId != null || unitId != null || StringUtils.hasText(roomNo)) {
            LambdaQueryWrapper<Room> rq = new LambdaQueryWrapper<Room>()
                    .isNull(Room::getDeletedAt);
            if (communityId != null) {
                rq.eq(Room::getCommunityId, communityId);
            }
            if (buildingId != null) {
                rq.eq(Room::getBuildingId, buildingId);
            }
            if (unitId != null) {
                rq.eq(Room::getUnitId, unitId);
            }
            if (StringUtils.hasText(roomNo)) {
                rq.like(Room::getRoomNo, roomNo.trim());
            }
            roomFilter = roomMapper.selectList(rq).stream().map(Room::getId).collect(Collectors.toSet());
            if (roomFilter.isEmpty()) {
                return pageEmpty(page, pageSize);
            }
        }

        Set<Long> userFilter = null;
        if (StringUtils.hasText(mobile)) {
            userFilter = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .like(SysUser::getMobile, mobile.trim()))
                    .stream().map(SysUser::getId).collect(Collectors.toSet());
            if (userFilter.isEmpty()) {
                return pageEmpty(page, pageSize);
            }
        }
        if (StringUtils.hasText(realName)) {
            Set<Long> byName = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .like(SysUser::getRealName, realName.trim()))
                    .stream().map(SysUser::getId).collect(Collectors.toSet());
            if (byName.isEmpty()) {
                return pageEmpty(page, pageSize);
            }
            if (userFilter == null) {
                userFilter = byName;
            } else {
                userFilter.retainAll(byName);
                if (userFilter.isEmpty()) {
                    return pageEmpty(page, pageSize);
                }
            }
        }
        if (StringUtils.hasText(idCardNo)) {
            Set<Long> byIdCard = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                            .like(SysUser::getIdCardNo, idCardNo.trim()))
                    .stream().map(SysUser::getId).collect(Collectors.toSet());
            if (byIdCard.isEmpty()) {
                return pageEmpty(page, pageSize);
            }
            if (userFilter == null) {
                userFilter = byIdCard;
            } else {
                userFilter.retainAll(byIdCard);
                if (userFilter.isEmpty()) {
                    return pageEmpty(page, pageSize);
                }
            }
        }

        LambdaQueryWrapper<RoomOccupant> q = new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getStatus, "ACTIVE")
                .orderByDesc(RoomOccupant::getId);
        if (communityId != null) {
            q.eq(RoomOccupant::getCommunityId, communityId);
        }
        if (roomFilter != null) {
            q.in(RoomOccupant::getRoomId, roomFilter);
        }
        if (userFilter != null) {
            q.in(RoomOccupant::getUserId, userFilter);
        }
        if (StringUtils.hasText(role)) {
            q.eq(RoomOccupant::getResidentRole, role.trim());
        }

        Page<RoomOccupant> p = roomOccupantMapper.selectPage(new Page<>(page, pageSize), q);
        Map<Long, String> committeeTitles = communityId == null
                ? Map.of()
                : committeeService.activeTitleByUser(communityId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RoomOccupant o : p.getRecords()) {
            Map<String, Object> row = toRow(o, maskIdCard);
            row.put("committeeTitle", committeeTitles.get(o.getUserId()));
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", rows);
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    private Map<String, Object> toRow(RoomOccupant o, boolean maskIdCard) {
        SysUser user = sysUserMapper.selectById(o.getUserId());
        Room room = roomMapper.selectById(o.getRoomId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", o.getId());
        row.put("communityId", o.getCommunityId());
        Community community = o.getCommunityId() == null ? null : communityMapper.selectById(o.getCommunityId());
        row.put("communityName", community == null ? "" : Optional.ofNullable(community.getName()).orElse(""));
        row.put("roomId", o.getRoomId());
        row.put("roomNo", room == null ? "" : room.getRoomNo());
        Building b = room == null || room.getBuildingId() == null ? null : buildingMapper.selectById(room.getBuildingId());
        Unit u = room == null || room.getUnitId() == null ? null : unitMapper.selectById(room.getUnitId());
        Floor f = room == null || room.getFloorId() == null ? null : floorMapper.selectById(room.getFloorId());
        String buildingName = b == null ? "" : Optional.ofNullable(b.getName()).orElse("");
        String unitName = u == null ? "" : Optional.ofNullable(u.getName()).orElse("");
        String floorName = Optional.ofNullable(RoomPaths.floorLabel(f)).orElse("");
        row.put("buildingId", room == null ? null : room.getBuildingId());
        row.put("buildingName", buildingName);
        row.put("unitId", room == null ? null : room.getUnitId());
        row.put("unitName", unitName);
        row.put("floorId", room == null ? null : room.getFloorId());
        row.put("floorName", floorName);
        row.put("address", room == null ? "" : RoomPaths.format(buildingName, unitName, floorName, room.getRoomNo()));
        row.put("userId", o.getUserId());
        row.put("realName", user == null ? "" : Optional.ofNullable(user.getRealName()).orElse(""));
        row.put("mobile", user == null ? "" : Optional.ofNullable(user.getMobile()).orElse(""));
        String idCard = user == null ? null : user.getIdCardNo();
        row.put("idCardNo", maskIdCard ? maskIdCard(idCard) : Optional.ofNullable(idCard).orElse(""));
        row.put("residentRole", o.getResidentRole());
        row.put("source", o.getSource());
        row.put("approvedAt", o.getApprovedAt());
        return row;
    }

    /** 统一房屋路径：楼栋/单元/层/房号 */
    public String buildAddress(Room room) {
        if (room == null) {
            return "";
        }
        Building b = room.getBuildingId() == null ? null : buildingMapper.selectById(room.getBuildingId());
        Unit u = room.getUnitId() == null ? null : unitMapper.selectById(room.getUnitId());
        Floor f = room.getFloorId() == null ? null : floorMapper.selectById(room.getFloorId());
        return RoomPaths.format(b, u, f, room);
    }

    public static String maskIdCard(String idCard) {
        if (!StringUtils.hasText(idCard) || idCard.length() < 8) {
            return idCard == null ? "" : idCard;
        }
        return idCard.substring(0, 4) + "*".repeat(idCard.length() - 8) + idCard.substring(idCard.length() - 4);
    }

    private static Map<String, Object> pageEmpty(int page, int pageSize) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", List.of());
        data.put("total", 0);
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    /**
     * 平台批量解除住户绑定。body.occupantIds 为 room_occupant.id 列表。
     */
    @Transactional
    public Map<String, Object> platformBatchDeactivate(Map<String, Object> body) {
        var u = AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台管理员权限");
        }
        Object raw = body == null ? null : body.get("occupantIds");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "occupantIds 不能为空");
        }
        List<Long> ids = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Number n) {
                ids.add(n.longValue());
            } else if (o != null && StringUtils.hasText(o.toString())) {
                try {
                    ids.add(Long.parseLong(o.toString().trim()));
                } catch (NumberFormatException e) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "occupantIds 含非法值");
                }
            }
        }
        if (ids.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "occupantIds 不能为空");
        }
        int success = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Long id : ids) {
            RoomOccupant o = roomOccupantMapper.selectById(id);
            if (o == null || !"ACTIVE".equals(o.getStatus())) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("occupantId", id);
                err.put("message", "绑定不存在或已失效");
                errors.add(err);
                continue;
            }
            try {
                roomOccupantBindService.deactivateActive(o.getRoomId(), o.getCommunityId(), o.getUserId(), o.getId());
                success++;
            } catch (BizException e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("occupantId", id);
                err.put("code", e.getCode());
                err.put("message", e.getMessage());
                errors.add(err);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("successCount", success);
        data.put("failCount", errors.size());
        data.put("errors", errors);
        return data;
    }

    /** 校验当前用户是否为本房 ACTIVE 住户。 */
    public RoomOccupant requireActiveOccupant(Long roomId) {
        Long uid = AuthContext.require().getUserId();
        RoomOccupant o = roomOccupantBindService.findActive(roomId, uid);
        if (o == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "非本房住户");
        }
        return o;
    }

    /**
     * 为本小区相关用户设置 App 临时密码（住户 / 业委会 / 物业任职均可）。
     * 密码与 Web 共用 password_hash；对方下次用 App 登录须改密。
     */
    @Transactional
    public Map<String, Object> resetAppPassword(Long userId, String newPassword) {
        StaffGuard.requireStaff();
        Long cid = StaffGuard.communityId();
        if (userId == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "userId 必填");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || !Objects.equals(user.getStatus(), 1)) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在或已停用");
        }
        if (Objects.equals(user.getIsPlatformAdmin(), 1)) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "不可重置平台管理员密码");
        }
        boolean inCommunity =
                roomOccupantMapper.selectCount(new LambdaQueryWrapper<RoomOccupant>()
                        .eq(RoomOccupant::getCommunityId, cid)
                        .eq(RoomOccupant::getUserId, userId)
                        .eq(RoomOccupant::getStatus, "ACTIVE")) > 0
                || committeeMemberMapper.selectCount(new LambdaQueryWrapper<CommitteeMember>()
                        .eq(CommitteeMember::getCommunityId, cid)
                        .eq(CommitteeMember::getUserId, userId)
                        .eq(CommitteeMember::getStatus, "ACTIVE")) > 0
                || staffCommunityMapper.selectCount(new LambdaQueryWrapper<StaffCommunity>()
                        .eq(StaffCommunity::getCommunityId, cid)
                        .eq(StaffCommunity::getUserId, userId)
                        .eq(StaffCommunity::getStatus, "ACTIVE")) > 0;
        if (!inCommunity) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "该用户不属于本小区");
        }
        boolean usedDefault = !StringUtils.hasText(newPassword);
        String pwd = usedDefault ? DefaultWebPassword.VALUE : newPassword.trim();
        if (pwd.length() < 6) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "密码至少 6 位");
        }
        user.setPasswordHash(passwordEncoder.encode(pwd));
        user.setMustChangePassword(1);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("mobile", user.getMobile());
        out.put("mustChangePassword", true);
        out.put("usedDefaultPassword", usedDefault);
        if (usedDefault) {
            out.put("tempPassword", DefaultWebPassword.VALUE);
        }
        return out;
    }
}
