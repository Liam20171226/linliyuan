package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StaffSpaceService {

    private final CommunityMapper communityMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final FloorMapper floorMapper;
    private final RoomMapper roomMapper;
    private final CommunityHouseTypeMapper houseTypeMapper;
    private final RoomOccupantMapper roomOccupantMapper;
    private final SysUserMapper sysUserMapper;

    public Community getCommunity() {
        Long cid = StaffGuard.communityId();
        Community c = communityMapper.selectById(cid);
        if (c == null || c.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "小区不存在");
        }
        return c;
    }

    public void updateCommunity(String name, String address, String intro, String contactPhone, Long coverAttachmentId,
                                String provinceCode, String provinceName, String cityCode, String cityName,
                                String districtCode, String districtName, String addressDetail) {
        Community c = getCommunity();
        if (StringUtils.hasText(name)) {
            c.setName(name);
        }
        String resolved = RegionCatalog.resolveStoredAddress(address, provinceCode, provinceName,
                cityCode, cityName, districtCode, districtName, addressDetail);
        if (resolved != null) {
            c.setAddress(resolved);
        }
        if (intro != null) {
            c.setIntro(intro);
        }
        if (contactPhone != null) {
            c.setContactPhone(contactPhone);
        }
        if (coverAttachmentId != null) {
            c.setCoverAttachmentId(coverAttachmentId);
        }
        c.setUpdatedAt(LocalDateTime.now());
        communityMapper.updateById(c);
    }

    // ---------- buildings ----------

    public List<Building> listBuildings() {
        Long cid = StaffGuard.communityId();
        return buildingMapper.selectList(new LambdaQueryWrapper<Building>()
                .eq(Building::getCommunityId, cid)
                .isNull(Building::getDeletedAt)
                .orderByAsc(Building::getName));
    }

    public Building createBuilding(String name) {
        Long cid = StaffGuard.communityId();
        assertUniqueBuilding(cid, name, null);
        Building b = new Building();
        b.setCommunityId(cid);
        b.setName(name.trim());
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        buildingMapper.insert(b);
        return b;
    }

    public void updateBuilding(Long id, String name) {
        Building b = requireBuilding(id);
        if (StringUtils.hasText(name)) {
            assertUniqueBuilding(b.getCommunityId(), name, id);
            b.setName(name.trim());
        }
        b.setUpdatedAt(LocalDateTime.now());
        buildingMapper.updateById(b);
    }

    public void deleteBuilding(Long id) {
        deleteBuilding(id, false);
    }

    @Transactional
    public void deleteBuilding(Long id, boolean releaseOccupants) {
        Building b = requireBuilding(id);
        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getBuildingId, id)
                .isNull(Room::getDeletedAt));
        releaseOrRejectRooms(rooms, releaseOccupants, "楼栋");
        LocalDateTime now = LocalDateTime.now();
        for (Room r : rooms) {
            r.setDeletedAt(now);
            r.setUpdatedAt(now);
            roomMapper.updateById(r);
        }
        List<Floor> floors = floorMapper.selectList(new LambdaQueryWrapper<Floor>()
                .eq(Floor::getBuildingId, id).isNull(Floor::getDeletedAt));
        for (Floor f : floors) {
            f.setDeletedAt(now);
            f.setUpdatedAt(now);
            floorMapper.updateById(f);
        }
        List<Unit> units = unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                .eq(Unit::getBuildingId, id).isNull(Unit::getDeletedAt));
        for (Unit u : units) {
            u.setDeletedAt(now);
            u.setUpdatedAt(now);
            unitMapper.updateById(u);
        }
        b.setDeletedAt(now);
        b.setUpdatedAt(now);
        buildingMapper.updateById(b);
    }

    // ---------- units ----------

    public List<Unit> listUnits(Long buildingId) {
        requireBuilding(buildingId);
        return unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                .eq(Unit::getBuildingId, buildingId)
                .isNull(Unit::getDeletedAt)
                .orderByAsc(Unit::getName));
    }

    public Unit createUnit(Long buildingId, String name) {
        Building b = requireBuilding(buildingId);
        assertUniqueUnit(buildingId, name, null);
        Unit u = new Unit();
        u.setCommunityId(b.getCommunityId());
        u.setBuildingId(buildingId);
        u.setName(name.trim());
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        unitMapper.insert(u);
        return u;
    }

    public void updateUnit(Long id, String name) {
        Unit u = requireUnit(id);
        if (StringUtils.hasText(name)) {
            assertUniqueUnit(u.getBuildingId(), name, id);
            u.setName(name.trim());
        }
        u.setUpdatedAt(LocalDateTime.now());
        unitMapper.updateById(u);
    }

    public void deleteUnit(Long id) {
        deleteUnit(id, false);
    }

    @Transactional
    public void deleteUnit(Long id, boolean releaseOccupants) {
        Unit u = requireUnit(id);
        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getUnitId, id)
                .isNull(Room::getDeletedAt));
        releaseOrRejectRooms(rooms, releaseOccupants, "单元");
        LocalDateTime now = LocalDateTime.now();
        for (Room r : rooms) {
            r.setDeletedAt(now);
            r.setUpdatedAt(now);
            roomMapper.updateById(r);
        }
        List<Floor> floors = floorMapper.selectList(new LambdaQueryWrapper<Floor>()
                .eq(Floor::getUnitId, id).isNull(Floor::getDeletedAt));
        for (Floor f : floors) {
            f.setDeletedAt(now);
            f.setUpdatedAt(now);
            floorMapper.updateById(f);
        }
        u.setDeletedAt(now);
        u.setUpdatedAt(now);
        unitMapper.updateById(u);
    }

    // ---------- floors ----------

    public List<Floor> listFloors(Long unitId) {
        requireUnit(unitId);
        return floorMapper.selectList(new LambdaQueryWrapper<Floor>()
                .eq(Floor::getUnitId, unitId)
                .isNull(Floor::getDeletedAt)
                .orderByAsc(Floor::getFloorNo)
                .orderByAsc(Floor::getName));
    }

    public Floor createFloor(Long unitId, String name, Integer floorNo) {
        Unit u = requireUnit(unitId);
        assertUniqueFloor(unitId, name, floorNo, null);
        Floor f = new Floor();
        f.setCommunityId(u.getCommunityId());
        f.setBuildingId(u.getBuildingId());
        f.setUnitId(unitId);
        f.setName(name.trim());
        f.setFloorNo(floorNo);
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.now());
        floorMapper.insert(f);
        return f;
    }

    public void updateFloor(Long id, String name, Integer floorNo) {
        Floor f = requireFloor(id);
        if (StringUtils.hasText(name) || floorNo != null) {
            String newName = StringUtils.hasText(name) ? name.trim() : f.getName();
            Integer newNo = floorNo != null ? floorNo : f.getFloorNo();
            assertUniqueFloor(f.getUnitId(), newName, newNo, id);
            f.setName(newName);
            f.setFloorNo(newNo);
        }
        f.setUpdatedAt(LocalDateTime.now());
        floorMapper.updateById(f);
    }

    public void deleteFloor(Long id) {
        deleteFloor(id, false);
    }

    @Transactional
    public void deleteFloor(Long id, boolean releaseOccupants) {
        Floor f = requireFloor(id);
        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getFloorId, id)
                .isNull(Room::getDeletedAt));
        releaseOrRejectRooms(rooms, releaseOccupants, "楼层");
        LocalDateTime now = LocalDateTime.now();
        for (Room r : rooms) {
            r.setDeletedAt(now);
            r.setUpdatedAt(now);
            roomMapper.updateById(r);
        }
        f.setDeletedAt(now);
        f.setUpdatedAt(now);
        floorMapper.updateById(f);
    }

    /** 汇总范围内房屋的住户展示名，供前端提示 */
    public Map<String, Object> summarizeOccupantsInRooms(List<Long> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return Map.of("occupants", List.of(), "names", "");
        }
        List<RoomOccupant> active = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .in(RoomOccupant::getRoomId, roomIds)
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        List<Map<String, Object>> people = new ArrayList<>();
        Set<String> nameSet = new LinkedHashSet<>();
        for (RoomOccupant o : active) {
            SysUser u = sysUserMapper.selectById(o.getUserId());
            String display = u == null ? ("用户" + o.getUserId())
                    : (StringUtils.hasText(u.getRealName()) ? u.getRealName()
                    : (StringUtils.hasText(u.getMobile()) ? u.getMobile() : ("用户" + o.getUserId())));
            nameSet.add(display);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", o.getUserId());
            row.put("roomId", o.getRoomId());
            row.put("displayName", display);
            people.add(row);
        }
        return Map.of("occupants", people, "names", String.join("、", nameSet), "count", people.size());
    }

    public Map<String, Object> occupantsUnderBuilding(Long buildingId) {
        requireBuilding(buildingId);
        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getBuildingId, buildingId).isNull(Room::getDeletedAt));
        return summarizeOccupantsInRooms(rooms.stream().map(Room::getId).toList());
    }

    public Map<String, Object> occupantsUnderUnit(Long unitId) {
        requireUnit(unitId);
        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getUnitId, unitId).isNull(Room::getDeletedAt));
        return summarizeOccupantsInRooms(rooms.stream().map(Room::getId).toList());
    }

    public Map<String, Object> occupantsUnderFloor(Long floorId) {
        requireFloor(floorId);
        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getFloorId, floorId).isNull(Room::getDeletedAt));
        return summarizeOccupantsInRooms(rooms.stream().map(Room::getId).toList());
    }

    private void releaseOrRejectRooms(List<Room> rooms, boolean releaseOccupants, String scopeLabel) {
        if (rooms.isEmpty()) return;
        List<Long> roomIds = rooms.stream().map(Room::getId).toList();
        List<RoomOccupant> active = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .in(RoomOccupant::getRoomId, roomIds)
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        if (!active.isEmpty() && !releaseOccupants) {
            throw BizException.of(ErrorCodes.BAD_PARAM,
                    scopeLabel + "下尚有住户，请确认是否将其变为游客后再删除");
        }
        if (!active.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            for (RoomOccupant o : active) {
                o.setStatus("INACTIVE");
                o.setUpdatedAt(now);
                roomOccupantMapper.updateById(o);
            }
        }
    }

    // ---------- rooms ----------

    public List<Room> listRooms(Long floorId) {
        requireFloor(floorId);
        return roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getFloorId, floorId)
                .isNull(Room::getDeletedAt)
                .orderByAsc(Room::getRoomNo));
    }

    public Room createRoom(Long floorId, String roomNo, BigDecimal areaSqm, Long houseTypeId, Integer status) {
        Floor f = requireFloor(floorId);
        assertUniqueRoom(floorId, roomNo, null);
        if (areaSqm != null && areaSqm.compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "面积不能小于 0");
        }
        Room r = new Room();
        r.setCommunityId(f.getCommunityId());
        r.setBuildingId(f.getBuildingId());
        r.setUnitId(f.getUnitId());
        r.setFloorId(floorId);
        r.setRoomNo(roomNo.trim());
        r.setAreaSqm(areaSqm);
        r.setHouseTypeId(houseTypeId);
        r.setStatus(status == null ? 1 : status);
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        roomMapper.insert(r);
        return r;
    }

    public Room updateRoom(Long id, String roomNo, BigDecimal areaSqm, Long houseTypeId, Integer status) {
        Room r = requireRoom(id);
        if (StringUtils.hasText(roomNo)) {
            assertUniqueRoom(r.getFloorId(), roomNo, id);
            r.setRoomNo(roomNo.trim());
        }
        if (areaSqm != null) {
            if (areaSqm.compareTo(BigDecimal.ZERO) < 0) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "面积不能小于 0");
            }
            r.setAreaSqm(areaSqm);
        }
        // allow clearing? only set if provided via wrapper - for null area use sentinel in controller
        if (houseTypeId != null) {
            r.setHouseTypeId(houseTypeId == 0L ? null : houseTypeId);
        }
        if (status != null) {
            r.setStatus(status);
        }
        r.setUpdatedAt(LocalDateTime.now());
        roomMapper.updateById(r);
        return r;
    }

    /** 查询房屋当前 ACTIVE 住户（删除前提示用） */
    public Map<String, Object> listActiveOccupants(Long roomId) {
        Room r = requireRoom(roomId);
        List<RoomOccupant> occupants = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getRoomId, roomId)
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        List<Map<String, Object>> people = new ArrayList<>();
        for (RoomOccupant o : occupants) {
            SysUser u = sysUserMapper.selectById(o.getUserId());
            String display = u == null ? ("用户" + o.getUserId())
                    : (StringUtils.hasText(u.getRealName()) ? u.getRealName()
                    : (StringUtils.hasText(u.getMobile()) ? u.getMobile() : ("用户" + o.getUserId())));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("occupantId", o.getId());
            row.put("userId", o.getUserId());
            row.put("displayName", display);
            row.put("residentRole", o.getResidentRole());
            people.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomId", r.getId());
        out.put("roomNo", r.getRoomNo());
        out.put("occupants", people);
        return out;
    }

    /**
     * 删除房屋。若有住户且 releaseOccupants=false → 拒绝并提示先确认；
     * releaseOccupants=true → 将 ACTIVE 住户置为 INACTIVE（变为游客身份可用性），再软删房屋。
     */
    @Transactional
    public void deleteRoom(Long id, boolean releaseOccupants) {
        Room r = requireRoom(id);
        List<RoomOccupant> active = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getRoomId, id)
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        if (!active.isEmpty() && !releaseOccupants) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "房屋尚有住户，请确认是否将其变为游客后再删除");
        }
        if (!active.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            for (RoomOccupant o : active) {
                o.setStatus("INACTIVE");
                o.setUpdatedAt(now);
                roomOccupantMapper.updateById(o);
            }
        }
        r.setDeletedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        roomMapper.updateById(r);
    }

    /**
     * 按模板批量生成楼栋/单元/楼层/房屋（平台审核建小区时调用，不依赖 StaffGuard）。
     */
    @Transactional
    public void generateFromTemplate(Long communityId, String templateCode) {
        CommunitySpaceTemplates.Template t = CommunitySpaceTemplates.require(templateCode);
        Community c = communityMapper.selectById(communityId);
        if (c == null || c.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "小区不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        for (int bi = 1; bi <= t.buildings(); bi++) {
            Building b = new Building();
            b.setCommunityId(communityId);
            b.setName(bi + "栋");
            b.setCreatedAt(now);
            b.setUpdatedAt(now);
            buildingMapper.insert(b);
            for (int ui = 1; ui <= t.unitsPerBuilding(); ui++) {
                Unit u = new Unit();
                u.setCommunityId(communityId);
                u.setBuildingId(b.getId());
                u.setName(ui + "单元");
                u.setCreatedAt(now);
                u.setUpdatedAt(now);
                unitMapper.insert(u);
                for (int fi = 1; fi <= t.floorsPerUnit(); fi++) {
                    Floor f = new Floor();
                    f.setCommunityId(communityId);
                    f.setBuildingId(b.getId());
                    f.setUnitId(u.getId());
                    f.setName(fi + "层");
                    f.setFloorNo(fi);
                    f.setCreatedAt(now);
                    f.setUpdatedAt(now);
                    floorMapper.insert(f);
                    for (int ri = 1; ri <= t.roomsPerFloor(); ri++) {
                        String roomNo = fi + String.format("%02d", ri);
                        Room r = new Room();
                        r.setCommunityId(communityId);
                        r.setBuildingId(b.getId());
                        r.setUnitId(u.getId());
                        r.setFloorId(f.getId());
                        r.setRoomNo(roomNo);
                        r.setStatus(1);
                        r.setCreatedAt(now);
                        r.setUpdatedAt(now);
                        roomMapper.insert(r);
                    }
                }
            }
        }
    }

    public Map<String, Object> spaceTree() {
        Long cid = StaffGuard.communityId();
        List<Building> buildings = listBuildings();
        List<Unit> units = unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                .eq(Unit::getCommunityId, cid).isNull(Unit::getDeletedAt));
        List<Floor> floors = floorMapper.selectList(new LambdaQueryWrapper<Floor>()
                .eq(Floor::getCommunityId, cid).isNull(Floor::getDeletedAt));
        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getCommunityId, cid).isNull(Room::getDeletedAt));

        Map<Long, List<Unit>> unitsByBuilding = units.stream().collect(Collectors.groupingBy(Unit::getBuildingId));
        Map<Long, List<Floor>> floorsByUnit = floors.stream().collect(Collectors.groupingBy(Floor::getUnitId));
        Map<Long, List<Room>> roomsByFloor = rooms.stream().collect(Collectors.groupingBy(Room::getFloorId));

        List<Map<String, Object>> tree = new ArrayList<>();
        for (Building b : buildings) {
            Map<String, Object> bn = new LinkedHashMap<>();
            bn.put("id", b.getId());
            bn.put("name", b.getName());
            List<Map<String, Object>> unitNodes = new ArrayList<>();
            for (Unit u : unitsByBuilding.getOrDefault(b.getId(), List.of())) {
                Map<String, Object> un = new LinkedHashMap<>();
                un.put("id", u.getId());
                un.put("name", u.getName());
                List<Map<String, Object>> floorNodes = new ArrayList<>();
                for (Floor f : floorsByUnit.getOrDefault(u.getId(), List.of())) {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("id", f.getId());
                    fn.put("name", f.getName());
                    fn.put("floorNo", f.getFloorNo());
                    List<Map<String, Object>> roomNodes = roomsByFloor.getOrDefault(f.getId(), List.of()).stream()
                            .map(r -> {
                                Map<String, Object> rn = new LinkedHashMap<>();
                                rn.put("id", r.getId());
                                rn.put("roomNo", r.getRoomNo());
                                rn.put("areaSqm", r.getAreaSqm());
                                rn.put("houseTypeId", r.getHouseTypeId());
                                rn.put("status", r.getStatus());
                                return rn;
                            }).collect(Collectors.toList());
                    fn.put("rooms", roomNodes);
                    floorNodes.add(fn);
                }
                un.put("floors", floorNodes);
                unitNodes.add(un);
            }
            bn.put("units", unitNodes);
            tree.add(bn);
        }
        return Map.of("buildings", tree);
    }

    // ---------- house types ----------

    public List<CommunityHouseType> listHouseTypes() {
        Long cid = StaffGuard.communityId();
        return houseTypeMapper.selectList(new LambdaQueryWrapper<CommunityHouseType>()
                .eq(CommunityHouseType::getCommunityId, cid)
                .isNull(CommunityHouseType::getDeletedAt)
                .orderByAsc(CommunityHouseType::getSortNo)
                .orderByAsc(CommunityHouseType::getId));
    }

    public CommunityHouseType createHouseType(String name, BigDecimal price, Integer sortNo) {
        Long cid = StaffGuard.communityId();
        assertUniqueHouseType(cid, name, null);
        CommunityHouseType t = new CommunityHouseType();
        t.setCommunityId(cid);
        t.setName(name.trim());
        t.setPropertyFeeUnitPrice(price);
        t.setSortNo(sortNo == null ? 0 : sortNo);
        t.setStatus(1);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        houseTypeMapper.insert(t);
        return t;
    }

    public void updateHouseType(Long id, String name, BigDecimal price, Integer sortNo) {
        CommunityHouseType t = requireHouseType(id);
        if (StringUtils.hasText(name)) {
            assertUniqueHouseType(t.getCommunityId(), name, id);
            t.setName(name.trim());
        }
        if (price != null) {
            t.setPropertyFeeUnitPrice(price);
        }
        if (sortNo != null) {
            t.setSortNo(sortNo);
        }
        t.setUpdatedAt(LocalDateTime.now());
        houseTypeMapper.updateById(t);
    }

    public void updateHouseTypeStatus(Long id, Integer status) {
        CommunityHouseType t = requireHouseType(id);
        t.setStatus(status);
        t.setUpdatedAt(LocalDateTime.now());
        houseTypeMapper.updateById(t);
    }

    // ---------- helpers ----------

    private Building requireBuilding(Long id) {
        Building b = buildingMapper.selectById(id);
        if (b == null || b.getDeletedAt() != null || !b.getCommunityId().equals(StaffGuard.communityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "楼栋不存在");
        }
        return b;
    }

    private Unit requireUnit(Long id) {
        Unit u = unitMapper.selectById(id);
        if (u == null || u.getDeletedAt() != null || !u.getCommunityId().equals(StaffGuard.communityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "单元不存在");
        }
        return u;
    }

    private Floor requireFloor(Long id) {
        Floor f = floorMapper.selectById(id);
        if (f == null || f.getDeletedAt() != null || !f.getCommunityId().equals(StaffGuard.communityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "楼层不存在");
        }
        return f;
    }

    public Room requireRoom(Long id) {
        Room r = roomMapper.selectById(id);
        if (r == null || r.getDeletedAt() != null || !r.getCommunityId().equals(StaffGuard.communityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在");
        }
        return r;
    }

    private CommunityHouseType requireHouseType(Long id) {
        CommunityHouseType t = houseTypeMapper.selectById(id);
        if (t == null || t.getDeletedAt() != null || !t.getCommunityId().equals(StaffGuard.communityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "房屋类型不存在");
        }
        return t;
    }

    private void assertUniqueBuilding(Long cid, String name, Long excludeId) {
        Long cnt = buildingMapper.selectCount(new LambdaQueryWrapper<Building>()
                .eq(Building::getCommunityId, cid)
                .eq(Building::getName, name.trim())
                .isNull(Building::getDeletedAt)
                .ne(excludeId != null, Building::getId, excludeId));
        if (cnt > 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "楼栋名称已存在");
        }
    }

    private void assertUniqueUnit(Long buildingId, String name, Long excludeId) {
        Long cnt = unitMapper.selectCount(new LambdaQueryWrapper<Unit>()
                .eq(Unit::getBuildingId, buildingId)
                .eq(Unit::getName, name.trim())
                .isNull(Unit::getDeletedAt)
                .ne(excludeId != null, Unit::getId, excludeId));
        if (cnt > 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "单元名称已存在");
        }
    }

    private void assertUniqueFloor(Long unitId, String name, Integer floorNo, Long excludeId) {
        Long cnt = floorMapper.selectCount(new LambdaQueryWrapper<Floor>()
                .eq(Floor::getUnitId, unitId)
                .eq(Floor::getName, name.trim())
                .isNull(Floor::getDeletedAt)
                .ne(excludeId != null, Floor::getId, excludeId));
        if (cnt > 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "楼层名称已存在");
        }
        if (floorNo != null) {
            Long cnt2 = floorMapper.selectCount(new LambdaQueryWrapper<Floor>()
                    .eq(Floor::getUnitId, unitId)
                    .eq(Floor::getFloorNo, floorNo)
                    .isNull(Floor::getDeletedAt)
                    .ne(excludeId != null, Floor::getId, excludeId));
            if (cnt2 > 0) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "楼层号已存在");
            }
        }
    }

    private void assertUniqueRoom(Long floorId, String roomNo, Long excludeId) {
        Long cnt = roomMapper.selectCount(new LambdaQueryWrapper<Room>()
                .eq(Room::getFloorId, floorId)
                .eq(Room::getRoomNo, roomNo.trim())
                .isNull(Room::getDeletedAt)
                .ne(excludeId != null, Room::getId, excludeId));
        if (cnt > 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "房号已存在");
        }
    }

    private void assertUniqueHouseType(Long cid, String name, Long excludeId) {
        Long cnt = houseTypeMapper.selectCount(new LambdaQueryWrapper<CommunityHouseType>()
                .eq(CommunityHouseType::getCommunityId, cid)
                .eq(CommunityHouseType::getName, name.trim())
                .isNull(CommunityHouseType::getDeletedAt)
                .ne(excludeId != null, CommunityHouseType::getId, excludeId));
        if (cnt > 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "房屋类型名称已存在");
        }
    }
}
