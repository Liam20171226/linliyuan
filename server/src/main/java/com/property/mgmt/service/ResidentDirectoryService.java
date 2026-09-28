package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.Collator;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 住户侧选小区/选房（认证申请 S2）：省市区 + 名称搜小区，再拉空间树。
 */
@Service
@RequiredArgsConstructor
public class ResidentDirectoryService {

    private final CommunityMapper communityMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final FloorMapper floorMapper;
    private final RoomMapper roomMapper;

    public Map<String, Object> searchCommunities(String name, String provinceName, String cityName,
                                                 String districtName, int page, int pageSize) {
        LambdaQueryWrapper<Community> q = new LambdaQueryWrapper<Community>()
                .isNull(Community::getDeletedAt)
                .orderByDesc(Community::getId);
        if (StringUtils.hasText(name)) {
            q.like(Community::getName, name.trim());
        }
        if (StringUtils.hasText(provinceName)) {
            String p = provinceName.trim();
            String c = StringUtils.hasText(cityName) ? cityName.trim() : "";
            String d = StringUtils.hasText(districtName) ? districtName.trim() : "";
            String composed = RegionCatalog.composeAddress(p, c, d, null);
            String raw = p + c + d;
            // 地址以「省市区」开头，或全文包含完整省市区串
            q.and(w -> {
                w.likeRight(Community::getAddress, composed)
                        .or()
                        .like(Community::getAddress, composed);
                if (!raw.equals(composed)) {
                    w.or().likeRight(Community::getAddress, raw)
                            .or().like(Community::getAddress, raw);
                }
            });
        }
        Page<Community> pr = communityMapper.selectPage(new Page<>(page, Math.min(pageSize, 50)), q);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Community c : pr.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("name", c.getName());
            row.put("address", c.getAddress());
            list.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", pr.getTotal());
        out.put("page", page);
        out.put("pageSize", pageSize);
        return out;
    }

    /** 全部小区，按城市分组。地址无法解析的归入「其他」。 */
    public Map<String, Object> catalogByCity() {
        List<Community> all = communityMapper.selectList(new LambdaQueryWrapper<Community>()
                .isNull(Community::getDeletedAt));
        Collator collator = Collator.getInstance(Locale.CHINA);
        Map<String, List<Map<String, Object>>> buckets = new TreeMap<>(collator);
        List<Map<String, Object>> other = new ArrayList<>();
        for (Community c : all) {
            RegionCatalog.ParsedAddress parsed = RegionCatalog.parseAddress(c.getAddress());
            String city = parsed != null && StringUtils.hasText(parsed.cityName()) ? parsed.cityName() : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("name", c.getName());
            row.put("districtName", parsed == null || parsed.districtName() == null ? "" : parsed.districtName());
            row.put("cityName", city == null ? "其他" : city);
            if (city == null) {
                other.add(row);
            } else {
                buckets.computeIfAbsent(city, k -> new ArrayList<>()).add(row);
            }
        }
        List<Map<String, Object>> cities = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : buckets.entrySet()) {
            e.getValue().sort((a, b) -> collator.compare(String.valueOf(a.get("name")), String.valueOf(b.get("name"))));
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("cityName", e.getKey());
            group.put("communities", e.getValue());
            cities.add(group);
        }
        if (!other.isEmpty()) {
            other.sort((a, b) -> collator.compare(String.valueOf(a.get("name")), String.valueOf(b.get("name"))));
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("cityName", "其他");
            group.put("communities", other);
            cities.add(group);
        }
        return Map.of("cities", cities);
    }

    public Map<String, Object> spaceTree(Long communityId) {
        Community c = communityMapper.selectById(communityId);
        if (c == null || c.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "小区不存在");
        }
        List<Building> buildings = buildingMapper.selectList(new LambdaQueryWrapper<Building>()
                .eq(Building::getCommunityId, communityId)
                .isNull(Building::getDeletedAt)
                .orderByAsc(Building::getId));
        List<Unit> units = unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                .eq(Unit::getCommunityId, communityId)
                .isNull(Unit::getDeletedAt));
        List<Floor> floors = floorMapper.selectList(new LambdaQueryWrapper<Floor>()
                .eq(Floor::getCommunityId, communityId)
                .isNull(Floor::getDeletedAt));
        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getCommunityId, communityId)
                .isNull(Room::getDeletedAt));

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
                    List<Map<String, Object>> roomNodes = roomsByFloor.getOrDefault(f.getId(), List.of()).stream()
                            .map(r -> {
                                Map<String, Object> rn = new LinkedHashMap<>();
                                rn.put("id", r.getId());
                                rn.put("roomNo", r.getRoomNo());
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
        return Map.of(
                "communityId", c.getId(),
                "communityName", c.getName(),
                "buildings", tree);
    }
}
