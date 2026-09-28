package com.property.mgmt.common;

import com.property.mgmt.domain.Building;
import com.property.mgmt.domain.Floor;
import com.property.mgmt.domain.Room;
import com.property.mgmt.domain.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * 房屋展示路径统一格式：{@code 楼栋/单元/层/房号}（缺段跳过，不以小区开头）。
 */
public final class RoomPaths {

    private RoomPaths() {}

    public static String format(String buildingName, String unitName, String floorName, String roomNo) {
        List<String> parts = new ArrayList<>(4);
        add(parts, buildingName);
        add(parts, unitName);
        add(parts, floorName);
        add(parts, roomNo);
        return String.join("/", parts);
    }

    public static String format(Building building, Unit unit, Floor floor, Room room) {
        String buildingName = building == null ? null : building.getName();
        String unitName = unit == null ? null : unit.getName();
        String floorName = floorLabel(floor);
        String roomNo = room == null ? null : room.getRoomNo();
        return format(buildingName, unitName, floorName, roomNo);
    }

    /** 层：优先层名称，否则层号 */
    public static String floorLabel(Floor floor) {
        if (floor == null) return null;
        if (floor.getName() != null && !floor.getName().isBlank()) {
            return floor.getName().trim();
        }
        return floor.getFloorNo() == null ? null : String.valueOf(floor.getFloorNo());
    }

    private static void add(List<String> parts, String v) {
        if (v != null && !v.isBlank()) {
            parts.add(v.trim());
        }
    }
}
