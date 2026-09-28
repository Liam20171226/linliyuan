package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.ParkingSpace;
import com.property.mgmt.domain.Room;
import com.property.mgmt.domain.RoomVehicle;
import com.property.mgmt.mapper.ParkingSpaceMapper;
import com.property.mgmt.mapper.RoomMapper;
import com.property.mgmt.mapper.RoomVehicleMapper;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ParkingService {

    private final ParkingSpaceMapper parkingSpaceMapper;
    private final RoomVehicleMapper roomVehicleMapper;
    private final RoomMapper roomMapper;
    private final StaffSpaceService staffSpaceService;
    private final OccupantQueryService occupantQueryService;

    public List<ParkingSpace> list(String linked, String spaceNo) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<ParkingSpace> q = new LambdaQueryWrapper<ParkingSpace>()
                .eq(ParkingSpace::getCommunityId, cid)
                .isNull(ParkingSpace::getDeletedAt)
                .like(StringUtils.hasText(spaceNo), ParkingSpace::getSpaceNo, spaceNo)
                .orderByAsc(ParkingSpace::getSpaceNo);
        if ("unlinked".equalsIgnoreCase(linked)) {
            q.isNull(ParkingSpace::getRoomId);
        } else if ("linked".equalsIgnoreCase(linked)) {
            q.isNotNull(ParkingSpace::getRoomId);
        }
        return parkingSpaceMapper.selectList(q);
    }

    /** 本小区车辆一览（物业后台住户·车辆 Tab）。 */
    public Map<String, Object> listVehicles(String plateNo, Long roomId, String bound, int page, int pageSize) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<RoomVehicle> q = new LambdaQueryWrapper<RoomVehicle>()
                .eq(RoomVehicle::getCommunityId, cid)
                .orderByDesc(RoomVehicle::getId);
        if (StringUtils.hasText(plateNo)) {
            q.like(RoomVehicle::getPlateNo, plateNo.trim());
        }
        if (roomId != null) {
            q.eq(RoomVehicle::getRoomId, roomId);
        }
        if ("yes".equalsIgnoreCase(bound) || "bound".equalsIgnoreCase(bound)) {
            q.isNotNull(RoomVehicle::getParkingSpaceId);
        } else if ("no".equalsIgnoreCase(bound) || "unbound".equalsIgnoreCase(bound)) {
            q.isNull(RoomVehicle::getParkingSpaceId);
        }
        Page<RoomVehicle> p = roomVehicleMapper.selectPage(new Page<>(page, pageSize), q);
        Set<Long> spaceIds = p.getRecords().stream()
                .map(RoomVehicle::getParkingSpaceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ParkingSpace> spaceMap = spaceIds.isEmpty() ? Map.of()
                : parkingSpaceMapper.selectList(new LambdaQueryWrapper<ParkingSpace>()
                        .in(ParkingSpace::getId, spaceIds))
                .stream()
                .collect(Collectors.toMap(ParkingSpace::getId, s -> s, (a, b) -> a));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RoomVehicle v : p.getRecords()) {
            Room room = roomMapper.selectById(v.getRoomId());
            ParkingSpace space = v.getParkingSpaceId() == null ? null : spaceMap.get(v.getParkingSpaceId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", v.getId());
            row.put("communityId", v.getCommunityId());
            row.put("roomId", v.getRoomId());
            row.put("roomNo", room == null ? "" : Optional.ofNullable(room.getRoomNo()).orElse(""));
            row.put("address", room == null ? "" : occupantQueryService.buildAddress(room));
            row.put("plateNo", v.getPlateNo());
            row.put("parkingSpaceId", v.getParkingSpaceId());
            row.put("parkingSpaceNo", space == null ? "" : Optional.ofNullable(space.getSpaceNo()).orElse(""));
            row.put("createdAt", v.getCreatedAt());
            row.put("updatedAt", v.getUpdatedAt());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", rows);
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    /** 本房已挂靠车位；availableOnly=true 时仅返回当前未被车辆占用的空位（可含 keepSpaceId）。 */
    public List<Map<String, Object>> roomParkingOptions(Long roomId, boolean availableOnly, Long keepSpaceId) {
        StaffGuard.communityId();
        staffSpaceService.requireRoom(roomId);
        List<ParkingSpace> spaces = parkingSpaceMapper.selectList(new LambdaQueryWrapper<ParkingSpace>()
                .eq(ParkingSpace::getRoomId, roomId)
                .isNull(ParkingSpace::getDeletedAt)
                .orderByAsc(ParkingSpace::getSpaceNo));
        Set<Long> occupied = roomVehicleMapper.selectList(new LambdaQueryWrapper<RoomVehicle>()
                        .eq(RoomVehicle::getRoomId, roomId)
                        .isNotNull(RoomVehicle::getParkingSpaceId))
                .stream()
                .map(RoomVehicle::getParkingSpaceId)
                .collect(Collectors.toSet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (ParkingSpace s : spaces) {
            boolean free = !occupied.contains(s.getId())
                    || (keepSpaceId != null && keepSpaceId.equals(s.getId()));
            if (availableOnly && !free) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("spaceNo", s.getSpaceNo());
            row.put("occupied", occupied.contains(s.getId()) && (keepSpaceId == null || !keepSpaceId.equals(s.getId())));
            out.add(row);
        }
        return out;
    }

    public ParkingSpace create(String spaceNo, String remark) {
        Long cid = StaffGuard.communityId();
        assertUniqueSpace(cid, spaceNo, null);
        ParkingSpace p = new ParkingSpace();
        p.setCommunityId(cid);
        p.setSpaceNo(spaceNo.trim());
        p.setRemark(remark);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        parkingSpaceMapper.insert(p);
        return p;
    }

    public void update(Long id, String spaceNo, String remark) {
        ParkingSpace p = require(id);
        if (StringUtils.hasText(spaceNo)) {
            assertUniqueSpace(p.getCommunityId(), spaceNo, id);
            p.setSpaceNo(spaceNo.trim());
        }
        if (remark != null) {
            p.setRemark(remark);
        }
        p.setUpdatedAt(LocalDateTime.now());
        parkingSpaceMapper.updateById(p);
    }

    @Transactional
    public void link(Long id, Long roomId) {
        ParkingSpace p = require(id);
        if (p.getRoomId() != null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "车位已挂靠房屋");
        }
        Room room = staffSpaceService.requireRoom(roomId);
        p.setRoomId(room.getId());
        p.setUpdatedAt(LocalDateTime.now());
        parkingSpaceMapper.updateById(p);
        autoBindUnboundVehicles(room.getId());
    }

    @Transactional
    public void unlink(Long id) {
        ParkingSpace p = require(id);
        Long roomId = p.getRoomId();
        if (roomId == null) {
            return;
        }
        rebalanceVehicleLeavingSpace(p.getId(), roomId);
        p.setRoomId(null);
        p.setUpdatedAt(LocalDateTime.now());
        parkingSpaceMapper.updateById(p);
    }

    @Transactional
    public void relink(Long id, Long newRoomId) {
        ParkingSpace p = require(id);
        Long oldRoomId = p.getRoomId();
        Room newRoom = staffSpaceService.requireRoom(newRoomId);
        if (Objects.equals(oldRoomId, newRoomId)) {
            return;
        }
        if (oldRoomId != null) {
            rebalanceVehicleLeavingSpace(p.getId(), oldRoomId);
        }
        p.setRoomId(newRoom.getId());
        p.setUpdatedAt(LocalDateTime.now());
        parkingSpaceMapper.updateById(p);
        autoBindUnboundVehicles(newRoom.getId());
    }

    /**
     * 车位脱离：原绑该车位的车先脱离，再按 space_no 尝试绑到本房下一空闲车位。
     */
    private void rebalanceVehicleLeavingSpace(Long spaceId, Long roomId) {
        RoomVehicle vehicle = roomVehicleMapper.selectOne(new LambdaQueryWrapper<RoomVehicle>()
                .eq(RoomVehicle::getParkingSpaceId, spaceId));
        if (vehicle != null) {
            vehicle.setParkingSpaceId(null);
            vehicle.setUpdatedAt(LocalDateTime.now());
            roomVehicleMapper.updateById(vehicle);
        }
        autoBindUnboundVehicles(roomId);
    }

    /**
     * 空位按 space_no；未绑车辆按 created_at 补绑。
     */
    public void autoBindUnboundVehicles(Long roomId) {
        List<ParkingSpace> spaces = parkingSpaceMapper.selectList(new LambdaQueryWrapper<ParkingSpace>()
                .eq(ParkingSpace::getRoomId, roomId)
                .isNull(ParkingSpace::getDeletedAt)
                .orderByAsc(ParkingSpace::getSpaceNo));
        Set<Long> occupied = roomVehicleMapper.selectList(new LambdaQueryWrapper<RoomVehicle>()
                        .eq(RoomVehicle::getRoomId, roomId)
                        .isNotNull(RoomVehicle::getParkingSpaceId))
                .stream()
                .map(RoomVehicle::getParkingSpaceId)
                .collect(Collectors.toSet());

        List<ParkingSpace> free = spaces.stream()
                .filter(s -> !occupied.contains(s.getId()))
                .sorted(Comparator.comparing(ParkingSpace::getSpaceNo))
                .toList();

        List<RoomVehicle> unbound = roomVehicleMapper.selectList(new LambdaQueryWrapper<RoomVehicle>()
                .eq(RoomVehicle::getRoomId, roomId)
                .isNull(RoomVehicle::getParkingSpaceId)
                .orderByAsc(RoomVehicle::getCreatedAt));

        int n = Math.min(free.size(), unbound.size());
        for (int i = 0; i < n; i++) {
            RoomVehicle v = unbound.get(i);
            v.setParkingSpaceId(free.get(i).getId());
            v.setUpdatedAt(LocalDateTime.now());
            roomVehicleMapper.updateById(v);
        }
    }

    private ParkingSpace require(Long id) {
        ParkingSpace p = parkingSpaceMapper.selectById(id);
        if (p == null || p.getDeletedAt() != null || !p.getCommunityId().equals(StaffGuard.communityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "车位不存在");
        }
        return p;
    }

    private void assertUniqueSpace(Long cid, String spaceNo, Long excludeId) {
        Long cnt = parkingSpaceMapper.selectCount(new LambdaQueryWrapper<ParkingSpace>()
                .eq(ParkingSpace::getCommunityId, cid)
                .eq(ParkingSpace::getSpaceNo, spaceNo.trim())
                .isNull(ParkingSpace::getDeletedAt)
                .ne(excludeId != null, ParkingSpace::getId, excludeId));
        if (cnt > 0) {
            throw BizException.of(ErrorCodes.SPACE_DUP, "车位编号已存在");
        }
    }
}
