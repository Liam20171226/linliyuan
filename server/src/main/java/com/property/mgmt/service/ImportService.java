package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ImportService {

    private static final String[] HEADERS = {
            "楼栋", "单元", "楼层", "房号", "姓名", "手机号", "身份证号", "身份", "房屋类型", "面积"
    };

    private final ImportBatchMapper importBatchMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final FloorMapper floorMapper;
    private final RoomMapper roomMapper;
    private final SysUserMapper sysUserMapper;
    private final RoomOccupantBindService roomOccupantBindService;
    private final CommunityHouseTypeMapper houseTypeMapper;
    private final ObjectMapper objectMapper;

    public Resource template() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("住户导入");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
                sheet.setColumnWidth(i, 14 * 256);
            }
            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("1栋");
            sample.createCell(1).setCellValue("1单元");
            sample.createCell(2).setCellValue("1层");
            sample.createCell(3).setCellValue("101");
            sample.createCell(4).setCellValue("张三");
            sample.createCell(5).setCellValue("13800001111");
            sample.createCell(6).setCellValue("110101199001011234");
            sample.createCell(7).setCellValue("业主");
            sample.createCell(8).setCellValue("");
            sample.createCell(9).setCellValue("89.5");
            wb.write(out);
            return new ByteArrayResource(out.toByteArray()) {
                @Override
                public String getFilename() {
                    return "resident-import-template.xlsx";
                }
            };
        } catch (Exception e) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "模板生成失败");
        }
    }

    @Transactional
    public ImportBatch importExcel(MultipartFile file) {
        AuthUser staff = StaffGuard.requireStaff();
        Long cid = staff.getCommunityId();
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请上传 Excel 文件");
        }
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("import.xlsx");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx") && !name.toLowerCase(Locale.ROOT).endsWith(".xls")) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅支持 Excel 文件");
        }

        LocalDateTime now = LocalDateTime.now();
        ImportBatch batch = new ImportBatch();
        batch.setCommunityId(cid);
        batch.setFileName(name);
        batch.setStatus("PROCESSING");
        batch.setSuccessCount(0);
        batch.setFailCount(0);
        batch.setCreatedBy(staff.getUserId());
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        importBatchMapper.insert(batch);

        List<Map<String, Object>> fails = new ArrayList<>();
        int success = 0;
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlankRow(row, fmt)) {
                    continue;
                }
                int excelRow = r + 1;
                try {
                    importOneRow(cid, staff.getUserId(), row, fmt, now);
                    success++;
                } catch (BizException ex) {
                    fails.add(Map.of("row", excelRow, "message", ex.getMessage()));
                } catch (Exception ex) {
                    fails.add(Map.of("row", excelRow, "message", Optional.ofNullable(ex.getMessage()).orElse("导入失败")));
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "Excel 解析失败");
        }

        batch.setSuccessCount(success);
        batch.setFailCount(fails.size());
        batch.setStatus(fails.isEmpty() ? "DONE" : "DONE_WITH_ERRORS");
        try {
            batch.setFailDetailJson(objectMapper.writeValueAsString(fails));
        } catch (Exception e) {
            batch.setFailDetailJson("[]");
        }
        batch.setUpdatedAt(LocalDateTime.now());
        importBatchMapper.updateById(batch);
        return batch;
    }

    public List<ImportBatch> list() {
        Long cid = StaffGuard.communityId();
        return importBatchMapper.selectList(new LambdaQueryWrapper<ImportBatch>()
                .eq(ImportBatch::getCommunityId, cid)
                .orderByDesc(ImportBatch::getId));
    }

    public ImportBatch get(Long id) {
        ImportBatch b = importBatchMapper.selectById(id);
        if (b == null || !StaffGuard.communityId().equals(b.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "导入批次不存在");
        }
        return b;
    }

    private void importOneRow(Long cid, Long staffId, Row row, DataFormatter fmt, LocalDateTime now) {
        String buildingName = cell(row, 0, fmt);
        String unitName = cell(row, 1, fmt);
        String floorName = cell(row, 2, fmt);
        String roomNo = cell(row, 3, fmt);
        String realName = cell(row, 4, fmt);
        String mobile = cell(row, 5, fmt);
        String idCard = cell(row, 6, fmt);
        String roleRaw = cell(row, 7, fmt);
        String houseTypeName = cell(row, 8, fmt);
        String areaRaw = cell(row, 9, fmt);

        if (!StringUtils.hasText(buildingName) || !StringUtils.hasText(unitName)
                || !StringUtils.hasText(floorName) || !StringUtils.hasText(roomNo)
                || !StringUtils.hasText(mobile) || !StringUtils.hasText(roleRaw)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "楼栋/单元/楼层/房号/手机号/身份必填");
        }
        String role = mapRole(roleRaw);
        if ("OWNER".equals(role) && !StringUtils.hasText(idCard)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "业主须填身份证");
        }

        Building building = findOrCreateBuilding(cid, buildingName, now);
        Unit unit = findOrCreateUnit(cid, building.getId(), unitName, now);
        Floor floor = findOrCreateFloor(cid, building.getId(), unit.getId(), floorName, now);
        Room room = findOrCreateRoom(cid, building.getId(), unit.getId(), floor.getId(), roomNo, houseTypeName, areaRaw, now);

        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getMobile, mobile.trim()));
        if (user == null) {
            user = new SysUser();
            user.setMobile(mobile.trim());
            user.setRealName(realName);
            if (StringUtils.hasText(idCard)) {
                assertIdCardFree(idCard, null);
                user.setIdCardNo(idCard.trim());
            }
            user.setIsPlatformAdmin(0);
            user.setStatus(1);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            sysUserMapper.insert(user);
        } else {
            if ("OWNER".equals(role)) {
                assertIdCardFree(idCard, user.getId());
                user.setIdCardNo(idCard.trim());
            } else if (StringUtils.hasText(idCard) && !StringUtils.hasText(user.getIdCardNo())) {
                assertIdCardFree(idCard, user.getId());
                user.setIdCardNo(idCard.trim());
            }
            if (StringUtils.hasText(realName)) {
                user.setRealName(realName);
            }
            user.setUpdatedAt(now);
            sysUserMapper.updateById(user);
        }

        roomOccupantBindService.bindActive(room, user.getId(), role, "IMPORT", staffId);
    }

    private void assertIdCardFree(String idCard, Long excludeUserId) {
        LambdaQueryWrapper<SysUser> q = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getIdCardNo, idCard.trim());
        if (excludeUserId != null) {
            q.ne(SysUser::getId, excludeUserId);
        }
        if (sysUserMapper.selectCount(q) > 0) {
            throw BizException.of(ErrorCodes.ID_CARD_TAKEN, "身份证已被其他账号占用");
        }
    }

    private Building findOrCreateBuilding(Long cid, String name, LocalDateTime now) {
        Building b = buildingMapper.selectOne(new LambdaQueryWrapper<Building>()
                .eq(Building::getCommunityId, cid)
                .eq(Building::getName, name.trim())
                .isNull(Building::getDeletedAt));
        if (b != null) {
            return b;
        }
        b = new Building();
        b.setCommunityId(cid);
        b.setName(name.trim());
        b.setCreatedAt(now);
        b.setUpdatedAt(now);
        buildingMapper.insert(b);
        return b;
    }

    private Unit findOrCreateUnit(Long cid, Long buildingId, String name, LocalDateTime now) {
        Unit u = unitMapper.selectOne(new LambdaQueryWrapper<Unit>()
                .eq(Unit::getBuildingId, buildingId)
                .eq(Unit::getName, name.trim())
                .isNull(Unit::getDeletedAt));
        if (u != null) {
            return u;
        }
        u = new Unit();
        u.setCommunityId(cid);
        u.setBuildingId(buildingId);
        u.setName(name.trim());
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        unitMapper.insert(u);
        return u;
    }

    private Floor findOrCreateFloor(Long cid, Long buildingId, Long unitId, String name, LocalDateTime now) {
        Floor f = floorMapper.selectOne(new LambdaQueryWrapper<Floor>()
                .eq(Floor::getUnitId, unitId)
                .eq(Floor::getName, name.trim())
                .isNull(Floor::getDeletedAt));
        if (f != null) {
            return f;
        }
        Integer floorNo = null;
        String digits = name.trim().replaceAll("[^0-9-]", "");
        if (StringUtils.hasText(digits)) {
            try {
                floorNo = Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
            }
        }
        f = new Floor();
        f.setCommunityId(cid);
        f.setBuildingId(buildingId);
        f.setUnitId(unitId);
        f.setName(name.trim());
        f.setFloorNo(floorNo);
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        floorMapper.insert(f);
        return f;
    }

    private Room findOrCreateRoom(Long cid, Long buildingId, Long unitId, Long floorId,
                                  String roomNo, String houseTypeName, String areaRaw, LocalDateTime now) {
        Room room = roomMapper.selectOne(new LambdaQueryWrapper<Room>()
                .eq(Room::getFloorId, floorId)
                .eq(Room::getRoomNo, roomNo.trim())
                .isNull(Room::getDeletedAt));
        Long houseTypeId = null;
        if (StringUtils.hasText(houseTypeName)) {
            CommunityHouseType ht = houseTypeMapper.selectOne(new LambdaQueryWrapper<CommunityHouseType>()
                    .eq(CommunityHouseType::getCommunityId, cid)
                    .eq(CommunityHouseType::getName, houseTypeName.trim())
                    .isNull(CommunityHouseType::getDeletedAt));
            if (ht == null) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "房屋类型不存在: " + houseTypeName);
            }
            houseTypeId = ht.getId();
        }
        BigDecimal area = null;
        if (StringUtils.hasText(areaRaw)) {
            area = new BigDecimal(areaRaw.trim());
            if (area.compareTo(BigDecimal.ZERO) < 0) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "面积不能为负");
            }
        }
        if (room == null) {
            room = new Room();
            room.setCommunityId(cid);
            room.setBuildingId(buildingId);
            room.setUnitId(unitId);
            room.setFloorId(floorId);
            room.setRoomNo(roomNo.trim());
            room.setHouseTypeId(houseTypeId);
            room.setAreaSqm(area);
            room.setStatus(1);
            room.setCreatedAt(now);
            room.setUpdatedAt(now);
            roomMapper.insert(room);
            return room;
        }
        boolean changed = false;
        if (houseTypeId != null && room.getHouseTypeId() == null) {
            room.setHouseTypeId(houseTypeId);
            changed = true;
        }
        if (area != null && room.getAreaSqm() == null) {
            room.setAreaSqm(area);
            changed = true;
        }
        if (changed) {
            room.setUpdatedAt(now);
            roomMapper.updateById(room);
        }
        return room;
    }

    private static String mapRole(String raw) {
        String v = raw.trim();
        return switch (v) {
            case "业主", "OWNER" -> "OWNER";
            case "业主成员", "OWNER_MEMBER" -> "OWNER_MEMBER";
            case "租户", "TENANT" -> "TENANT";
            case "租户成员", "TENANT_MEMBER" -> "TENANT_MEMBER";
            default -> throw BizException.of(ErrorCodes.BAD_PARAM, "身份无法识别: " + v);
        };
    }

    private static String cell(Row row, int idx, DataFormatter fmt) {
        Cell c = row.getCell(idx);
        if (c == null) {
            return "";
        }
        return fmt.formatCellValue(c).trim();
    }

    private static boolean isBlankRow(Row row, DataFormatter fmt) {
        for (int i = 0; i < HEADERS.length; i++) {
            if (StringUtils.hasText(cell(row, i, fmt))) {
                return false;
            }
        }
        return true;
    }
}
