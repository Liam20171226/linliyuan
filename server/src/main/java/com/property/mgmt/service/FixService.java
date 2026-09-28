package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class FixService {

    private static final Set<String> ROLES = Set.of(
            "OWNER", "OWNER_MEMBER", "TENANT", "TENANT_MEMBER");

    private final AuditLogMapper auditLogMapper;
    private final RoomMapper roomMapper;
    private final RoomOccupantBindService roomOccupantBindService;
    private final SysUserMapper sysUserMapper;
    private final RoomVehicleMapper roomVehicleMapper;
    private final ParkingSpaceMapper parkingSpaceMapper;
    private final ParkingService parkingService;
    private final WechatBindService wechatBindService;
    private final ObjectMapper objectMapper;

    /** 按手机号查已有账号（物业端绑定时匹配卡片用）。不校验小区归属。 */
    public Map<String, Object> lookupByMobile(String mobile) {
        StaffGuard.requireStaff();
        String m = mobile == null ? "" : mobile.trim();
        if (!m.matches("^1\\d{10}$")) {
            throw new BizException(ErrorCodes.BAD_PARAM, "手机号须为 1 开头的 11 位数字");
        }
        SysUser u = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getMobile, m)
                .last("limit 1"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (u == null) {
            out.put("exists", false);
            return out;
        }
        out.put("exists", true);
        out.put("userId", u.getId());
        out.put("mobile", u.getMobile());
        out.put("realName", u.getRealName() == null ? "" : u.getRealName());
        out.put("hasIdCard", StringUtils.hasText(u.getIdCardNo()));
        out.put("idCardMasked", maskIdCard(u.getIdCardNo()));
        return out;
    }

    private static String maskIdCard(String id) {
        if (!StringUtils.hasText(id) || id.length() < 8) return "";
        return id.substring(0, 4) + "**********" + id.substring(id.length() - 4);
    }

    @Transactional
    public Map<String, Object> fixOccupant(boolean platform, Map<String, Object> body) {
        AuthUser actor = requireActor(platform);
        String action = str(body, "action");
        if (!StringUtils.hasText(action)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "action 必填");
        }
        Long roomId = longVal(body, "roomId");
        Room room = requireRoom(roomId, platform ? longValOpt(body, "communityId") : StaffGuard.communityId(), platform);
        Long cid = room.getCommunityId();
        String source = platform ? "ADMIN_FIX" : "STAFF_FIX";
        String auditAction = platform ? "ADMIN_FIX_OCCUPANT" : "STAFF_FIX_OCCUPANT";
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> result = new LinkedHashMap<>();

        switch (action.toUpperCase(Locale.ROOT)) {
            case "BIND", "UPSERT" -> {
                String role = str(body, "role");
                if (!ROLES.contains(role)) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "role 非法");
                }
                SysUser user = resolveUser(body, role);
                if (StringUtils.hasText(str(body, "idCardNo"))) {
                    assertIdCardFree(str(body, "idCardNo"), user.getId());
                    user.setIdCardNo(str(body, "idCardNo").trim());
                    user.setUpdatedAt(now);
                    sysUserMapper.updateById(user);
                }
                RoomOccupant bound = roomOccupantBindService.bindActive(room, user.getId(), role, source, actor.getUserId());
                result.put("occupantId", bound.getId());
                result.put("userId", user.getId());
            }
            case "DEACTIVATE", "UNBIND" -> {
                Long occupantId = longValOpt(body, "occupantId");
                Long userId = occupantId == null ? resolveUserId(body) : null;
                RoomOccupant o = roomOccupantBindService.deactivateActive(roomId, cid, userId, occupantId);
                result.put("occupantId", o.getId());
            }
            default -> throw BizException.of(ErrorCodes.BAD_PARAM, "action 须为 BIND/DEACTIVATE");
        }

        writeAudit(cid, actor.getUserId(), auditAction, "room_occupant",
                result.get("occupantId") == null ? null : ((Number) result.get("occupantId")).longValue(), body);
        return result;
    }

    @Transactional
    public Map<String, Object> fixVehicle(boolean platform, Map<String, Object> body) {
        AuthUser actor = requireActor(platform);
        String action = str(body, "action");
        Long roomId = longVal(body, "roomId");
        Room room = requireRoom(roomId, platform ? longValOpt(body, "communityId") : StaffGuard.communityId(), platform);
        Long cid = room.getCommunityId();
        String auditAction = platform ? "ADMIN_FIX_VEHICLE" : "STAFF_FIX_VEHICLE";
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> result = new LinkedHashMap<>();

        switch (action.toUpperCase(Locale.ROOT)) {
            case "ADD" -> {
                String plate = str(body, "plateNo");
                if (!StringUtils.hasText(plate)) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "plateNo 必填");
                }
                plate = plate.trim().toUpperCase(Locale.ROOT).replace(" ", "");
                assertPlateUnique(cid, plate, null);
                RoomVehicle v = new RoomVehicle();
                v.setCommunityId(cid);
                v.setRoomId(roomId);
                v.setPlateNo(plate);
                Long spaceId = longValOpt(body, "parkingSpaceId");
                if (spaceId != null) {
                    assertRoomSpace(roomId, spaceId, true, null);
                    v.setParkingSpaceId(spaceId);
                }
                v.setCreatedAt(now);
                v.setUpdatedAt(now);
                roomVehicleMapper.insert(v);
                if (spaceId == null) {
                    parkingService.autoBindUnboundVehicles(roomId);
                }
                result.put("vehicleId", v.getId());
            }
            case "UPDATE" -> {
                Long vehicleId = longVal(body, "vehicleId");
                RoomVehicle v = requireRoomVehicle(roomId, vehicleId);
                String plate = str(body, "plateNo");
                if (StringUtils.hasText(plate)) {
                    plate = plate.trim().toUpperCase(Locale.ROOT).replace(" ", "");
                    assertPlateUnique(cid, plate, vehicleId);
                    v.setPlateNo(plate);
                }
                if (body.containsKey("parkingSpaceId")) {
                    Long spaceId = longValOpt(body, "parkingSpaceId");
                    if (spaceId != null) {
                        assertRoomSpace(roomId, spaceId, true, vehicleId);
                    }
                    v.setParkingSpaceId(spaceId);
                }
                v.setUpdatedAt(now);
                roomVehicleMapper.updateById(v);
                result.put("vehicleId", v.getId());
                parkingService.autoBindUnboundVehicles(roomId);
            }
            case "DELETE" -> {
                Long vehicleId = longVal(body, "vehicleId");
                RoomVehicle v = requireRoomVehicle(roomId, vehicleId);
                roomVehicleMapper.deleteById(v.getId());
                parkingService.autoBindUnboundVehicles(roomId);
                result.put("vehicleId", vehicleId);
            }
            default -> throw BizException.of(ErrorCodes.BAD_PARAM, "action 须为 ADD/UPDATE/DELETE");
        }

        writeAudit(cid, actor.getUserId(), auditAction, "room_vehicle",
                result.get("vehicleId") == null ? null : ((Number) result.get("vehicleId")).longValue(), body);
        return result;
    }

    @Transactional
    public Map<String, Object> fixParkingLink(boolean platform, Map<String, Object> body) {
        AuthUser actor = requireActor(platform);
        String action = str(body, "action");
        Long spaceId = longVal(body, "parkingSpaceId");
        ParkingSpace space = parkingSpaceMapper.selectById(spaceId);
        if (space == null || space.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "车位不存在");
        }
        Long expectedCid = platform ? longValOpt(body, "communityId") : StaffGuard.communityId();
        if (expectedCid != null && !expectedCid.equals(space.getCommunityId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "非本小区车位");
        }
        if (!platform && !StaffGuard.communityId().equals(space.getCommunityId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "非本小区车位");
        }
        Long cid = space.getCommunityId();
        String auditAction = platform ? "ADMIN_FIX_PARKING_LINK" : "STAFF_FIX_PARKING_LINK";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parkingSpaceId", spaceId);

        if (platform) {
            switch (action.toUpperCase(Locale.ROOT)) {
                case "LINK" -> linkSpace(space, longVal(body, "roomId"));
                case "UNLINK" -> unlinkSpace(space);
                case "RELINK" -> {
                    unlinkSpace(space);
                    space = parkingSpaceMapper.selectById(spaceId);
                    linkSpace(space, longVal(body, "roomId"));
                }
                default -> throw BizException.of(ErrorCodes.BAD_PARAM, "action 须为 LINK/UNLINK/RELINK");
            }
        } else {
            switch (action.toUpperCase(Locale.ROOT)) {
                case "LINK" -> parkingService.link(spaceId, longVal(body, "roomId"));
                case "UNLINK" -> parkingService.unlink(spaceId);
                case "RELINK" -> parkingService.relink(spaceId, longVal(body, "roomId"));
                default -> throw BizException.of(ErrorCodes.BAD_PARAM, "action 须为 LINK/UNLINK/RELINK");
            }
        }

        writeAudit(cid, actor.getUserId(), auditAction, "parking_space", spaceId, body);
        return result;
    }

    private void linkSpace(ParkingSpace space, Long roomId) {
        if (space.getRoomId() != null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "车位已挂靠房屋，请用 RELINK");
        }
        Room room = roomMapper.selectById(roomId);
        if (room == null || room.getDeletedAt() != null || !space.getCommunityId().equals(room.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在或不属于同小区");
        }
        space.setRoomId(roomId);
        space.setUpdatedAt(LocalDateTime.now());
        parkingSpaceMapper.updateById(space);
        parkingService.autoBindUnboundVehicles(roomId);
    }

    private void unlinkSpace(ParkingSpace space) {
        Long roomId = space.getRoomId();
        if (roomId == null) {
            return;
        }
        RoomVehicle vehicle = roomVehicleMapper.selectOne(new LambdaQueryWrapper<RoomVehicle>()
                .eq(RoomVehicle::getParkingSpaceId, space.getId()));
        if (vehicle != null) {
            vehicle.setParkingSpaceId(null);
            vehicle.setUpdatedAt(LocalDateTime.now());
            roomVehicleMapper.updateById(vehicle);
        }
        space.setRoomId(null);
        space.setUpdatedAt(LocalDateTime.now());
        parkingSpaceMapper.updateById(space);
        parkingService.autoBindUnboundVehicles(roomId);
    }

    private AuthUser requireActor(boolean platform) {
        AuthUser u = AuthContext.require();
        if (platform) {
            if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台身份");
            }
            return u;
        }
        return StaffGuard.requireStaff();
    }

    private Room requireRoom(Long roomId, Long communityId, boolean platform) {
        Room room = roomMapper.selectById(roomId);
        if (room == null || room.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在");
        }
        if (communityId != null && !communityId.equals(room.getCommunityId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "房屋不属于指定小区");
        }
        if (!platform && !StaffGuard.communityId().equals(room.getCommunityId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "非本小区房屋");
        }
        return room;
    }

    /**
     * 解析住户用户：传 userId 表示编辑已有账号（可改手机号，全局唯一）；仅传 mobile 则按号查找或建档。
     */
    private SysUser resolveUser(Map<String, Object> body, String role) {
        Long userId = longValOpt(body, "userId");
        String mobile = str(body, "mobile");
        String realName = str(body, "realName");
        LocalDateTime now = LocalDateTime.now();
        if (userId != null) {
            SysUser u = sysUserMapper.selectById(userId);
            if (u == null) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在");
            }
            boolean changed = false;
            if (StringUtils.hasText(mobile)) {
                String next = mobile.trim();
                if (!next.matches("^1\\d{10}$")) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "手机号须为 1 开头的 11 位数字");
                }
                if (!next.equals(Optional.ofNullable(u.getMobile()).orElse(""))) {
                    assertMobileFree(next, u.getId());
                    u.setMobile(next);
                    changed = true;
                    wechatBindService.unbindAllForUser(u.getId());
                }
            }
            if (StringUtils.hasText(realName) && !realName.equals(Optional.ofNullable(u.getRealName()).orElse(""))) {
                u.setRealName(realName);
                changed = true;
            }
            if (changed) {
                u.setUpdatedAt(now);
                sysUserMapper.updateById(u);
            }
            return u;
        }
        if (!StringUtils.hasText(mobile)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "userId 或 mobile 必填");
        }
        String nextMobile = mobile.trim();
        if (!nextMobile.matches("^1\\d{10}$")) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "手机号须为 1 开头的 11 位数字");
        }
        SysUser u = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, nextMobile));
        if (u == null) {
            u = new SysUser();
            u.setMobile(nextMobile);
            u.setRealName(realName);
            u.setIsPlatformAdmin(0);
            u.setStatus(1);
            u.setCreatedAt(now);
            u.setUpdatedAt(now);
            sysUserMapper.insert(u);
        } else if (StringUtils.hasText(realName) && !StringUtils.hasText(u.getRealName())) {
            u.setRealName(realName);
            u.setUpdatedAt(now);
            sysUserMapper.updateById(u);
        }
        return u;
    }

    private void assertMobileFree(String mobile, Long excludeUserId) {
        long cnt = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getMobile, mobile.trim())
                .ne(excludeUserId != null, SysUser::getId, excludeUserId));
        if (cnt > 0) {
            throw BizException.of(ErrorCodes.MOBILE_TAKEN, "手机号已被占用");
        }
    }

    private Long resolveUserId(Map<String, Object> body) {
        Long userId = longValOpt(body, "userId");
        if (userId != null) {
            return userId;
        }
        String mobile = str(body, "mobile");
        if (!StringUtils.hasText(mobile)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "userId 或 mobile 必填");
        }
        SysUser u = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, mobile.trim()));
        if (u == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在");
        }
        return u.getId();
    }

    private void assertIdCardFree(String idCard, Long excludeUserId) {
        long cnt = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getIdCardNo, idCard.trim())
                .ne(excludeUserId != null, SysUser::getId, excludeUserId));
        if (cnt > 0) {
            throw BizException.of(ErrorCodes.ID_CARD_TAKEN, "身份证已被其他账号占用");
        }
    }

    private void assertPlateUnique(Long cid, String plate, Long excludeId) {
        long cnt = roomVehicleMapper.selectCount(new LambdaQueryWrapper<RoomVehicle>()
                .eq(RoomVehicle::getCommunityId, cid)
                .eq(RoomVehicle::getPlateNo, plate.trim())
                .ne(excludeId != null, RoomVehicle::getId, excludeId));
        if (cnt > 0) {
            throw BizException.of(ErrorCodes.PLATE_DUP, "车牌已存在");
        }
    }

    private void assertRoomSpace(Long roomId, Long spaceId, boolean mustFree, Long excludeVehicleId) {
        ParkingSpace s = parkingSpaceMapper.selectById(spaceId);
        if (s == null || s.getDeletedAt() != null || !roomId.equals(s.getRoomId())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "车位须为本房已挂车位");
        }
        if (mustFree) {
            long used = roomVehicleMapper.selectCount(new LambdaQueryWrapper<RoomVehicle>()
                    .eq(RoomVehicle::getParkingSpaceId, spaceId)
                    .ne(excludeVehicleId != null, RoomVehicle::getId, excludeVehicleId));
            if (used > 0) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "车位已被占用");
            }
        }
    }

    private RoomVehicle requireRoomVehicle(Long roomId, Long vehicleId) {
        RoomVehicle v = roomVehicleMapper.selectById(vehicleId);
        if (v == null || !roomId.equals(v.getRoomId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "车辆不存在");
        }
        return v;
    }

    private void writeAudit(Long communityId, Long actorId, String action, String targetType,
                            Long targetId, Map<String, Object> detail) {
        AuditLog log = new AuditLog();
        log.setCommunityId(communityId);
        log.setActorId(actorId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        try {
            log.setDetailJson(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            log.setDetailJson("{}");
        }
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString().trim();
    }

    private static Long longVal(Map<String, Object> p, String key) {
        Long v = longValOpt(p, key);
        if (v == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, key + " 必填");
        }
        return v;
    }

    private static Long longValOpt(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null || "".equals(v.toString().trim())) {
            return null;
        }
        return Long.valueOf(v.toString());
    }
}
