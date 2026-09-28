package com.property.mgmt.service;

import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 房屋结构：预设模板或自定义 栋 × 单元 × 层 × 户（自定义编码 C_b_u_f_r） */
public final class CommunitySpaceTemplates {

    public record Template(String code, String name, String description,
                           int buildings, int unitsPerBuilding, int floorsPerUnit, int roomsPerFloor) {}

    public static final int MAX_BUILDINGS = 30;
    public static final int MAX_UNITS = 20;
    public static final int MAX_FLOORS = 60;
    public static final int MAX_ROOMS_PER_FLOOR = 20;
    public static final int MAX_TOTAL_ROOMS = 8000;

    private static final Pattern CUSTOM = Pattern.compile("^C_(\\d+)_(\\d+)_(\\d+)_(\\d+)$");

    public static final Template T_1_3_10_4 = new Template(
            "T_1_3_10_4", "标准高层", "1 栋 × 3 单元 × 10 层 × 4 户", 1, 3, 10, 4);
    public static final Template T_1_2_6_2 = new Template(
            "T_1_2_6_2", "小高层", "1 栋 × 2 单元 × 6 层 × 2 户", 1, 2, 6, 2);
    public static final Template T_2_2_12_4 = new Template(
            "T_2_2_12_4", "双栋高层", "2 栋 × 2 单元 × 12 层 × 4 户", 2, 2, 12, 4);

    private static final List<Template> ALL = List.of(T_1_3_10_4, T_1_2_6_2, T_2_2_12_4);

    private CommunitySpaceTemplates() {}

    public static List<Map<String, Object>> list() {
        return ALL.stream().map(CommunitySpaceTemplates::toMap).toList();
    }

    public static Template ofCounts(int buildings, int unitsPerBuilding, int floorsPerUnit, int roomsPerFloor) {
        validateCounts(buildings, unitsPerBuilding, floorsPerUnit, roomsPerFloor);
        String code = "C_" + buildings + "_" + unitsPerBuilding + "_" + floorsPerUnit + "_" + roomsPerFloor;
        String desc = buildings + " 栋 × " + unitsPerBuilding + " 单元 × " + floorsPerUnit + " 层 × " + roomsPerFloor + " 户";
        return new Template(code, "自定义", desc, buildings, unitsPerBuilding, floorsPerUnit, roomsPerFloor);
    }

    public static Template require(String code) {
        if (code == null || code.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "未知房屋模板");
        }
        String trimmed = code.trim();
        Matcher m = CUSTOM.matcher(trimmed);
        if (m.matches()) {
            return ofCounts(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)));
        }
        return ALL.stream().filter(t -> t.code().equals(trimmed)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodes.BAD_PARAM, "未知房屋模板"));
    }

    public static String describe(String code) {
        if (code == null || code.isBlank()) {
            return "不初始化";
        }
        try {
            Template t = require(code);
            return t.name() + "（" + t.description() + "）";
        } catch (BizException e) {
            return code;
        }
    }

    public static void validateCounts(int buildings, int unitsPerBuilding, int floorsPerUnit, int roomsPerFloor) {
        if (buildings < 1 || buildings > MAX_BUILDINGS) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "楼栋数须为 1–" + MAX_BUILDINGS);
        }
        if (unitsPerBuilding < 1 || unitsPerBuilding > MAX_UNITS) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "每栋单元数须为 1–" + MAX_UNITS);
        }
        if (floorsPerUnit < 1 || floorsPerUnit > MAX_FLOORS) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "每单元楼层数须为 1–" + MAX_FLOORS);
        }
        if (roomsPerFloor < 1 || roomsPerFloor > MAX_ROOMS_PER_FLOOR) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "每层房间数须为 1–" + MAX_ROOMS_PER_FLOOR);
        }
        long total = (long) buildings * unitsPerBuilding * floorsPerUnit * roomsPerFloor;
        if (total > MAX_TOTAL_ROOMS) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "房屋总数不能超过 " + MAX_TOTAL_ROOMS + "（当前 " + total + "）");
        }
    }

    private static Map<String, Object> toMap(Template t) {
        return Map.of(
                "code", t.code(),
                "name", t.name(),
                "description", t.description(),
                "buildings", t.buildings(),
                "unitsPerBuilding", t.unitsPerBuilding(),
                "floorsPerUnit", t.floorsPerUnit(),
                "roomsPerFloor", t.roomsPerFloor()
        );
    }
}
