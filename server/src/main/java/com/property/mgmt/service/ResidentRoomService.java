package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ResidentRoomService {

    private final RoomOccupantMapper roomOccupantMapper;
    private final RoomMapper roomMapper;
    private final CommunityHouseTypeMapper houseTypeMapper;
    private final SysUserMapper sysUserMapper;
    private final RoomVehicleMapper roomVehicleMapper;
    private final ParkingSpaceMapper parkingSpaceMapper;
    private final AttachmentService attachmentService;
    private final OccupantQueryService occupantQueryService;

    public List<Map<String, Object>> myRooms() {
        AuthUser auth = AuthContext.require();
        Long uid = auth.getUserId();
        LambdaQueryWrapper<RoomOccupant> q = new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getUserId, uid)
                .eq(RoomOccupant::getStatus, "ACTIVE")
                .orderByDesc(RoomOccupant::getId);
        if (auth.getCommunityId() != null && "RESIDENT".equals(auth.getIdentityType())) {
            q.eq(RoomOccupant::getCommunityId, auth.getCommunityId());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        Map<Long, RoomOccupant> bestByRoom = new LinkedHashMap<>();
        for (RoomOccupant o : roomOccupantMapper.selectList(q)) {
            RoomOccupant prev = bestByRoom.get(o.getRoomId());
            if (prev == null || RoomOccupantBindService.compareRolePriority(o.getResidentRole(), prev.getResidentRole()) < 0) {
                bestByRoom.put(o.getRoomId(), o);
            }
        }
        for (RoomOccupant o : bestByRoom.values()) {
            Room room = roomMapper.selectById(o.getRoomId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("occupantId", o.getId());
            row.put("communityId", o.getCommunityId());
            row.put("roomId", o.getRoomId());
            row.put("roomNo", room == null ? "" : room.getRoomNo());
            row.put("address", room == null ? "" : occupantQueryService.buildAddress(room));
            row.put("residentRole", o.getResidentRole());
            row.put("areaSqm", room == null ? null : room.getAreaSqm());
            row.put("houseTypeId", room == null ? null : room.getHouseTypeId());
            list.add(row);
        }
        return list;
    }

    public Map<String, Object> roomDetail(Long roomId) {
        RoomOccupant me = occupantQueryService.requireActiveOccupant(roomId);
        Room room = roomMapper.selectById(roomId);
        if (room == null || room.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在");
        }
        CommunityHouseType ht = room.getHouseTypeId() == null ? null : houseTypeMapper.selectById(room.getHouseTypeId());

        List<RoomOccupant> members = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getRoomId, roomId)
                .eq(RoomOccupant::getStatus, "ACTIVE")
                .orderByAsc(RoomOccupant::getId));
        List<Map<String, Object>> memberRows = new ArrayList<>();
        for (RoomOccupant o : members) {
            SysUser u = sysUserMapper.selectById(o.getUserId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("occupantId", o.getId());
            m.put("userId", o.getUserId());
            m.put("realName", u == null ? "" : Optional.ofNullable(u.getRealName()).orElse(""));
            m.put("mobile", u == null ? "" : Optional.ofNullable(u.getMobile()).orElse(""));
            m.put("residentRole", o.getResidentRole());
            memberRows.add(m);
        }

        List<RoomVehicle> vehicles = roomVehicleMapper.selectList(new LambdaQueryWrapper<RoomVehicle>()
                .eq(RoomVehicle::getRoomId, roomId)
                .orderByAsc(RoomVehicle::getId));
        List<ParkingSpace> parkings = parkingSpaceMapper.selectList(new LambdaQueryWrapper<ParkingSpace>()
                .eq(ParkingSpace::getRoomId, roomId)
                .isNull(ParkingSpace::getDeletedAt)
                .orderByAsc(ParkingSpace::getSpaceNo));
        Map<Long, String> spaceNoById = new HashMap<>();
        for (ParkingSpace ps : parkings) {
            spaceNoById.put(ps.getId(), Optional.ofNullable(ps.getSpaceNo()).orElse(""));
        }
        List<Map<String, Object>> vehicleRows = new ArrayList<>();
        for (RoomVehicle v : vehicles) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", v.getId());
            row.put("plateNo", v.getPlateNo());
            row.put("parkingSpaceId", v.getParkingSpaceId());
            String spaceNo = v.getParkingSpaceId() == null ? null : spaceNoById.get(v.getParkingSpaceId());
            row.put("parkingSpaceNo", spaceNo == null ? "" : spaceNo);
            vehicleRows.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", room.getId());
        data.put("communityId", room.getCommunityId());
        data.put("roomNo", room.getRoomNo());
        data.put("address", occupantQueryService.buildAddress(room));
        data.put("areaSqm", room.getAreaSqm());
        data.put("houseTypeId", room.getHouseTypeId());
        data.put("houseTypeName", ht == null ? null : ht.getName());
        data.put("myRole", me.getResidentRole());
        data.put("members", memberRows);
        data.put("vehicles", vehicleRows);
        data.put("parkingSpaces", parkings);
        if ("OWNER".equals(me.getResidentRole())) {
            data.put("archives", attachmentService.listByBiz("ROOM_ARCHIVE", roomId));
        }
        return data;
    }

    public List<Attachment> archives(Long roomId) {
        RoomOccupant me = occupantQueryService.requireActiveOccupant(roomId);
        if (!"OWNER".equals(me.getResidentRole())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "仅业主可查看归档材料");
        }
        return attachmentService.listByBiz("ROOM_ARCHIVE", roomId);
    }

    public List<ParkingSpace> unlinkedParking() {
        AuthUser auth = AuthContext.require();
        if (!"RESIDENT".equals(auth.getIdentityType()) || auth.getRoomId() == null || auth.getCommunityId() == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换为住户并选择房屋");
        }
        RoomOccupant me = occupantQueryService.requireActiveOccupant(auth.getRoomId());
        if (!"OWNER".equals(me.getResidentRole())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "仅业主可查询未挂房车位");
        }
        return parkingSpaceMapper.selectList(new LambdaQueryWrapper<ParkingSpace>()
                .eq(ParkingSpace::getCommunityId, auth.getCommunityId())
                .isNull(ParkingSpace::getRoomId)
                .isNull(ParkingSpace::getDeletedAt)
                .orderByAsc(ParkingSpace::getSpaceNo));
    }

    /** 业主增加成员时按手机号匹配已有账号（仅回存在性与姓名） */
    public Map<String, Object> lookupUserByMobile(String mobile) {
        AuthUser auth = AuthContext.require();
        if (!"RESIDENT".equals(auth.getIdentityType()) || auth.getRoomId() == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换为住户并选择房屋");
        }
        RoomOccupant me = occupantQueryService.requireActiveOccupant(auth.getRoomId());
        if (!"OWNER".equals(me.getResidentRole())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "仅业主可为房屋增加成员");
        }
        String m = mobile == null ? "" : mobile.trim();
        if (!m.matches("^1\\d{10}$")) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "手机号须为 1 开头的 11 位数字");
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
        out.put("realName", u.getRealName() == null ? "" : u.getRealName());
        return out;
    }
}
