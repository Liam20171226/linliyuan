package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.common.RoomPaths;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.integration.WechatPayApi;
import com.property.mgmt.config.WxProperties;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BillingService {

    private final FeeItemService feeItemService;
    private final BillMapper billMapper;
    private final BillLineMapper billLineMapper;
    private final BillMeterUploadMapper meterUploadMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final PaymentConfigMapper paymentConfigMapper;
    private final RoomMapper roomMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final FloorMapper floorMapper;
    private final RoomOccupantMapper roomOccupantMapper;
    private final CommunityHouseTypeMapper houseTypeMapper;
    private final RoomVehicleMapper roomVehicleMapper;
    private final ParkingSpaceMapper parkingSpaceMapper;
    private final TodoNotifyService todoNotifyService;
    private final WechatPayApi wechatPayApi;
    private final UserWechatMapper userWechatMapper;
    private final WxProperties wxProperties;
    private final RoomFeeExemptionService roomFeeExemptionService;
    private final PrepaidService prepaidService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---------- meters ----------

    @Transactional
    public List<BillMeterUpload> uploadMeters(List<Map<String, Object>> items) {
        Long cid = StaffGuard.communityId();
        if (items == null || items.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "上传数据不能为空");
        }
        List<BillMeterUpload> result = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> it : items) {
            Long roomId = toLong(it.get("roomId"));
            String billMonth = str(it.get("billMonth"));
            String feeCategory = str(it.get("feeCategory"));
            if (roomId == null || billMonth == null || feeCategory == null) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "roomId/billMonth/feeCategory 必填");
            }
            Room room = roomMapper.selectById(roomId);
            if (room == null || room.getDeletedAt() != null || !cid.equals(room.getCommunityId())) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在: " + roomId);
            }
            Set<String> meterCats = Set.of("SHARED", "WATER", "ELECTRIC", "GAS");
            if (!meterCats.contains(feeCategory)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "feeCategory 须为 SHARED/WATER/ELECTRIC/GAS");
            }
            BillMeterUpload u = new BillMeterUpload();
            u.setCommunityId(cid);
            u.setRoomId(roomId);
            u.setBillMonth(billMonth);
            u.setFeeCategory(feeCategory);
            if ("SHARED".equals(feeCategory)) {
                BigDecimal amount = toDecimal(it.get("amount"));
                if (amount == null) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "公摊须填写 amount");
                }
                u.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
            } else {
                BigDecimal start = toDecimal(it.get("meterStart"));
                BigDecimal end = toDecimal(it.get("meterEnd"));
                BigDecimal unit = toDecimal(it.get("unitPrice"));
                if (start == null || end == null || unit == null) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "水电煤气须填写起止读数与单价");
                }
                if (end.compareTo(start) < 0) {
                    throw BizException.of(ErrorCodes.BAD_PARAM, "止数不能小于起数");
                }
                u.setMeterStart(start);
                u.setMeterEnd(end);
                u.setUnitPrice(unit);
                u.setAmount(end.subtract(start).multiply(unit).setScale(2, RoundingMode.HALF_UP));
            }
            u.setCreatedAt(now);
            u.setUpdatedAt(now);
            // replace same room/month/category
            meterUploadMapper.delete(new LambdaQueryWrapper<BillMeterUpload>()
                    .eq(BillMeterUpload::getCommunityId, cid)
                    .eq(BillMeterUpload::getRoomId, roomId)
                    .eq(BillMeterUpload::getBillMonth, billMonth)
                    .eq(BillMeterUpload::getFeeCategory, feeCategory));
            meterUploadMapper.insert(u);
            result.add(u);
        }
        return result;
    }

    public List<BillMeterUpload> listMeters(String billMonth, Long roomId) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<BillMeterUpload> q = new LambdaQueryWrapper<BillMeterUpload>()
                .eq(BillMeterUpload::getCommunityId, cid)
                .orderByDesc(BillMeterUpload::getId);
        if (billMonth != null && !billMonth.isBlank()) {
            q.eq(BillMeterUpload::getBillMonth, billMonth);
        }
        if (roomId != null) {
            q.eq(BillMeterUpload::getRoomId, roomId);
        }
        return meterUploadMapper.selectList(q);
    }

    /**
     * 下载本小区全部房屋的表计导入模板（含填写说明）。
     * 列：A房屋ID B楼栋 C单元 D楼层 E房号 F账期 G费用类型 H金额 I起数 J止数 K单价
     */
    public Resource meterTemplate(String billMonth) {
        StaffGuard.requireStaff();
        Long cid = StaffGuard.communityId();
        String month = (billMonth == null || billMonth.isBlank())
                ? LocalDate.now().toString().substring(0, 7)
                : billMonth.trim();

        List<FeeItem> importItems = feeItemService.enabledImportConfigured(cid);
        List<String> cats = importItems.isEmpty()
                ? List.of("SHARED", "WATER", "ELECTRIC", "GAS")
                : importItems.stream().map(FeeItem::getFeeCategory).distinct().toList();

        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getCommunityId, cid)
                .isNull(Room::getDeletedAt)
                .orderByAsc(Room::getBuildingId)
                .orderByAsc(Room::getUnitId)
                .orderByAsc(Room::getFloorId)
                .orderByAsc(Room::getRoomNo));

        Map<Long, String> buildingName = buildingMapper.selectList(new LambdaQueryWrapper<Building>()
                        .eq(Building::getCommunityId, cid).isNull(Building::getDeletedAt))
                .stream().collect(Collectors.toMap(Building::getId, Building::getName, (a, b) -> a));
        Map<Long, String> unitName = unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                        .eq(Unit::getCommunityId, cid).isNull(Unit::getDeletedAt))
                .stream().collect(Collectors.toMap(Unit::getId, Unit::getName, (a, b) -> a));
        Map<Long, String> floorName = floorMapper.selectList(new LambdaQueryWrapper<Floor>()
                        .eq(Floor::getCommunityId, cid).isNull(Floor::getDeletedAt))
                .stream().collect(Collectors.toMap(Floor::getId, f -> {
                    if (f.getName() != null && !f.getName().isBlank()) {
                        return f.getName();
                    }
                    return f.getFloorNo() == null ? "" : String.valueOf(f.getFloorNo());
                }, (a, b) -> a));

        String[] headers = {"房屋ID", "楼栋", "单元", "楼层", "房号", "账期", "费用类型", "金额", "起数", "止数", "单价"};
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(bold);

            Sheet guide = wb.createSheet("填写说明");
            guide.setColumnWidth(0, 22 * 256);
            guide.setColumnWidth(1, 72 * 256);
            String[][] guideRows = {
                    {"工作表", "请填写「表计上传」工作表；本页仅说明，导入时忽略。"},
                    {"数据起始", "「表计上传」第 1 行为表头，从第 2 行起为房屋数据（已预填本小区全部房屋）。"},
                    {"A列 房屋ID", "系统房屋主键，请勿改动；导入以本列匹配房屋。"},
                    {"B～E列", "楼栋/单元/楼层/房号，仅方便核对，导入不依赖修改。"},
                    {"F列 账期", "格式 YYYY-MM，已预填当前选择账期，可按需修改。"},
                    {"G列 费用类型", "SHARED=公摊 / WATER=水费 / ELECTRIC=电费 / GAS=煤气费。已按启用中的导入费项预填。"},
                    {"H列 金额", "公摊（SHARED）请在本列填写金额（元）；水电气可不填本列。"},
                    {"I列 起数", "水/电/煤气填写起始读数（第 I 列）。"},
                    {"J列 止数", "水/电/煤气填写截止读数（第 J 列），须 ≥ 起数。"},
                    {"K列 单价", "水/电/煤气填写单价（元）（第 K 列）；金额=(止数−起数)×单价。"},
                    {"空行跳过", "某行若 H～K 均未填，导入时自动跳过该行。"},
                    {"房屋数", String.valueOf(rooms.size()) + "；费用类型数：" + cats.size()
                            + "；预填行数约 " + (rooms.size() * cats.size())},
            };
            for (int i = 0; i < guideRows.length; i++) {
                Row r = guide.createRow(i);
                Cell c0 = r.createCell(0);
                c0.setCellValue(guideRows[i][0]);
                c0.setCellStyle(headerStyle);
                Cell c1 = r.createCell(1);
                c1.setCellValue(guideRows[i][1]);
                c1.setCellStyle(wrap);
                r.setHeightInPoints(28);
            }

            Sheet sheet = wb.createSheet("表计上传");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, (i == 0 ? 12 : 12) * 256);
            }
            sheet.setColumnWidth(1, 14 * 256);
            sheet.setColumnWidth(5, 12 * 256);
            sheet.setColumnWidth(6, 14 * 256);

            int rowIdx = 1;
            for (Room room : rooms) {
                for (String cat : cats) {
                    Row row = sheet.createRow(rowIdx++);
                    row.createCell(0).setCellValue(room.getId());
                    row.createCell(1).setCellValue(buildingName.getOrDefault(room.getBuildingId(), ""));
                    row.createCell(2).setCellValue(unitName.getOrDefault(room.getUnitId(), ""));
                    row.createCell(3).setCellValue(floorName.getOrDefault(room.getFloorId(), ""));
                    row.createCell(4).setCellValue(room.getRoomNo() == null ? "" : room.getRoomNo());
                    row.createCell(5).setCellValue(month);
                    row.createCell(6).setCellValue(cat);
                    // H～K 留给用户填写
                }
            }
            if (rooms.isEmpty()) {
                Row tip = sheet.createRow(1);
                tip.createCell(0).setCellValue("（本小区暂无房屋，请先在「空间」维护楼栋房屋后再下载模板）");
            }

            wb.setSheetOrder("填写说明", 0);
            wb.setSheetOrder("表计上传", 1);
            wb.setActiveSheet(1);
            wb.write(out);
            return new ByteArrayResource(out.toByteArray()) {
                @Override
                public String getFilename() {
                    return "bill-meter-template.xlsx";
                }
            };
        } catch (Exception e) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "模板生成失败");
        }
    }

    /**
     * Excel「表计上传」列：A房屋ID B楼栋 C单元 D楼层 E房号 F账期 G费用类型 H金额 I起数 J止数 K单价。
     * 兼容旧模板（房号、账期、费用类型、金额、起数、止数、单价 共 7 列）。
     */
    @Transactional
    public Map<String, Object> importMetersExcel(MultipartFile file) {
        StaffGuard.requireStaff();
        Long cid = StaffGuard.communityId();
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请上传 Excel 文件");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> fails = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheet("表计上传");
            if (sheet == null) {
                sheet = wb.getSheetAt(0);
            }
            boolean wide = isWideMeterHeader(sheet.getRow(0), fmt);
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                int excelRow = r + 1;
                try {
                    String roomIdStr;
                    String roomNo;
                    String billMonth;
                    String feeCategory;
                    String amount;
                    String start;
                    String end;
                    String unit;
                    if (wide) {
                        roomIdStr = cell(row, 0, fmt);
                        roomNo = cell(row, 4, fmt);
                        billMonth = cell(row, 5, fmt);
                        feeCategory = cell(row, 6, fmt).toUpperCase(Locale.ROOT);
                        amount = cell(row, 7, fmt);
                        start = cell(row, 8, fmt);
                        end = cell(row, 9, fmt);
                        unit = cell(row, 10, fmt);
                    } else {
                        roomIdStr = "";
                        roomNo = cell(row, 0, fmt);
                        billMonth = cell(row, 1, fmt);
                        feeCategory = cell(row, 2, fmt).toUpperCase(Locale.ROOT);
                        amount = cell(row, 3, fmt);
                        start = cell(row, 4, fmt);
                        end = cell(row, 5, fmt);
                        unit = cell(row, 6, fmt);
                    }
                    if (roomIdStr.isEmpty() && roomNo.isEmpty() && billMonth.isEmpty() && feeCategory.isEmpty()) {
                        continue;
                    }
                    if (amount.isEmpty() && start.isEmpty() && end.isEmpty() && unit.isEmpty()) {
                        continue; // 模板预填空行
                    }
                    if (billMonth.isEmpty() || feeCategory.isEmpty()) {
                        throw BizException.of(ErrorCodes.BAD_PARAM, "账期/费用类型必填");
                    }
                    Room room = null;
                    if (!roomIdStr.isEmpty()) {
                        Long rid = toLong(roomIdStr);
                        if (rid != null) {
                            room = roomMapper.selectById(rid);
                            if (room == null || room.getDeletedAt() != null || !cid.equals(room.getCommunityId())) {
                                throw BizException.of(ErrorCodes.NOT_FOUND, "房屋ID不存在: " + roomIdStr);
                            }
                        }
                    }
                    if (room == null) {
                        if (roomNo.isEmpty()) {
                            throw BizException.of(ErrorCodes.BAD_PARAM, "房屋ID或房号必填");
                        }
                        room = roomMapper.selectOne(new LambdaQueryWrapper<Room>()
                                .eq(Room::getCommunityId, cid)
                                .eq(Room::getRoomNo, roomNo)
                                .isNull(Room::getDeletedAt)
                                .last("LIMIT 1"));
                        if (room == null) {
                            throw BizException.of(ErrorCodes.NOT_FOUND, "房号不存在: " + roomNo);
                        }
                    }
                    Map<String, Object> it = new LinkedHashMap<>();
                    it.put("roomId", room.getId());
                    it.put("billMonth", billMonth);
                    it.put("feeCategory", feeCategory);
                    if (!amount.isEmpty()) {
                        it.put("amount", amount);
                    }
                    if (!start.isEmpty()) {
                        it.put("meterStart", start);
                    }
                    if (!end.isEmpty()) {
                        it.put("meterEnd", end);
                    }
                    if (!unit.isEmpty()) {
                        it.put("unitPrice", unit);
                    }
                    items.add(it);
                } catch (BizException ex) {
                    fails.add(Map.of("row", excelRow, "message", ex.getMessage()));
                } catch (Exception ex) {
                    fails.add(Map.of("row", excelRow, "message", Optional.ofNullable(ex.getMessage()).orElse("解析失败")));
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "Excel 解析失败");
        }

        List<BillMeterUpload> uploaded = List.of();
        if (!items.isEmpty()) {
            uploaded = uploadMeters(items);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("successCount", uploaded.size());
        data.put("failCount", fails.size());
        data.put("fails", fails);
        data.put("list", uploaded);
        return data;
    }

    private static boolean isWideMeterHeader(Row header, DataFormatter fmt) {
        if (header == null) {
            return false;
        }
        String a = cell(header, 0, fmt);
        return a.contains("房屋ID") || a.equalsIgnoreCase("roomId") || a.contains("房屋id");
    }

    private static String cell(Row row, int idx, DataFormatter fmt) {
        Cell c = row.getCell(idx);
        if (c == null) {
            return "";
        }
        return fmt.formatCellValue(c).trim();
    }

    // ---------- generate / publish ----------

    @Transactional
    public Map<String, Object> generate(String billMonth, List<Long> roomIds) {
        Long cid = StaffGuard.communityId();
        if (billMonth == null || !billMonth.matches("\\d{4}-\\d{2}")) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "billMonth 须为 YYYY-MM");
        }
        feeItemService.seedDefaultsIfEmpty(cid);
        assertImportUploadsReady(cid, billMonth);
        List<Long> targets = resolveActiveRooms(cid, roomIds);
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Bill> ok = new ArrayList<>();
        int skippedPrepaid = 0;
        for (Long roomId : targets) {
            try {
                Bill bill = generateOne(cid, roomId, billMonth, false);
                if (bill == null) {
                    skippedPrepaid++;
                } else {
                    ok.add(bill);
                }
            } catch (BizException e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("roomId", roomId);
                err.put("code", e.getCode());
                err.put("message", e.getMessage());
                errors.add(err);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("successCount", ok.size());
        data.put("skippedPrepaidCount", skippedPrepaid);
        data.put("failCount", errors.size());
        data.put("bills", ok);
        data.put("errors", errors);
        return data;
    }

    /**
     * 启用中的公摊/水电煤气费项须先完成本月 Excel 导入，才允许生成账单。
     */
    private void assertImportUploadsReady(Long communityId, String billMonth) {
        List<FeeItem> needImport = feeItemService.enabledImportConfigured(communityId);
        if (needImport.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (FeeItem fi : needImport) {
            long cnt = meterUploadMapper.selectCount(new LambdaQueryWrapper<BillMeterUpload>()
                    .eq(BillMeterUpload::getCommunityId, communityId)
                    .eq(BillMeterUpload::getBillMonth, billMonth)
                    .eq(BillMeterUpload::getFeeCategory, fi.getFeeCategory()));
            if (cnt == 0) {
                missing.add(fi.getName() + "(" + fi.getFeeCategory() + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM,
                    "存在需导入配置的启用费项，请先下载模板并上传本月数据后再生成：" + String.join("、", missing));
        }
    }

    @Transactional
    public Map<String, Object> publishBatch(String billMonth, List<Long> roomIds) {
        Long cid = StaffGuard.communityId();
        if (billMonth == null || !billMonth.matches("\\d{4}-\\d{2}")) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "billMonth 须为 YYYY-MM");
        }
        LambdaQueryWrapper<Bill> q = new LambdaQueryWrapper<Bill>()
                .eq(Bill::getCommunityId, cid)
                .eq(Bill::getBillMonth, billMonth)
                .eq(Bill::getStatus, "DRAFT");
        if (roomIds != null && !roomIds.isEmpty()) {
            q.in(Bill::getRoomId, roomIds);
        }
        List<Bill> drafts = billMapper.selectList(q);
        List<Bill> published = new ArrayList<>();
        for (Bill b : drafts) {
            published.add(doPublish(b));
        }
        return Map.of("count", published.size(), "bills", published);
    }

    @Transactional
    public Bill publishOne(Long billId) {
        Bill b = requireStaffBill(billId);
        if ("PAID".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BILL_PAID_NO_REPUBLISH, "账单已确认收款，不可覆盖");
        }
        if (!"DRAFT".equals(b.getStatus()) && !"PUBLISHED".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅草稿或已发放账单可发放");
        }
        return doPublish(b);
    }

    @Transactional
    public Bill republishRoom(Long roomId, String billMonth) {
        Long cid = StaffGuard.communityId();
        if (billMonth == null || !billMonth.matches("\\d{4}-\\d{2}")) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "billMonth 须为 YYYY-MM");
        }
        Bill existing = billMapper.selectOne(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getCommunityId, cid)
                .eq(Bill::getRoomId, roomId)
                .eq(Bill::getBillMonth, billMonth));
        if (existing != null && "PAID".equals(existing.getStatus())) {
            throw BizException.of(ErrorCodes.BILL_PAID_NO_REPUBLISH, "账单已确认收款，不可覆盖");
        }
        assertImportUploadsReady(cid, billMonth);
        Bill draft = generateOne(cid, roomId, billMonth, true);
        if (draft == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "本月费项均已被预缴/豁免覆盖，无需出账发放");
        }
        return doPublish(draft);
    }

    private Bill doPublish(Bill b) {
        if ("PAID".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BILL_PAID_NO_REPUBLISH, "账单已确认收款，不可覆盖");
        }
        LocalDateTime now = LocalDateTime.now();
        b.setStatus("PUBLISHED");
        b.setDueDate(LocalDate.now().plusDays(10));
        b.setPublishedAt(now);
        b.setUpdatedAt(now);
        billMapper.updateById(b);
        notifyBillPublished(b);
        return b;
    }

    private void notifyBillPublished(Bill b) {
        String title = billDueTodoTitle(b);
        String content = "金额 " + b.getTotalAmount();
        List<RoomOccupant> occupants = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getRoomId, b.getRoomId())
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        for (RoomOccupant o : occupants) {
            todoNotifyService.createTodo(b.getCommunityId(), o.getUserId(), "BILL_DUE",
                    title, content, "BILL", b.getId());
            todoNotifyService.skipSubscribe(b.getCommunityId(), o.getUserId(), "BILL_DUE",
                    "BILL", b.getId(), "一阶段未接微信下发");
        }
    }

    /** 待办标题：楼栋/单元/房号—yy年M月账单（不含楼层） */
    private String billDueTodoTitle(Bill b) {
        String addr = "房屋";
        Room room = b.getRoomId() == null ? null : roomMapper.selectById(b.getRoomId());
        if (room != null && room.getDeletedAt() == null) {
            Building building = room.getBuildingId() == null ? null : buildingMapper.selectById(room.getBuildingId());
            Unit unit = room.getUnitId() == null ? null : unitMapper.selectById(room.getUnitId());
            String buildingName = building == null ? null : building.getName();
            String unitName = unitLabel(unit == null ? null : unit.getName());
            String roomNo = room.getRoomNo();
            String path = RoomPaths.format(buildingName, unitName, null, roomNo);
            if (path != null && !path.isBlank()) {
                addr = path;
            }
        }
        return addr + "—" + billMonthLabel(b.getBillMonth()) + "账单";
    }

    /** 单元展示：纯数字补「单元」后缀 */
    private static String unitLabel(String unitName) {
        if (unitName == null || unitName.isBlank()) {
            return null;
        }
        String u = unitName.trim();
        if (u.contains("单元")) {
            return u;
        }
        return u + "单元";
    }

    private static String billMonthLabel(String billMonth) {
        if (billMonth == null || !billMonth.matches("\\d{4}-\\d{2}")) {
            return billMonth == null ? "" : billMonth;
        }
        int year = Integer.parseInt(billMonth.substring(0, 4));
        int month = Integer.parseInt(billMonth.substring(5, 7));
        return (year % 100) + "年" + month + "月";
    }

    private Bill generateOne(Long communityId, Long roomId, String billMonth, boolean allowOverwritePublished) {
        Room room = roomMapper.selectById(roomId);
        if (room == null || room.getDeletedAt() != null || !communityId.equals(room.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在");
        }
        Bill existing = billMapper.selectOne(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getCommunityId, communityId)
                .eq(Bill::getRoomId, roomId)
                .eq(Bill::getBillMonth, billMonth));
        if (existing != null) {
            if ("PAID".equals(existing.getStatus())) {
                throw BizException.of(ErrorCodes.BILL_PAID_NO_REPUBLISH,
                        "账单已确认收款，不可覆盖；如需纠错请先冲红");
            }
            // VOID / DRAFT 可直接覆盖；PUBLISHED 仅单房重出允许
            if ("PUBLISHED".equals(existing.getStatus()) && !allowOverwritePublished) {
                throw BizException.of(ErrorCodes.BILL_LOCKED,
                        "该房本月账单已发放。如需改金额，请对该房使用「单房重出」，勿用批量生成覆盖");
            }
            if ("PUBLISHED".equals(existing.getStatus())
                    && existing.getWechatTransactionId() != null
                    && !existing.getWechatTransactionId().isBlank()) {
                LocalDateTime touch = existing.getUpdatedAt() != null ? existing.getUpdatedAt() : existing.getPublishedAt();
                // 预下单超过 2 小时视为放弃，允许单房重出；否则禁止覆盖以免与支付并发
                if (touch != null && touch.isAfter(LocalDateTime.now().minusMinutes(30))) {
                    throw BizException.of(ErrorCodes.BILL_PAYING,
                            "该房账单支付处理中（近 30 分钟内已拉起支付），暂不可重出。请待支付完成后再试");
                }
            }
            billLineMapper.delete(new LambdaQueryWrapper<BillLine>().eq(BillLine::getBillId, existing.getId()));
        }

        List<FeeItem> items = feeItemService.enabledByCommunity(communityId);
        List<FeeExemptionLedger> pendingExempt = new ArrayList<>();
        List<BillLine> lines = buildLinesForRoom(communityId, room, billMonth, items, pendingExempt, true);

        // 预缴/豁免后无剩余费项：不出账、不发放（方案 A）
        if (lines.isEmpty()) {
            if (existing != null && !"PAID".equals(existing.getStatus())) {
                billMapper.deleteById(existing.getId());
            }
            return null;
        }

        BigDecimal total = lines.stream().map(BillLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDateTime now = LocalDateTime.now();
        Bill bill;
        if (existing != null) {
            bill = existing;
            bill.setStatus("DRAFT");
            bill.setTotalAmount(total);
            bill.setDueDate(null);
            bill.setPayChannel(null);
            bill.setPaidAt(null);
            bill.setConfirmedBy(null);
            bill.setWechatTransactionId(null);
            bill.setPublishedAt(null);
            bill.setUpdatedAt(now);
            billMapper.updateById(bill);
        } else {
            bill = new Bill();
            bill.setCommunityId(communityId);
            bill.setRoomId(roomId);
            bill.setBillMonth(billMonth);
            bill.setStatus("DRAFT");
            bill.setTotalAmount(total);
            bill.setCreatedAt(now);
            bill.setUpdatedAt(now);
            billMapper.insert(bill);
        }
        for (BillLine line : lines) {
            line.setBillId(bill.getId());
            line.setCreatedAt(now);
            billLineMapper.insert(line);
        }
        for (FeeExemptionLedger led : pendingExempt) {
            RoomFeeExemption ex = new RoomFeeExemption();
            ex.setId(led.getExemptionId());
            roomFeeExemptionService.writeLedger(
                    communityId, roomId, billMonth, bill.getId(), ex,
                    led.getFeeCategory(), led.getFeeItemId(), led.getTitle(), led.getAmount());
        }
        return bill;
    }

    /**
     * 按当前启用费项干跑一房明细（不写库）。校验失败抛 BizException（与生成一致）。
     * @param pendingExempt 非 null 时记录被豁免跳过的明细（供出账写入减免台账）
     */
    private List<BillLine> buildLinesForRoom(Long communityId, Room room, String billMonth, List<FeeItem> items,
                                             List<FeeExemptionLedger> pendingExempt, boolean applyPrepaidSkip) {
        Long roomId = room.getId();
        List<BillLine> lines = new ArrayList<>();
        Map<String, FeeItem> byCat = new LinkedHashMap<>();
        for (FeeItem fi : items) {
            byCat.putIfAbsent(fi.getFeeCategory(), fi);
        }

        FeeItem propertyItem = byCat.get("PROPERTY_FEE");
        if (propertyItem != null) {
            String pMode = FeeItemService.billingModeOf(propertyItem);
            if ("IMPORT".equals(pMode)) {
                // 由上传表并入
            } else if ("FIXED".equals(pMode)) {
                BigDecimal amt = propertyItem.getMonthlyAmount() == null ? null
                        : propertyItem.getMonthlyAmount().setScale(2, RoundingMode.HALF_UP);
                if (amt != null && !skipIfExemptOrPrepaid(communityId, roomId, billMonth, "PROPERTY_FEE",
                        propertyItem.getId(), propertyItem.getName(), amt, pendingExempt, applyPrepaidSkip)) {
                    lines.add(line("PROPERTY_FEE", propertyItem.getName(), amt,
                            Map.of("feeItemId", propertyItem.getId(), "billingMode", "FIXED",
                                    "monthlyAmount", propertyItem.getMonthlyAmount())));
                }
            } else if (skipIfExemptOrPrepaid(communityId, roomId, billMonth, "PROPERTY_FEE", propertyItem.getId(),
                    propertyItem.getName(), null, pendingExempt, applyPrepaidSkip)) {
                // 豁免/预缴则跳过面积/类型校验
            } else {
            Map<String, Object> cfg = parsePropertyFormula(propertyItem.getRemark());
            BigDecimal discount = (BigDecimal) cfg.get("discountRate");
            boolean useArea = Boolean.TRUE.equals(cfg.get("useArea"));
            boolean useHouseTypePrice = Boolean.TRUE.equals(cfg.get("useHouseTypePrice"));
            if (!useArea && !useHouseTypePrice) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "物业费公式须至少勾选面积或房屋类型单价");
            }
            BigDecimal propertyFee = BigDecimal.ONE;
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("feeItemId", propertyItem.getId());
            snap.put("useArea", useArea);
            snap.put("useHouseTypePrice", useHouseTypePrice);
            snap.put("discountRate", discount);
            snap.put("billingMode", "FORMULA");
            if (useArea) {
        if (room.getAreaSqm() == null || room.getAreaSqm().compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.of(ErrorCodes.BILL_LOCKED, "面积为空或非法，整单不可提交");
        }
                propertyFee = propertyFee.multiply(room.getAreaSqm());
                snap.put("areaSqm", room.getAreaSqm());
            }
            if (useHouseTypePrice) {
        if (room.getHouseTypeId() == null) {
            throw BizException.of(ErrorCodes.BILL_LOCKED, "房屋类型缺失，整单不可提交");
        }
        CommunityHouseType ht = houseTypeMapper.selectById(room.getHouseTypeId());
        if (ht == null || ht.getDeletedAt() != null || ht.getPropertyFeeUnitPrice() == null) {
            throw BizException.of(ErrorCodes.BILL_LOCKED, "房屋类型或物业费单价缺失，整单不可提交");
        }
                propertyFee = propertyFee.multiply(ht.getPropertyFeeUnitPrice());
                snap.put("houseTypeId", ht.getId());
                snap.put("unitPrice", ht.getPropertyFeeUnitPrice());
            }
            propertyFee = propertyFee.multiply(discount).setScale(2, RoundingMode.HALF_UP);
            lines.add(line("PROPERTY_FEE", propertyItem.getName(), propertyFee, snap));
            }
        }

        long spaceCount = parkingSpaceMapper.selectCount(new LambdaQueryWrapper<ParkingSpace>()
                .eq(ParkingSpace::getRoomId, roomId)
                .isNull(ParkingSpace::getDeletedAt));
        List<RoomVehicle> vehicles = roomVehicleMapper.selectList(new LambdaQueryWrapper<RoomVehicle>()
                .eq(RoomVehicle::getRoomId, roomId)
                .orderByAsc(RoomVehicle::getId));
        int n = (int) spaceCount;
        int x = vehicles.size();
        int mgmtQty = Math.min(x, n);
        int monthlyQty = Math.max(x - n, 0);

        FeeItem parkingMgmt = byCat.get("PARKING_MGMT");
        if (parkingMgmt != null) {
            String mgmtMode = FeeItemService.billingModeOf(parkingMgmt);
            if ("IMPORT".equals(mgmtMode)) {
                // 上传表并入
            } else if ("FIXED".equals(mgmtMode)) {
                BigDecimal amt = parkingMgmt.getMonthlyAmount() == null ? null
                        : parkingMgmt.getMonthlyAmount().setScale(2, RoundingMode.HALF_UP);
                if (amt != null && !skipIfExemptOrPrepaid(communityId, roomId, billMonth, "PARKING_MGMT",
                        parkingMgmt.getId(), parkingMgmt.getName(), amt, pendingExempt, applyPrepaidSkip)) {
                    lines.add(line("PARKING_MGMT", parkingMgmt.getName(), amt,
                            Map.of("feeItemId", parkingMgmt.getId(), "billingMode", "FIXED",
                                    "monthlyAmount", parkingMgmt.getMonthlyAmount())));
                }
            } else if (mgmtQty > 0) {
            Map<String, BigDecimal> prices = priceMap(parkingMgmt.getId());
            BigDecimal unit = prices.get("DEFAULT");
            if (unit == null) {
                unit = prices.get("OWNED");
            }
                if (unit == null) {
                throw BizException.of(ErrorCodes.BILL_GEN_FAIL, "车位管理费缺少单价");
            }
            BigDecimal amount = unit.multiply(BigDecimal.valueOf(mgmtQty)).setScale(2, RoundingMode.HALF_UP);
            String title = parkingMgmt.getName() + "×" + mgmtQty;
            if (!skipIfExemptOrPrepaid(communityId, roomId, billMonth, "PARKING_MGMT", parkingMgmt.getId(),
                    title, amount, pendingExempt, applyPrepaidSkip)) {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("N", n);
            snap.put("X", x);
            snap.put("qty", mgmtQty);
            snap.put("unitPrice", unit);
            snap.put("feeItemId", parkingMgmt.getId());
            snap.put("billingMode", "FORMULA");
            lines.add(line("PARKING_MGMT", title, amount, snap));
            }
            }
        }

        FeeItem parkingMonthly = byCat.get("PARKING_MONTHLY");
        if (parkingMonthly != null) {
            String monMode = FeeItemService.billingModeOf(parkingMonthly);
            if ("IMPORT".equals(monMode)) {
                // 上传表并入
            } else if ("FIXED".equals(monMode)) {
                BigDecimal amt = parkingMonthly.getMonthlyAmount() == null ? null
                        : parkingMonthly.getMonthlyAmount().setScale(2, RoundingMode.HALF_UP);
                if (amt != null && !skipIfExemptOrPrepaid(communityId, roomId, billMonth, "PARKING_MONTHLY",
                        parkingMonthly.getId(), parkingMonthly.getName(), amt, pendingExempt, applyPrepaidSkip)) {
                    lines.add(line("PARKING_MONTHLY", parkingMonthly.getName(), amt,
                            Map.of("feeItemId", parkingMonthly.getId(), "billingMode", "FIXED",
                                    "monthlyAmount", parkingMonthly.getMonthlyAmount())));
                }
            } else if (monthlyQty > 0) {
            if (skipIfExemptOrPrepaid(communityId, roomId, billMonth, "PARKING_MONTHLY", parkingMonthly.getId(),
                    parkingMonthly.getName(), null, pendingExempt, applyPrepaidSkip)) {
                // skip all monthly lines
            } else {
            Map<String, BigDecimal> prices = priceMap(parkingMonthly.getId());
            BigDecimal unit = prices.get("DEFAULT");
            if (unit == null) {
                throw BizException.of(ErrorCodes.BILL_GEN_FAIL, "车辆月保费缺少 DEFAULT 单价");
            }
            List<RoomVehicle> excess = new ArrayList<>(vehicles);
            excess.sort(Comparator
                    .comparing((RoomVehicle v) -> v.getParkingSpaceId() == null ? 0 : 1)
                    .thenComparing(RoomVehicle::getId));
            List<RoomVehicle> monthlyVehicles = excess.subList(0, monthlyQty);
            for (RoomVehicle v : monthlyVehicles) {
                lines.add(line("PARKING_MONTHLY", parkingMonthly.getName() + "-" + v.getPlateNo(),
                        unit.setScale(2, RoundingMode.HALF_UP),
                        Map.of("plateNo", v.getPlateNo(), "unitPrice", unit, "N", n, "X", x,
                                "feeItemId", parkingMonthly.getId(), "billingMode", "FORMULA")));
            }
            }
            }
        }

        // 固定价格：OTHER / GARBAGE / SHARED / WATER / ELECTRIC / GAS（三费 FIXED 已在上方处理）
        Set<String> formulaHandled = Set.of("PROPERTY_FEE", "PARKING_MGMT", "PARKING_MONTHLY");
        for (FeeItem fi : items) {
            if (!"FIXED".equals(FeeItemService.billingModeOf(fi))) {
                continue;
            }
            if (formulaHandled.contains(fi.getFeeCategory())) {
                continue;
            }
            if (fi.getMonthlyAmount() == null) {
                continue;
            }
            BigDecimal amt = fi.getMonthlyAmount().setScale(2, RoundingMode.HALF_UP);
            if (skipIfExemptOrPrepaid(communityId, roomId, billMonth, fi.getFeeCategory(), fi.getId(),
                    fi.getName(), amt, pendingExempt, applyPrepaidSkip)) {
                continue;
            }
            lines.add(line(fi.getFeeCategory(), fi.getName(), amt,
                    Map.of("feeItemId", fi.getId(), "monthlyAmount", fi.getMonthlyAmount(),
                            "billingMode", "FIXED")));
        }

        List<BillMeterUpload> meters = meterUploadMapper.selectList(new LambdaQueryWrapper<BillMeterUpload>()
                .eq(BillMeterUpload::getCommunityId, communityId)
                .eq(BillMeterUpload::getRoomId, roomId)
                .eq(BillMeterUpload::getBillMonth, billMonth));
        for (BillMeterUpload m : meters) {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("meterUploadId", m.getId());
            if (m.getMeterStart() != null) {
                snap.put("meterStart", m.getMeterStart());
                snap.put("meterEnd", m.getMeterEnd());
                snap.put("unitPrice", m.getUnitPrice());
            }
            String title = FeeItemService.categoryLabel(m.getFeeCategory());
            FeeItem named = byCat.get(m.getFeeCategory());
            if (named != null && named.getName() != null && !named.getName().isBlank()) {
                title = named.getName();
            }
            if (skipIfExemptOrPrepaid(communityId, roomId, billMonth, m.getFeeCategory(), null, title, m.getAmount(), pendingExempt, applyPrepaidSkip)) {
                continue;
            }
            lines.add(line(m.getFeeCategory(), title, m.getAmount(), snap));
        }
        return lines;
    }

    /** @return true 表示豁免或预缴覆盖，调用方勿再写入 bill_line */
    private boolean skipIfExemptOrPrepaid(Long communityId, Long roomId, String billMonth,
                                          String feeCategory, Long feeItemId, String title, BigDecimal amount,
                                          List<FeeExemptionLedger> pendingExempt, boolean applyPrepaidSkip) {
        if (skipIfExempt(communityId, roomId, billMonth, feeCategory, feeItemId, title, amount, pendingExempt)) {
            return true;
        }
        return applyPrepaidSkip && prepaidService.isCovered(communityId, roomId, billMonth, feeCategory);
    }

    /** @return true 表示已豁免并记入 pending，调用方勿再写入 bill_line */
    private boolean skipIfExempt(Long communityId, Long roomId, String billMonth,
                                 String feeCategory, Long feeItemId, String title, BigDecimal amount,
                                 List<FeeExemptionLedger> pendingExempt) {
        RoomFeeExemption ex = roomFeeExemptionService.findMatch(
                communityId, roomId, billMonth, feeCategory, feeItemId);
        if (ex == null) {
            return false;
        }
        if (pendingExempt != null) {
            FeeExemptionLedger led = new FeeExemptionLedger();
            led.setCommunityId(communityId);
            led.setRoomId(roomId);
            led.setBillMonth(billMonth);
            led.setExemptionId(ex.getId());
            led.setFeeCategory(feeCategory);
            led.setFeeItemId(feeItemId);
            led.setTitle(title == null ? feeCategory : title);
            led.setAmount(amount == null ? BigDecimal.ZERO : amount);
            pendingExempt.add(led);
        }
        return true;
    }

    /**
     * 预缴协议报价：按当前规则计算指定费项原价（仍尊重豁免；不因已有预缴而跳过计价）。
     */
    public Map<String, BigDecimal> quoteCategoryAmounts(Long communityId, Long roomId, String billMonth,
                                                        Set<String> categories) {
        Room room = roomMapper.selectById(roomId);
        if (room == null || room.getDeletedAt() != null || !communityId.equals(room.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在");
        }
        feeItemService.seedDefaultsIfEmpty(communityId);
        List<FeeItem> items = feeItemService.enabledByCommunity(communityId);
        List<BillLine> lines = buildLinesForRoom(communityId, room, billMonth, items, null, false);
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (BillLine line : lines) {
            if (categories != null && !categories.isEmpty() && !categories.contains(line.getFeeCategory())) {
                continue;
            }
            map.merge(line.getFeeCategory(), line.getAmount(), BigDecimal::add);
        }
        return map;
    }

    /**
     * 算费预览（不写库）：摘要 + 有有效住户房屋清单及预计费项/失败原因。
     */
    public Map<String, Object> chargePreview(String billMonth) {
        Long cid = StaffGuard.communityId();
        if (billMonth == null || !billMonth.matches("\\d{4}-\\d{2}")) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "billMonth 须为 YYYY-MM");
        }
        feeItemService.seedDefaultsIfEmpty(cid);
        List<FeeItem> items = feeItemService.enabledByCommunity(cid);
        boolean propertyFeeEnabled = items.stream().anyMatch(f ->
                "PROPERTY_FEE".equals(f.getFeeCategory())
                        && "FORMULA".equals(FeeItemService.billingModeOf(f)));
        List<FeeItem> needImport = feeItemService.enabledImportConfigured(cid);
        List<String> missingImport = new ArrayList<>();
        for (FeeItem fi : needImport) {
            long cnt = meterUploadMapper.selectCount(new LambdaQueryWrapper<BillMeterUpload>()
                    .eq(BillMeterUpload::getCommunityId, cid)
                    .eq(BillMeterUpload::getBillMonth, billMonth)
                    .eq(BillMeterUpload::getFeeCategory, fi.getFeeCategory()));
            if (cnt == 0) {
                missingImport.add(fi.getName() + "(" + fi.getFeeCategory() + ")");
            }
        }
        boolean importReady = needImport.isEmpty() || missingImport.isEmpty();

        List<RoomOccupant> active = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getCommunityId, cid)
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        Map<Long, Long> occCount = new LinkedHashMap<>();
        for (RoomOccupant o : active) {
            occCount.merge(o.getRoomId(), 1L, Long::sum);
        }
        List<Long> roomIds = new ArrayList<>(occCount.keySet());
        roomIds.sort(Long::compareTo);

        Map<Long, Bill> billByRoom = new HashMap<>();
        if (!roomIds.isEmpty()) {
            List<Bill> monthBills = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                    .eq(Bill::getCommunityId, cid)
                    .eq(Bill::getBillMonth, billMonth)
                    .in(Bill::getRoomId, roomIds));
            for (Bill b : monthBills) {
                billByRoom.put(b.getRoomId(), b);
            }
        }

        Set<Long> meterRooms = meterUploadMapper.selectList(new LambdaQueryWrapper<BillMeterUpload>()
                        .eq(BillMeterUpload::getCommunityId, cid)
                        .eq(BillMeterUpload::getBillMonth, billMonth))
                .stream().map(BillMeterUpload::getRoomId).collect(Collectors.toSet());

        List<Map<String, Object>> rooms = new ArrayList<>();
        int readyCount = 0;
        int failCount = 0;
        int propertyReady = 0;
        int propertyMissing = 0;
        for (Long roomId : roomIds) {
            Room room = roomMapper.selectById(roomId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("roomId", roomId);
            row.put("occupantCount", occCount.getOrDefault(roomId, 0L));
            row.put("hasMeterUpload", meterRooms.contains(roomId));
            Bill exist = billByRoom.get(roomId);
            row.put("billStatus", exist == null ? null : exist.getStatus());
            if (room == null || room.getDeletedAt() != null) {
                row.put("roomNo", "");
                row.put("roomLabel", "房#" + roomId);
                row.put("areaSqm", null);
                row.put("houseTypeName", null);
                row.put("unitPrice", null);
                row.put("parkingSpaces", 0);
                row.put("vehicles", 0);
                row.put("status", "WILL_FAIL");
                row.put("failReason", "房屋不存在");
                row.put("expectedLines", List.of());
                row.put("estimatedTotal", null);
                failCount++;
                rooms.add(row);
                continue;
            }
            row.put("roomNo", room.getRoomNo());
            row.put("roomLabel", roomPathLabel(room));
            row.put("areaSqm", room.getAreaSqm());
            CommunityHouseType ht = room.getHouseTypeId() == null ? null : houseTypeMapper.selectById(room.getHouseTypeId());
            row.put("houseTypeName", ht == null || ht.getDeletedAt() != null ? null : ht.getName());
            row.put("unitPrice", ht == null || ht.getDeletedAt() != null ? null : ht.getPropertyFeeUnitPrice());
            long spaces = parkingSpaceMapper.selectCount(new LambdaQueryWrapper<ParkingSpace>()
                    .eq(ParkingSpace::getRoomId, roomId)
                    .isNull(ParkingSpace::getDeletedAt));
            long vehicles = roomVehicleMapper.selectCount(new LambdaQueryWrapper<RoomVehicle>()
                    .eq(RoomVehicle::getRoomId, roomId));
            row.put("parkingSpaces", spaces);
            row.put("vehicles", vehicles);

            if (propertyFeeEnabled) {
                try {
                    FeeItem propertyItem = items.stream()
                            .filter(f -> "PROPERTY_FEE".equals(f.getFeeCategory())).findFirst().orElse(null);
                    if (propertyItem != null) {
                        // 仅校验物业费前置，计入统计
                        List<FeeItem> onlyProp = List.of(propertyItem);
                        buildLinesForRoom(cid, room, billMonth, onlyProp, null, true);
                        propertyReady++;
                    }
                } catch (BizException e) {
                    propertyMissing++;
                }
            }

            try {
                List<BillLine> lines = buildLinesForRoom(cid, room, billMonth, items, null, true);
        BigDecimal total = lines.stream().map(BillLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                row.put("status", lines.isEmpty() ? "READY" : "READY");
                row.put("failReason", lines.isEmpty() ? "全额预缴/豁免覆盖，无需出账" : null);
                row.put("expectedLines", lines.stream().map(BillLine::getTitle).toList());
                row.put("estimatedTotal", total);
                row.put("prepaidCovered", lines.isEmpty());
                readyCount++;
            } catch (BizException e) {
                row.put("status", "WILL_FAIL");
                row.put("failReason", e.getMessage());
                row.put("expectedLines", List.of());
                row.put("estimatedTotal", null);
                failCount++;
            }
            rooms.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("includedCount", roomIds.size());
        summary.put("readyCount", readyCount);
        summary.put("failCount", failCount);
        summary.put("propertyFeeEnabled", propertyFeeEnabled);
        summary.put("propertyReadyCount", propertyReady);
        summary.put("propertyMissingCount", propertyMissing);
        summary.put("meterRoomCount", meterRooms.size());
        summary.put("importFeeEnabled", !needImport.isEmpty());
        summary.put("importReady", importReady);
        summary.put("missingImportCategories", missingImport);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("billMonth", billMonth);
        data.put("summary", summary);
        data.put("rooms", rooms);
        return data;
    }

    private String roomPathLabel(Room room) {
        Building building = room.getBuildingId() == null ? null : buildingMapper.selectById(room.getBuildingId());
        Unit unit = room.getUnitId() == null ? null : unitMapper.selectById(room.getUnitId());
        Floor floor = room.getFloorId() == null ? null : floorMapper.selectById(room.getFloorId());
        String path = RoomPaths.format(building, unit, floor, room);
        return path.isEmpty() ? ("房#" + room.getId()) : path;
    }

    private Map<String, BigDecimal> priceMap(Long feeItemId) {
        return feeItemService.rulesOf(feeItemId).stream()
                .collect(Collectors.toMap(FeeItemPriceRule::getMatchKey, FeeItemPriceRule::getUnitPrice, (a, b) -> a));
    }

    /**
     * 物业费 remark JSON：useArea / useHouseTypePrice / discountRate（界面称「系数」，≥0，缺省 1）。
     * 缺省：面积×物业费单价×1。兼容旧字段 areaSource / unitPriceSource。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePropertyFormula(String remark) {
        boolean useArea = true;
        boolean useHouseTypePrice = true;
        BigDecimal rate = BigDecimal.ONE;
        if (remark != null && !remark.isBlank()) {
            try {
                Map<String, Object> m = objectMapper.readValue(remark, Map.class);
                if (m.containsKey("useArea")) {
                    useArea = Boolean.parseBoolean(String.valueOf(m.get("useArea")));
                } else if ("NONE".equals(String.valueOf(m.get("areaSource")))) {
                    useArea = false;
                }
                if (m.containsKey("useHouseTypePrice")) {
                    useHouseTypePrice = Boolean.parseBoolean(String.valueOf(m.get("useHouseTypePrice")));
                } else if ("NONE".equals(String.valueOf(m.get("unitPriceSource")))) {
                    useHouseTypePrice = false;
                }
                Object d = m.get("discountRate");
                if (d != null) {
                    rate = new BigDecimal(String.valueOf(d));
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception ignored) {
                // keep defaults
            }
        }
        if (rate.compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "物业费系数不能为负数");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("useArea", useArea);
        out.put("useHouseTypePrice", useHouseTypePrice);
        out.put("discountRate", rate);
        return out;
    }

    private BillLine line(String cat, String title, BigDecimal amount, Map<String, Object> snap) {
        BillLine l = new BillLine();
        l.setFeeCategory(cat);
        l.setTitle(title);
        l.setAmount(amount);
        try {
            l.setSnapshotJson(objectMapper.writeValueAsString(snap));
        } catch (Exception e) {
            l.setSnapshotJson(null);
        }
        return l;
    }

    private List<Long> resolveActiveRooms(Long communityId, List<Long> roomIds) {
        if (roomIds != null && !roomIds.isEmpty()) {
            return roomIds;
        }
        List<RoomOccupant> active = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getCommunityId, communityId)
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        return active.stream().map(RoomOccupant::getRoomId).distinct().toList();
    }

    // ---------- queries ----------

    public Map<String, Object> staffList(String billMonth, String status, Long roomId, int page, int pageSize) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<Bill> q = new LambdaQueryWrapper<Bill>()
                .eq(Bill::getCommunityId, cid)
                .orderByDesc(Bill::getId);
        if (billMonth != null && !billMonth.isBlank()) {
            q.eq(Bill::getBillMonth, billMonth);
        }
        if (status != null && !status.isBlank()) {
            q.eq(Bill::getStatus, status);
        }
        if (roomId != null) {
            q.eq(Bill::getRoomId, roomId);
        }
        return pageBills(q, page, pageSize, true);
    }

    public Map<String, Object> staffGet(Long id) {
        Bill b = requireStaffBill(id);
        return billDetail(b, true);
    }

    public Map<String, Object> residentList(String status, int page, int pageSize) {
        AuthUser u = AuthContext.require();
        if (!"RESIDENT".equals(u.getIdentityType()) || u.getCommunityId() == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换为住户身份");
        }
        List<Long> roomIds = myActiveRoomIds(u);
        if (roomIds.isEmpty()) {
            return emptyPage(page, pageSize);
        }
        LambdaQueryWrapper<Bill> q = new LambdaQueryWrapper<Bill>()
                .eq(Bill::getCommunityId, u.getCommunityId())
                .in(Bill::getRoomId, roomIds)
                .orderByDesc(Bill::getBillMonth)
                .orderByDesc(Bill::getId);
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase();
            if ("UNPAID".equals(s) || "DUE".equals(s)) {
                q.in(Bill::getStatus, List.of("PUBLISHED", "OVERDUE"));
            } else {
                q.eq(Bill::getStatus, s);
            }
        } else {
            // 默认：我的账单只看待缴
            q.in(Bill::getStatus, List.of("PUBLISHED", "OVERDUE"));
        }
        return pageBills(q, page, pageSize, true);
    }

    public Map<String, Object> residentGet(Long id) {
        Bill b = requireResidentBill(id);
        return billDetail(b, true);
    }

    public Map<String, Object> platformList(Long communityId, String billMonth, int page, int pageSize) {
        requirePlatform();
        LambdaQueryWrapper<Bill> q = new LambdaQueryWrapper<Bill>().orderByDesc(Bill::getId);
        if (communityId != null) {
            q.eq(Bill::getCommunityId, communityId);
        }
        if (billMonth != null && !billMonth.isBlank()) {
            q.eq(Bill::getBillMonth, billMonth);
        }
        return pageBills(q, page, pageSize, false);
    }

    // ---------- pay ----------

    public Map<String, Object> getPaymentConfig() {
        Long cid = StaffGuard.communityId();
        PaymentConfig cfg = paymentConfigMapper.selectOne(new LambdaQueryWrapper<PaymentConfig>()
                .eq(PaymentConfig::getCommunityId, cid));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("guideText", cfg == null ? null : cfg.getGuideText());
        data.put("qrAttachmentId", cfg == null ? null : cfg.getQrAttachmentId());
        data.put("merchantPayEnabled", false);
        data.put("prepaidEnabled", cfg != null && cfg.getPrepaidEnabled() != null && cfg.getPrepaidEnabled() == 1);
        data.put("prepaidGuideText", cfg == null ? null : cfg.getPrepaidGuideText());
        return data;
    }

    @Transactional
    public Map<String, Object> putPaymentConfig(String guideText, Long qrAttachmentId,
                                                Boolean prepaidEnabled, String prepaidGuideText) {
        AuthUser u = StaffGuard.requireStaff();
        StaffGuard.requireManagerOrPlatform(u);
        Long cid = u.getCommunityId();
        PaymentConfig cfg = paymentConfigMapper.selectOne(new LambdaQueryWrapper<PaymentConfig>()
                .eq(PaymentConfig::getCommunityId, cid));
        LocalDateTime now = LocalDateTime.now();
        if (cfg == null) {
            cfg = new PaymentConfig();
            cfg.setCommunityId(cid);
            cfg.setGuideText(guideText);
            cfg.setQrAttachmentId(qrAttachmentId);
            cfg.setPrepaidEnabled(Boolean.TRUE.equals(prepaidEnabled) ? 1 : 0);
            cfg.setPrepaidGuideText(prepaidGuideText);
            cfg.setUpdatedBy(u.getUserId());
            cfg.setCreatedAt(now);
            cfg.setUpdatedAt(now);
            paymentConfigMapper.insert(cfg);
        } else {
            cfg.setGuideText(guideText);
            cfg.setQrAttachmentId(qrAttachmentId);
            if (prepaidEnabled != null) {
                cfg.setPrepaidEnabled(Boolean.TRUE.equals(prepaidEnabled) ? 1 : 0);
            }
            if (prepaidGuideText != null) {
                cfg.setPrepaidGuideText(prepaidGuideText);
            }
            cfg.setUpdatedBy(u.getUserId());
            cfg.setUpdatedAt(now);
            paymentConfigMapper.updateById(cfg);
        }
        return getPaymentConfig();
    }

    public Map<String, Object> payGuide(Long billId) {
        Bill b = requireResidentBill(billId);
        PaymentConfig cfg = paymentConfigMapper.selectOne(new LambdaQueryWrapper<PaymentConfig>()
                .eq(PaymentConfig::getCommunityId, b.getCommunityId()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("billId", b.getId());
        data.put("totalAmount", b.getTotalAmount());
        data.put("guideText", cfg == null ? null : cfg.getGuideText());
        data.put("qrAttachmentId", cfg == null ? null : cfg.getQrAttachmentId());
        return data;
    }

    @Transactional
    public Map<String, Object> wechatPay(Long billId) {
        Bill b = requireResidentBill(billId);
        if ("PAID".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BILL_LOCKED, "账单已缴费，无需再支付");
        }
        if (!"PUBLISHED".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅已发放未缴账单可支付");
        }
        Long uid = AuthContext.require().getUserId();
        UserWechat bind = userWechatMapper.selectOne(new LambdaQueryWrapper<UserWechat>()
                .eq(UserWechat::getUserId, uid)
                .eq(UserWechat::getAppId, wxProperties.getAppId())
                .last("LIMIT 1"));
        String openid = bind == null ? null : bind.getOpenid();
        Map<String, Object> data = wechatPayApi.createJsapiOrder(b, openid);
        Object prepayId = data.get("prepayId");
        if (prepayId != null) {
            b.setWechatTransactionId(String.valueOf(prepayId));
        b.setUpdatedAt(LocalDateTime.now());
        billMapper.updateById(b);
        }
        return data;
    }

    @Transactional
    public Map<String, Object> wechatNotify(Map<String, Object> body) {
        String prepayId = str(body == null ? null : body.get("prepayId"));
        if (prepayId == null) {
            prepayId = str(body == null ? null : body.get("wechatTransactionId"));
        }
        if (prepayId == null || prepayId.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "prepayId 必填");
        }
        // 必须与下单时写入账单的预支付单号一致；禁止仅凭 billId 入账
        Bill b = billMapper.selectOne(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getWechatTransactionId, prepayId)
                .last("LIMIT 1"));
        if (b == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "未找到对应预支付账单，请先发起支付");
        }
        if ("PAID".equals(b.getStatus())) {
            return Map.of("idempotent", true, "billId", b.getId());
        }
        if (!"PUBLISHED".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "账单状态不可确认支付");
        }
        boolean mock = wechatPayApi.isMock() || prepayId.startsWith("MOCK_");
        if (!mock) {
            // 正式签名校验待接 SDK；未接前拒绝裸回调，避免伪造入账
            throw BizException.of(ErrorCodes.FORBIDDEN, "正式支付回调须完成签名校验后方可入账");
        }
        markPaid(b, "WECHAT_MCH", null, AuthContext.get() == null ? null : AuthContext.get().getUserId(),
                "微信商户支付(MOCK)");
        return Map.of("ok", true, "billId", b.getId(), "mock", true);
    }

    @Transactional
    public Bill confirmPaid(Long billId, String payChannel, String remark) {
        AuthUser staff = StaffGuard.requireStaff();
        Bill b = requireStaffBill(billId);
        if ("PAID".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BILL_LOCKED, "账单已确认收款");
        }
        if (!"PUBLISHED".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅已发放账单可确认收款");
        }
        String channel = payChannel == null || payChannel.isBlank() ? "OTHER" : payChannel;
        Set<String> ok = Set.of("TRANSFER", "QR", "CASH", "OTHER");
        if (!ok.contains(channel)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "payChannel 非法");
        }
        markPaid(b, channel, staff.getUserId(), null, remark);
        return billMapper.selectById(billId);
    }

    @Transactional
    public Bill refundDuplicate(Long billId, String remark) {
        // 兼容旧路径：转为冲红
        return creditReverse(billId, remark == null || remark.isBlank() ? "重复支付冲红" : remark);
    }

    /** 未缴作废：PUBLISHED → VOID，不影响财务公开 */
    @Transactional
    public Bill voidUnpaid(Long billId, String reason) {
        AuthUser staff = StaffGuard.requireStaff();
        Bill b = requireStaffBill(billId);
        if ("PAID".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BILL_LOCKED, "已缴账单请使用冲红，不可直接作废");
        }
        if (!"PUBLISHED".equals(b.getStatus()) && !"DRAFT".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅草稿或已发放未缴账单可作废");
        }
        LocalDateTime now = LocalDateTime.now();
        b.setStatus("VOID");
        b.setUpdatedAt(now);
        if (reason != null && !reason.isBlank()) {
            b.setWechatTransactionId(null);
        }
        billMapper.updateById(b);
        todoNotifyService.doneByBiz("BILL", b.getId(), "BILL_DUE");
        return b;
    }

    /**
     * 预缴签约前：覆盖月若存在已缴账单且含拟覆盖费项 → 拒绝（须先冲红）。
     */
    public void assertNoPaidConflictForPrepaid(Long communityId, Long roomId,
                                               Collection<String> billMonths, Collection<String> feeCategories) {
        Set<String> cats = new LinkedHashSet<>(feeCategories);
        List<String> conflicts = new ArrayList<>();
        for (String month : billMonths) {
            Bill b = billMapper.selectOne(new LambdaQueryWrapper<Bill>()
                    .eq(Bill::getCommunityId, communityId)
                    .eq(Bill::getRoomId, roomId)
                    .eq(Bill::getBillMonth, month));
            if (b == null || !"PAID".equals(b.getStatus())) {
                continue;
            }
            List<BillLine> lines = billLineMapper.selectList(new LambdaQueryWrapper<BillLine>()
                    .eq(BillLine::getBillId, b.getId()));
            for (BillLine line : lines) {
                if (cats.contains(line.getFeeCategory())) {
                    conflicts.add(month + "/" + line.getFeeCategory());
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM,
                    "以下账期费项已缴，不能直接预缴，请先冲红后再签：" + String.join("、", conflicts));
        }
    }

    /**
     * 读已有账单上某费项金额合计（非 VOID）；无则返回 null。
     */
    public BigDecimal existingBillLineAmount(Long communityId, Long roomId, String billMonth, String feeCategory) {
        Bill b = billMapper.selectOne(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getCommunityId, communityId)
                .eq(Bill::getRoomId, roomId)
                .eq(Bill::getBillMonth, billMonth));
        if (b == null || "VOID".equals(b.getStatus())) {
            return null;
        }
        List<BillLine> lines = billLineMapper.selectList(new LambdaQueryWrapper<BillLine>()
                .eq(BillLine::getBillId, b.getId())
                .eq(BillLine::getFeeCategory, feeCategory));
        if (lines.isEmpty()) {
            return null;
        }
        return lines.stream().map(BillLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 预缴生效：对未缴 DRAFT/PUBLISHED 账单去掉覆盖费项；无剩余明细则作废。
     * 已缴冲突须已由 assertNoPaidConflictForPrepaid 拦截。
     */
    @Transactional
    public Map<String, Object> applyPrepaidCoverageToExistingBills(Long communityId, Long roomId,
                                                                   Collection<String> billMonths,
                                                                   Collection<String> feeCategories,
                                                                   Long planId) {
        assertNoPaidConflictForPrepaid(communityId, roomId, billMonths, feeCategories);
        Set<String> cats = new LinkedHashSet<>(feeCategories);
        int adjusted = 0;
        int voided = 0;
        List<String> details = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (String month : billMonths) {
            Bill b = billMapper.selectOne(new LambdaQueryWrapper<Bill>()
                    .eq(Bill::getCommunityId, communityId)
                    .eq(Bill::getRoomId, roomId)
                    .eq(Bill::getBillMonth, month));
            if (b == null || "VOID".equals(b.getStatus()) || "PAID".equals(b.getStatus())) {
                continue;
            }
            if (!"DRAFT".equals(b.getStatus()) && !"PUBLISHED".equals(b.getStatus())) {
                continue;
            }
            List<BillLine> lines = billLineMapper.selectList(new LambdaQueryWrapper<BillLine>()
                    .eq(BillLine::getBillId, b.getId()));
            List<BillLine> remove = lines.stream()
                    .filter(l -> cats.contains(l.getFeeCategory()))
                    .toList();
            if (remove.isEmpty()) {
                continue;
            }
            BigDecimal removed = remove.stream().map(BillLine::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            for (BillLine line : remove) {
                // 标题加注便于审计；再删除行
                billLineMapper.deleteById(line.getId());
            }
            List<BillLine> remain = billLineMapper.selectList(new LambdaQueryWrapper<BillLine>()
                    .eq(BillLine::getBillId, b.getId()));
            if (remain.isEmpty()) {
                b.setStatus("VOID");
                b.setTotalAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                b.setUpdatedAt(now);
                billMapper.updateById(b);
                todoNotifyService.doneByBiz("BILL", b.getId(), "BILL_DUE");
                voided++;
                details.add(month + " 账单#" + b.getId() + " 已无剩余费项，已作废（预缴协议#" + planId + "）");
            } else {
                BigDecimal total = remain.stream().map(BillLine::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(2, RoundingMode.HALF_UP);
                b.setTotalAmount(total);
                b.setUpdatedAt(now);
                billMapper.updateById(b);
                adjusted++;
                details.add(month + " 账单#" + b.getId() + " 已去掉覆盖费项合计 " + removed
                        + "，新合计 " + total + "（预缴协议#" + planId + "）");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("adjustedCount", adjusted);
        data.put("voidedCount", voided);
        data.put("details", details);
        return data;
    }

    /** 已缴冲红：写入负向 payment_record，账单 → VOID，公开收入回冲 */
    @Transactional
    public Bill creditReverse(Long billId, String reason) {
        AuthUser staff = StaffGuard.requireStaff();
        Bill b = requireStaffBill(billId);
        if (!"PAID".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅已缴账单可冲红");
        }
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "冲红原因必填");
        }
        List<PaymentRecord> positives = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getBillId, b.getId())
                .gt(PaymentRecord::getAmount, BigDecimal.ZERO));
        BigDecimal paidSum = positives.stream().map(PaymentRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<PaymentRecord> negatives = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getBillId, b.getId())
                .lt(PaymentRecord::getAmount, BigDecimal.ZERO));
        BigDecimal reversed = negatives.stream().map(PaymentRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add).abs();
        BigDecimal remain = paidSum.subtract(reversed);
        if (remain.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "该账单已无剩余可冲红金额");
        }
        LocalDateTime now = LocalDateTime.now();
        PaymentRecord pr = new PaymentRecord();
        pr.setCommunityId(b.getCommunityId());
        pr.setBillId(b.getId());
        pr.setRoomId(b.getRoomId());
        pr.setPayerUserId(null);
        pr.setAmount(remain.negate());
        pr.setPayChannel(b.getPayChannel() == null ? "OTHER" : b.getPayChannel());
        pr.setConfirmedBy(staff.getUserId());
        pr.setPaidAt(now);
        pr.setRemark("冲红：" + reason.trim());
        fillFeeSnapshot(pr, b.getId());
        // 冲红时优先沿用原正数收款快照，避免账单已重出后误记当前费项
        PaymentRecord src = positives.stream()
                .filter(x -> x.getFeeTypeLabel() != null && !x.getFeeTypeLabel().isBlank())
                .reduce((a, x) -> x)
                .orElse(null);
        if (src != null) {
            pr.setFeeTypeLabel(src.getFeeTypeLabel());
            pr.setFeeCategories(src.getFeeCategories());
        }
        pr.setCreatedAt(now);
        paymentRecordMapper.insert(pr);

        b.setStatus("VOID");
        b.setUpdatedAt(now);
        billMapper.updateById(b);
        return billMapper.selectById(billId);
    }

    /** 补收：对已冲红/作废后重新发放的账单，或对仍为 PAID 的账单追加正数缴费（少收补差） */
    @Transactional
    public PaymentRecord supplementPayment(Long billId, BigDecimal amount, String payChannel, String remark) {
        AuthUser staff = StaffGuard.requireStaff();
        Bill b = requireStaffBill(billId);
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "补收金额须为正数");
        }
        if (!"PAID".equals(b.getStatus()) && !"PUBLISHED".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅已发放或已缴账单可补收");
        }
        String channel = payChannel == null || payChannel.isBlank() ? "OTHER" : payChannel;
        LocalDateTime now = LocalDateTime.now();
        if ("PUBLISHED".equals(b.getStatus())) {
            // 未缴补收语义不当；引导确认收款。此处若金额等于总额则等同确认
            if (amount.compareTo(b.getTotalAmount()) == 0) {
                markPaid(b, channel, staff.getUserId(), null, remark);
                return paymentRecordMapper.selectOne(new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getBillId, billId)
                        .gt(PaymentRecord::getAmount, BigDecimal.ZERO)
                        .orderByDesc(PaymentRecord::getId)
                        .last("LIMIT 1"));
            }
            throw BizException.of(ErrorCodes.BAD_PARAM, "未缴账单请使用确认收款；补收仅用于已缴少收场景");
        }
        PaymentRecord pr = new PaymentRecord();
        pr.setCommunityId(b.getCommunityId());
        pr.setBillId(b.getId());
        pr.setRoomId(b.getRoomId());
        pr.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        pr.setPayChannel(channel);
        pr.setConfirmedBy(staff.getUserId());
        pr.setPaidAt(now);
        pr.setRemark(remark == null || remark.isBlank() ? "补收" : "补收：" + remark.trim());
        pr.setCreatedAt(now);
        fillFeeSnapshot(pr, b.getId());
        paymentRecordMapper.insert(pr);
        return pr;
    }

    @Transactional
    public Map<String, Object> batchConfirmPaid(List<Long> billIds, String payChannel, String remark) {
        if (billIds == null || billIds.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "billIds 不能为空");
        }
        List<Bill> paid = new ArrayList<>();
        for (Long id : billIds) {
            paid.add(confirmPaid(id, payChannel, remark));
        }
        BigDecimal total = paid.stream().map(Bill::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("count", paid.size(), "totalAmount", total, "bills", paid);
    }

    @Transactional
    public Map<String, Object> batchWechatPay(List<Long> billIds) {
        if (billIds == null || billIds.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "billIds 不能为空");
        }
        BigDecimal total = BigDecimal.ZERO;
        List<Bill> bills = new ArrayList<>();
        for (Long id : billIds) {
            Bill b = requireResidentBill(id);
            if (!"PUBLISHED".equals(b.getStatus())) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "仅已发放未缴账单可合并支付，账单#" + id);
            }
            total = total.add(b.getTotalAmount());
            bills.add(b);
        }
        // MOCK：直接逐单确认；正式环境可后续扩展为一次下单多分账
        if (wechatPayApi.isMock()) {
            Long uid = AuthContext.require().getUserId();
            for (Bill b : bills) {
                markPaid(b, "WECHAT_MCH", null, uid, "合并支付(MOCK)");
            }
            return Map.of(
                    "mock", true,
                    "paid", true,
                    "count", bills.size(),
                    "totalAmount", total,
                    "billIds", billIds);
        }
        // 非 MOCK：对金额最大的单拉起支付会不准确；暂对首单下单并在备注标明合并（正式接入前以 MOCK 为主）
        Map<String, Object> first = wechatPay(bills.get(0).getId());
        first.put("batchTotalAmount", total);
        first.put("batchBillIds", billIds);
        first.put("hint", "正式商户合并支付待完整接入；当前仅锁定首单预支付");
        return first;
    }

    private void markPaid(Bill b, String channel, Long confirmedBy, Long payerUserId, String remark) {
        LocalDateTime now = LocalDateTime.now();
        int updated = billMapper.update(null, new LambdaUpdateWrapper<Bill>()
                .eq(Bill::getId, b.getId())
                .eq(Bill::getStatus, "PUBLISHED")
                .set(Bill::getStatus, "PAID")
                .set(Bill::getPayChannel, channel)
                .set(Bill::getPaidAt, now)
                .set(Bill::getConfirmedBy, confirmedBy)
                .set(Bill::getUpdatedAt, now));
        if (updated == 0) {
            Bill latest = billMapper.selectById(b.getId());
            if (latest != null && "PAID".equals(latest.getStatus())) {
                return;
            }
            throw BizException.of(ErrorCodes.BILL_LOCKED, "账单状态已变更，无法确认收款");
        }
        b.setStatus("PAID");
        b.setPayChannel(channel);
        b.setPaidAt(now);
        b.setConfirmedBy(confirmedBy);

        List<PaymentRecord> pos = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getBillId, b.getId())
                .gt(PaymentRecord::getAmount, BigDecimal.ZERO));
        BigDecimal posSum = pos.stream().map(PaymentRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<PaymentRecord> neg = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getBillId, b.getId())
                .lt(PaymentRecord::getAmount, BigDecimal.ZERO));
        BigDecimal negAbs = neg.stream().map(PaymentRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add).abs();
        if (posSum.subtract(negAbs).compareTo(BigDecimal.ZERO) > 0) {
            todoNotifyService.doneByBiz("BILL", b.getId(), "BILL_DUE");
            return;
        }
            PaymentRecord pr = new PaymentRecord();
            pr.setCommunityId(b.getCommunityId());
            pr.setBillId(b.getId());
            pr.setRoomId(b.getRoomId());
            pr.setPayerUserId(payerUserId);
            pr.setAmount(b.getTotalAmount());
            pr.setPayChannel(channel);
            pr.setConfirmedBy(confirmedBy);
            pr.setPaidAt(now);
            pr.setRemark(remark);
            pr.setCreatedAt(now);
        fillFeeSnapshot(pr, b.getId());
            paymentRecordMapper.insert(pr);
        todoNotifyService.doneByBiz("BILL", b.getId(), "BILL_DUE");
    }

    private void fillFeeSnapshot(PaymentRecord pr, Long billId) {
        if (pr == null || billId == null) {
            return;
        }
        List<BillLine> lines = billLineMapper.selectList(new LambdaQueryWrapper<BillLine>()
                .eq(BillLine::getBillId, billId)
                .orderByAsc(BillLine::getId));
        if (lines.isEmpty()) {
            return;
        }
        List<String> labels = new ArrayList<>();
        List<String> cats = new ArrayList<>();
        Set<String> seenLabel = new LinkedHashSet<>();
        Set<String> seenCat = new LinkedHashSet<>();
        for (BillLine line : lines) {
            if (line.getFeeCategory() != null && !line.getFeeCategory().isBlank() && seenCat.add(line.getFeeCategory())) {
                cats.add(line.getFeeCategory());
            }
            String label = line.getTitle() != null && !line.getTitle().isBlank()
                    ? line.getTitle().trim()
                    : line.getFeeCategory();
            if (label != null && !label.isBlank() && seenLabel.add(label)) {
                labels.add(label);
            }
        }
        if (!labels.isEmpty()) {
            pr.setFeeTypeLabel(String.join("、", labels));
        }
        if (!cats.isEmpty()) {
            pr.setFeeCategories(String.join(",", cats));
        }
    }

    /** 预缴冲抵入账：渠道 PREPAID，计入公开；归属账期见 FinanceService（按 bill_month） */
    public void markPaidFromPrepaid(Bill b, Long payerUserId, Long confirmedBy) {
        markPaid(b, "PREPAID", confirmedBy, payerUserId, "预缴冲抵 " + b.getBillMonth());
    }

    public Map<String, Object> listPaymentRecords(Long communityId, Long billId, int page, int pageSize, boolean staffOrPlatform) {
        LambdaQueryWrapper<PaymentRecord> q = new LambdaQueryWrapper<PaymentRecord>()
                .orderByDesc(PaymentRecord::getId);
        if (communityId != null) {
            q.eq(PaymentRecord::getCommunityId, communityId);
        }
        if (billId != null) {
            q.eq(PaymentRecord::getBillId, billId);
        }
        Page<PaymentRecord> p = paymentRecordMapper.selectPage(new Page<>(page, pageSize), q);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", p.getRecords());
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    public Map<String, Object> residentPaymentRecords(int page, int pageSize) {
        AuthUser u = AuthContext.require();
        if (!"RESIDENT".equals(u.getIdentityType()) || u.getCommunityId() == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换为住户身份");
        }
        List<Long> roomIds = myActiveRoomIds(u);
        if (roomIds.isEmpty()) {
            return emptyPage(page, pageSize);
        }
        LambdaQueryWrapper<PaymentRecord> q = new LambdaQueryWrapper<PaymentRecord>()
                .eq(PaymentRecord::getCommunityId, u.getCommunityId())
                .in(PaymentRecord::getRoomId, roomIds)
                .orderByDesc(PaymentRecord::getId);
        Page<PaymentRecord> p = paymentRecordMapper.selectPage(new Page<>(page, pageSize), q);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", p.getRecords());
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    // ---------- helpers ----------

    private Map<String, Object> pageBills(LambdaQueryWrapper<Bill> q, int page, int pageSize, boolean withLines) {
        Page<Bill> p = billMapper.selectPage(new Page<>(page, pageSize), q);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Bill b : p.getRecords()) {
            list.add(billDetail(b, withLines));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    private Map<String, Object> billDetail(Bill b, boolean withLines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("communityId", b.getCommunityId());
        m.put("roomId", b.getRoomId());
        Room room = roomMapper.selectById(b.getRoomId());
        if (room != null && room.getDeletedAt() == null) {
            m.put("roomNo", room.getRoomNo());
            Building building = room.getBuildingId() == null ? null : buildingMapper.selectById(room.getBuildingId());
            Unit unit = room.getUnitId() == null ? null : unitMapper.selectById(room.getUnitId());
            Floor floor = room.getFloorId() == null ? null : floorMapper.selectById(room.getFloorId());
            String path = RoomPaths.format(building, unit, floor, room);
            m.put("roomLabel", path.isEmpty() ? ("房#" + b.getRoomId()) : path);
        } else {
            m.put("roomNo", null);
            m.put("roomLabel", "房#" + b.getRoomId());
        }
        m.put("billMonth", b.getBillMonth());
        m.put("status", b.getStatus());
        m.put("dueDate", b.getDueDate());
        m.put("totalAmount", b.getTotalAmount());
        m.put("payChannel", b.getPayChannel());
        m.put("paidAt", b.getPaidAt());
        m.put("confirmedBy", b.getConfirmedBy());
        m.put("publishedAt", b.getPublishedAt());
        m.put("createdAt", b.getCreatedAt());
        if (withLines) {
            List<BillLine> lines = billLineMapper.selectList(new LambdaQueryWrapper<BillLine>()
                    .eq(BillLine::getBillId, b.getId())
                    .orderByAsc(BillLine::getId));
            m.put("lines", lines);
            m.put("lineSummary", lines.stream()
                    .map(l -> (l.getTitle() == null ? l.getFeeCategory() : l.getTitle())
                            + " " + (l.getAmount() == null ? "0" : l.getAmount().toPlainString()))
                    .collect(Collectors.joining("；")));
        }
        return m;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private Bill requireStaffBill(Long id) {
        Bill b = billMapper.selectById(id);
        if (b == null || !b.getCommunityId().equals(StaffGuard.communityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "账单不存在");
        }
        return b;
    }

    private Bill requireResidentBill(Long id) {
        AuthUser u = AuthContext.require();
        Bill b = billMapper.selectById(id);
        if (b == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "账单不存在");
        }
        if (!"RESIDENT".equals(u.getIdentityType()) || u.getCommunityId() == null
                || !u.getCommunityId().equals(b.getCommunityId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "无权查看该账单");
        }
        if ("DRAFT".equals(b.getStatus())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "账单尚未发放");
        }
        List<Long> roomIds = myActiveRoomIds(u);
        if (!roomIds.contains(b.getRoomId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "非本房账单");
        }
        return b;
    }

    private List<Long> myActiveRoomIds(AuthUser u) {
        return roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                        .eq(RoomOccupant::getUserId, u.getUserId())
                        .eq(RoomOccupant::getCommunityId, u.getCommunityId())
                        .eq(RoomOccupant::getStatus, "ACTIVE"))
                .stream().map(RoomOccupant::getRoomId).distinct().toList();
    }

    private void requirePlatform() {
        AuthUser u = AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台身份");
        }
    }

    private static Map<String, Object> emptyPage(int page, int pageSize) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", List.of());
        data.put("total", 0);
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        return Long.parseLong(s);
    }

    private static BigDecimal toDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        return new BigDecimal(s);
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
