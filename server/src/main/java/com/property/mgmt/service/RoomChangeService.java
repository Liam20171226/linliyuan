package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoomChangeService {

    /** 业主可申请、物业可审核的变更类型（已去掉车辆改绑 / 改面积 / 改房屋类型） */
    private static final Set<String> TYPES = Set.of(
            "ADD_MEMBER", "REMOVE_MEMBER", "ADD_VEHICLE", "REMOVE_VEHICLE",
            "LINK_PARKING", "UNLINK_PARKING");
    private static final Set<String> MEMBER_ROLES = Set.of(
            "OWNER_MEMBER", "TENANT", "TENANT_MEMBER");
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "ADD_MEMBER", "增加成员",
            "REMOVE_MEMBER", "移除成员",
            "ADD_VEHICLE", "增加车辆",
            "REMOVE_VEHICLE", "移除车辆",
            "LINK_PARKING", "关联车位",
            "UNLINK_PARKING", "取消关联车位");

    private final RoomChangeApplicationMapper changeMapper;
    private final RoomOccupantMapper roomOccupantMapper;
    private final RoomOccupantBindService roomOccupantBindService;
    private final RoomMapper roomMapper;
    private final SysUserMapper sysUserMapper;
    private final RoomVehicleMapper roomVehicleMapper;
    private final ParkingSpaceMapper parkingSpaceMapper;
    private final StaffCommunityMapper staffCommunityMapper;
    private final ParkingService parkingService;
    private final AttachmentService attachmentService;
    private final TodoNotifyService todoNotifyService;
    private final OccupantQueryService occupantQueryService;
    private final ObjectMapper objectMapper;

    @Transactional
    public RoomChangeApplication submit(String changeType, Map<String, Object> payload,
                                        String applyMessage, List<Long> attachmentIds) {
        AuthUser auth = AuthContext.require();
        if (!"RESIDENT".equals(auth.getIdentityType()) || auth.getRoomId() == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换为住户并选择房屋");
        }
        RoomOccupant owner = roomOccupantBindService.findActive(auth.getRoomId(), auth.getUserId());
        if (owner == null || !"OWNER".equals(owner.getResidentRole())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "仅当前房业主可提交变更");
        }
        if (!TYPES.contains(changeType)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "不支持的变更类型");
        }
        if (payload == null) {
            payload = Map.of();
        }
        validatePayload(changeType, payload, owner.getRoomId(), owner.getCommunityId());

        LocalDateTime now = LocalDateTime.now();
        RoomChangeApplication app = new RoomChangeApplication();
        app.setCommunityId(owner.getCommunityId());
        app.setRoomId(owner.getRoomId());
        app.setApplicantUserId(auth.getUserId());
        app.setChangeType(changeType);
        app.setPayloadJson(toJson(payload));
        app.setApplyMessage(applyMessage);
        app.setStatus("PENDING");
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        changeMapper.insert(app);

        attachmentService.bindToBiz(attachmentIds, "ROOM_CHANGE", app.getId(), owner.getCommunityId(), auth.getUserId());

        Room room = roomMapper.selectById(owner.getRoomId());
        String roomNo = room == null ? "" : room.getRoomNo();
        String typeLabel = typeLabel(changeType);
        List<StaffCommunity> staffs = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, owner.getCommunityId())
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        for (StaffCommunity sc : staffs) {
            todoNotifyService.createTodo(owner.getCommunityId(), sc.getUserId(), "PENDING_REVIEW",
                    "待审核房屋变更",
                    "房间 " + roomNo + " · " + typeLabel,
                    "ROOM_CHANGE", app.getId());
            todoNotifyService.skipSubscribe(owner.getCommunityId(), sc.getUserId(), "PENDING_REVIEW_STAFF",
                    "ROOM_CHANGE", app.getId(), "一阶段未接微信订阅下发");
        }
        return app;
    }

    public List<RoomChangeApplication> mine() {
        Long uid = AuthContext.require().getUserId();
        return changeMapper.selectList(new LambdaQueryWrapper<RoomChangeApplication>()
                .eq(RoomChangeApplication::getApplicantUserId, uid)
                .orderByDesc(RoomChangeApplication::getId));
    }

    public List<Map<String, Object>> mineViews() {
        return mine().stream().map(this::toView).collect(Collectors.toList());
    }

    public List<RoomChangeApplication> staffList(String status) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<RoomChangeApplication> q = new LambdaQueryWrapper<RoomChangeApplication>()
                .eq(RoomChangeApplication::getCommunityId, cid)
                .orderByDesc(RoomChangeApplication::getId);
        if (StringUtils.hasText(status)) {
            q.eq(RoomChangeApplication::getStatus, status);
        }
        return changeMapper.selectList(q);
    }

    public List<Map<String, Object>> staffListViews(String status) {
        return staffList(status).stream().map(this::toView).collect(Collectors.toList());
    }

    public RoomChangeApplication staffGet(Long id) {
        RoomChangeApplication app = require(id);
        if (!StaffGuard.communityId().equals(app.getCommunityId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "非本小区申请");
        }
        return app;
    }

    public Map<String, Object> staffGetView(Long id) {
        return toView(staffGet(id));
    }

    @Transactional
    public RoomChangeApplication approve(Long id) {
        AuthUser staff = StaffGuard.requireStaff();
        RoomChangeApplication app = staffGet(id);
        if (!"PENDING".equals(app.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "申请已处理");
        }
        Map<String, Object> payload = fromJson(app.getPayloadJson());
        applyChange(app, payload);

        LocalDateTime now = LocalDateTime.now();
        app.setStatus("APPROVED");
        app.setReviewedBy(staff.getUserId());
        app.setReviewedAt(now);
        app.setUpdatedAt(now);
        changeMapper.updateById(app);

        todoNotifyService.doneByBiz("ROOM_CHANGE", app.getId(), "PENDING_REVIEW");
        todoNotifyService.createTodo(app.getCommunityId(), app.getApplicantUserId(), "ROOM_CHANGE_RESULT",
                "变更已通过",
                typeLabel(app.getChangeType()) + " 已生效",
                "ROOM_CHANGE", app.getId());
        todoNotifyService.skipSubscribe(app.getCommunityId(), app.getApplicantUserId(), "ROOM_CHANGE_RESULT",
                "ROOM_CHANGE", app.getId(), "一阶段未接微信订阅下发");
        return app;
    }

    @Transactional
    public RoomChangeApplication reject(Long id, String rejectReason) {
        AuthUser staff = StaffGuard.requireStaff();
        RoomChangeApplication app = staffGet(id);
        if (!"PENDING".equals(app.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "申请已处理");
        }
        if (!StringUtils.hasText(rejectReason)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "拒绝原因必填");
        }
        LocalDateTime now = LocalDateTime.now();
        app.setStatus("REJECTED");
        app.setRejectReason(rejectReason.trim());
        app.setReviewedBy(staff.getUserId());
        app.setReviewedAt(now);
        app.setUpdatedAt(now);
        changeMapper.updateById(app);

        todoNotifyService.doneByBiz("ROOM_CHANGE", app.getId(), "PENDING_REVIEW");
        todoNotifyService.createTodo(app.getCommunityId(), app.getApplicantUserId(), "ROOM_CHANGE_RESULT",
                "变更未通过",
                rejectReason.trim(),
                "ROOM_CHANGE", app.getId());
        todoNotifyService.skipSubscribe(app.getCommunityId(), app.getApplicantUserId(), "ROOM_CHANGE_RESULT",
                "ROOM_CHANGE", app.getId(), "一阶段未接微信订阅下发");
        return app;
    }

    private Map<String, Object> toView(RoomChangeApplication app) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", app.getId());
        m.put("communityId", app.getCommunityId());
        m.put("roomId", app.getRoomId());
        m.put("applicantUserId", app.getApplicantUserId());
        m.put("changeType", app.getChangeType());
        m.put("changeTypeLabel", typeLabel(app.getChangeType()));
        Map<String, Object> payload = fromJsonSafe(app.getPayloadJson());
        m.put("payload", payload);
        m.put("payloadSummary", payloadSummary(app.getChangeType(), payload));
        m.put("applyMessage", app.getApplyMessage());
        m.put("status", app.getStatus());
        m.put("rejectReason", app.getRejectReason());
        m.put("reviewedBy", app.getReviewedBy());
        m.put("reviewedAt", app.getReviewedAt());
        m.put("createdAt", app.getCreatedAt());
        m.put("updatedAt", app.getUpdatedAt());
        Room room = roomMapper.selectById(app.getRoomId());
        m.put("roomNo", room == null ? "" : room.getRoomNo());
        m.put("roomPath", room == null ? "" : occupantQueryService.buildAddress(room));
        return m;
    }

    private static String typeLabel(String type) {
        return TYPE_LABELS.getOrDefault(type, type == null ? "" : type);
    }

    private String payloadSummary(String type, Map<String, Object> p) {
        if (p == null) p = Map.of();
        return switch (type == null ? "" : type) {
            case "ADD_MEMBER" -> "增加成员：" + str(p, "name") + " / " + str(p, "mobile")
                    + " / " + memberRoleLabel(str(p, "resident_role"));
            case "REMOVE_MEMBER" -> {
                Long oid = longValOpt(p, "occupant_id");
                RoomOccupant o = oid == null ? null : roomOccupantMapper.selectById(oid);
                SysUser u = o == null ? null : sysUserMapper.selectById(o.getUserId());
                String name = u == null ? "" : Optional.ofNullable(u.getRealName()).orElse("");
                String mobile = u == null ? "" : Optional.ofNullable(u.getMobile()).orElse("");
                yield "移除成员：" + (StringUtils.hasText(name) ? name : "住户")
                        + (StringUtils.hasText(mobile) ? " / " + mobile : "")
                        + (oid != null ? "（绑定#" + oid + "）" : "");
            }
            case "ADD_VEHICLE" -> {
                String plate = str(p, "plate_no");
                Long spaceId = longValOpt(p, "parking_space_id");
                String space = spaceId == null ? "不绑车位" : "车位#" + spaceId;
                yield "增加车辆：" + plate + "（" + space + "）";
            }
            case "REMOVE_VEHICLE" -> {
                Long vid = longValOpt(p, "vehicle_id");
                RoomVehicle v = vid == null ? null : roomVehicleMapper.selectById(vid);
                yield "移除车辆：" + (v == null ? ("#" + vid) : v.getPlateNo());
            }
            case "LINK_PARKING" -> {
                Long sid = longValOpt(p, "parking_space_id");
                ParkingSpace s = sid == null ? null : parkingSpaceMapper.selectById(sid);
                yield "关联车位：" + (s == null ? ("#" + sid) : s.getSpaceNo());
            }
            case "UNLINK_PARKING" -> {
                Long sid = longValOpt(p, "parking_space_id");
                ParkingSpace s = sid == null ? null : parkingSpaceMapper.selectById(sid);
                yield "取消关联车位：" + (s == null ? ("#" + sid) : s.getSpaceNo());
            }
            default -> "";
        };
    }

    private static String memberRoleLabel(String role) {
        return switch (role == null ? "" : role) {
            case "OWNER_MEMBER" -> "业主成员";
            case "TENANT" -> "租户";
            case "TENANT_MEMBER" -> "租户成员";
            default -> role == null ? "" : role;
        };
    }

    private void applyChange(RoomChangeApplication app, Map<String, Object> p) {
        Long roomId = app.getRoomId();
        Long cid = app.getCommunityId();
        LocalDateTime now = LocalDateTime.now();
        switch (app.getChangeType()) {
            case "ADD_MEMBER" -> {
                String mobile = str(p, "mobile");
                String name = str(p, "name");
                String role = str(p, "resident_role");
                if (!MEMBER_ROLES.contains(role)) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "成员角色不可为 OWNER");
                }
                SysUser user = findOrCreateUser(mobile, name);
                List<RoomOccupant> actives = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                        .eq(RoomOccupant::getRoomId, roomId)
                        .eq(RoomOccupant::getUserId, user.getId())
                        .eq(RoomOccupant::getStatus, "ACTIVE"));
                if (actives.stream().anyMatch(o -> "OWNER".equals(o.getResidentRole()))) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "该用户已是本房业主，不可再加为成员");
                }
                Room room = roomMapper.selectById(roomId);
                if (room == null || room.getDeletedAt() != null) {
                    throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在");
                }
                roomOccupantBindService.bindActive(
                        room, user.getId(), role, "ROOM_CHANGE", StaffGuard.requireStaff().getUserId());
            }
            case "REMOVE_MEMBER" -> {
                Long occupantId = longVal(p, "occupant_id");
                RoomOccupant o = roomOccupantMapper.selectById(occupantId);
                if (o == null || !roomId.equals(o.getRoomId()) || !"ACTIVE".equals(o.getStatus())) {
                    throw BizException.of(ErrorCodes.NOT_FOUND, "成员不存在");
                }
                if ("OWNER".equals(o.getResidentRole())) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "不可通过变更移除业主");
                }
                o.setStatus("INACTIVE");
                o.setUpdatedAt(now);
                roomOccupantMapper.updateById(o);
            }
            case "ADD_VEHICLE" -> {
                String plate = str(p, "plate_no");
                assertPlateUnique(cid, plate, null);
                RoomVehicle v = new RoomVehicle();
                v.setCommunityId(cid);
                v.setRoomId(roomId);
                v.setPlateNo(plate);
                Long spaceId = longValOpt(p, "parking_space_id");
                if (spaceId != null) {
                    assertRoomFreeSpace(roomId, spaceId);
                    v.setParkingSpaceId(spaceId);
                }
                v.setCreatedAt(now);
                v.setUpdatedAt(now);
                roomVehicleMapper.insert(v);
                if (spaceId == null) {
                    parkingService.autoBindUnboundVehicles(roomId);
                }
            }
            case "REMOVE_VEHICLE" -> {
                Long vehicleId = longVal(p, "vehicle_id");
                RoomVehicle v = requireRoomVehicle(roomId, vehicleId);
                roomVehicleMapper.deleteById(v.getId());
                parkingService.autoBindUnboundVehicles(roomId);
            }
            case "LINK_PARKING" -> parkingService.link(longVal(p, "parking_space_id"), roomId);
            case "UNLINK_PARKING" -> parkingService.unlink(longVal(p, "parking_space_id"));
            default -> throw BizException.of(ErrorCodes.BAD_PARAM, "未知变更类型");
        }
    }

    private void validatePayload(String type, Map<String, Object> p, Long roomId, Long cid) {
        switch (type) {
            case "ADD_MEMBER" -> {
                requireStr(p, "name");
                String mobile = requireStr(p, "mobile");
                if (!mobile.matches("^1\\d{10}$")) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "手机号须为 1 开头的 11 位数字");
                }
                String role = requireStr(p, "resident_role");
                if (!MEMBER_ROLES.contains(role)) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "成员角色须为非业主");
                }
            }
            case "REMOVE_MEMBER" -> {
                Long occupantId = requireLong(p, "occupant_id");
                RoomOccupant o = roomOccupantMapper.selectById(occupantId);
                if (o == null || !roomId.equals(o.getRoomId()) || !"ACTIVE".equals(o.getStatus())) {
                    throw BizException.of(ErrorCodes.NOT_FOUND, "成员不存在");
                }
                if ("OWNER".equals(o.getResidentRole())) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "不可申请移除业主");
                }
            }
            case "ADD_VEHICLE" -> {
                requireStr(p, "plate_no");
                Long spaceId = longValOpt(p, "parking_space_id");
                if (spaceId != null) {
                    assertRoomFreeSpace(roomId, spaceId);
                }
            }
            case "REMOVE_VEHICLE" -> requireRoomVehicle(roomId, requireLong(p, "vehicle_id"));
            case "LINK_PARKING" -> {
                Long spaceId = requireLong(p, "parking_space_id");
                ParkingSpace s = parkingSpaceMapper.selectById(spaceId);
                if (s == null || s.getDeletedAt() != null || !cid.equals(s.getCommunityId())) {
                    throw BizException.of(ErrorCodes.NOT_FOUND, "车位不存在");
                }
                if (s.getRoomId() != null) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "车位已挂其他房屋");
                }
            }
            case "UNLINK_PARKING" -> {
                Long spaceId = requireLong(p, "parking_space_id");
                ParkingSpace s = parkingSpaceMapper.selectById(spaceId);
                if (s == null || s.getDeletedAt() != null || !roomId.equals(s.getRoomId())) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "须为本房已挂车位");
                }
            }
            default -> throw BizException.of(ErrorCodes.BAD_PARAM, "不支持的变更类型");
        }
    }

    private SysUser findOrCreateUser(String mobile, String name) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getMobile, mobile.trim()));
        LocalDateTime now = LocalDateTime.now();
        if (user == null) {
            user = new SysUser();
            user.setMobile(mobile.trim());
            user.setRealName(name);
            user.setIsPlatformAdmin(0);
            user.setStatus(1);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            sysUserMapper.insert(user);
        } else if (StringUtils.hasText(name) && !StringUtils.hasText(user.getRealName())) {
            user.setRealName(name);
            user.setUpdatedAt(now);
            sysUserMapper.updateById(user);
        }
        return user;
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

    private void assertRoomFreeSpace(Long roomId, Long spaceId) {
        ParkingSpace s = parkingSpaceMapper.selectById(spaceId);
        if (s == null || s.getDeletedAt() != null || !roomId.equals(s.getRoomId())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "车位须为本房已挂车位");
        }
        long used = roomVehicleMapper.selectCount(new LambdaQueryWrapper<RoomVehicle>()
                .eq(RoomVehicle::getParkingSpaceId, spaceId));
        if (used > 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "车位已被占用");
        }
    }

    private RoomVehicle requireRoomVehicle(Long roomId, Long vehicleId) {
        RoomVehicle v = roomVehicleMapper.selectById(vehicleId);
        if (v == null || !roomId.equals(v.getRoomId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "车辆不存在");
        }
        return v;
    }

    private RoomChangeApplication require(Long id) {
        RoomChangeApplication app = changeMapper.selectById(id);
        if (app == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "变更申请不存在");
        }
        return app;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "payload 无效");
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "payload 解析失败");
        }
    }

    private Map<String, Object> fromJsonSafe(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static String requireStr(Map<String, Object> p, String key) {
        String v = str(p, key);
        if (!StringUtils.hasText(v)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, key + " 必填");
        }
        return v;
    }

    private static Long requireLong(Map<String, Object> p, String key) {
        Long v = longValOpt(p, key);
        if (v == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, key + " 必填");
        }
        return v;
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString().trim();
    }

    private static Long longVal(Map<String, Object> p, String key) {
        return requireLong(p, key);
    }

    private static Long longValOpt(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null || "".equals(v.toString().trim())) {
            return null;
        }
        return Long.valueOf(v.toString());
    }
}
