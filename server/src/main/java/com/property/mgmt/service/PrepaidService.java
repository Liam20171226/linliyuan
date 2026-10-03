package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.PrepaidPlanItemMapper;
import com.property.mgmt.mapper.PrepaidPlanMapper;
import com.property.mgmt.mapper.RoomMapper;
import com.property.mgmt.mapper.RoomOccupantMapper;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.StaffGuard;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 预缴方案 A（Q33）：协议约定月份+费项；实付可打折；出账跳过覆盖项；公开按账期结转 cash，折扣不进收入。
 */
@Service
public class PrepaidService {

    private final PrepaidPlanMapper planMapper;
    private final PrepaidPlanItemMapper itemMapper;
    private final RoomMapper roomMapper;
    private final RoomOccupantMapper roomOccupantMapper;
    private final BillingService billingService;
    private final OccupantQueryService occupantQueryService;

    public PrepaidService(PrepaidPlanMapper planMapper,
                          PrepaidPlanItemMapper itemMapper,
                          RoomMapper roomMapper,
                          RoomOccupantMapper roomOccupantMapper,
                          @Lazy BillingService billingService,
                          OccupantQueryService occupantQueryService) {
        this.planMapper = planMapper;
        this.itemMapper = itemMapper;
        this.roomMapper = roomMapper;
        this.roomOccupantMapper = roomOccupantMapper;
        this.billingService = billingService;
        this.occupantQueryService = occupantQueryService;
    }

    public boolean isCovered(Long communityId, Long roomId, String billMonth, String feeCategory) {
        if (communityId == null || roomId == null || billMonth == null || feeCategory == null) {
            return false;
        }
        List<PrepaidPlanItem> items = itemMapper.selectList(new LambdaQueryWrapper<PrepaidPlanItem>()
                .eq(PrepaidPlanItem::getCommunityId, communityId)
                .eq(PrepaidPlanItem::getRoomId, roomId)
                .eq(PrepaidPlanItem::getBillMonth, billMonth)
                .eq(PrepaidPlanItem::getFeeCategory, feeCategory));
        if (items.isEmpty()) {
            return false;
        }
        Set<Long> planIds = items.stream().map(PrepaidPlanItem::getPlanId).collect(Collectors.toSet());
        long active = planMapper.selectCount(new LambdaQueryWrapper<PrepaidPlan>()
                .in(PrepaidPlan::getId, planIds)
                .eq(PrepaidPlan::getStatus, "ACTIVE"));
        return active > 0;
    }

    public BigDecimal sumCashByBillMonthRange(Long communityId, String fromMonth, String toMonth) {
        return sumCashByFeeCategory(communityId, fromMonth, toMonth).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** ACTIVE 预缴明细按费项大类汇总实付（按账单月区间）。 */
    public Map<String, BigDecimal> sumCashByFeeCategory(Long communityId, String fromMonth, String toMonth) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (PrepaidPlanItem it : listActiveItems(communityId, fromMonth, toMonth)) {
            if (it.getCashAmount() == null || it.getCashAmount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            String cat = it.getFeeCategory() == null || it.getFeeCategory().isBlank()
                    ? "OTHER" : it.getFeeCategory().trim();
            out.merge(cat, it.getCashAmount(), BigDecimal::add);
        }
        out.replaceAll((k, v) -> v.setScale(2, RoundingMode.HALF_UP));
        return out;
    }

    /** ACTIVE 预缴协议在账期内的分摊明细（含协议支付方式与确认时间）。 */
    public List<PrepaidCashRow> listCashRowsByBillMonthRange(Long communityId, String fromMonth, String toMonth) {
        List<PrepaidCashRow> rows = new ArrayList<>();
        if (communityId == null || fromMonth == null || toMonth == null) {
            return rows;
        }
        List<PrepaidPlan> plans = planMapper.selectList(new LambdaQueryWrapper<PrepaidPlan>()
                .eq(PrepaidPlan::getCommunityId, communityId)
                .eq(PrepaidPlan::getStatus, "ACTIVE"));
        if (plans.isEmpty()) {
            return rows;
        }
        Map<Long, PrepaidPlan> planById = plans.stream()
                .collect(Collectors.toMap(PrepaidPlan::getId, p -> p, (a, b) -> a, LinkedHashMap::new));
        List<PrepaidPlanItem> items = itemMapper.selectList(new LambdaQueryWrapper<PrepaidPlanItem>()
                .in(PrepaidPlanItem::getPlanId, planById.keySet())
                .ge(PrepaidPlanItem::getBillMonth, fromMonth)
                .le(PrepaidPlanItem::getBillMonth, toMonth)
                .orderByAsc(PrepaidPlanItem::getBillMonth)
                .orderByAsc(PrepaidPlanItem::getId));
        for (PrepaidPlanItem it : items) {
            if (it.getCashAmount() == null || it.getCashAmount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            PrepaidPlan plan = planById.get(it.getPlanId());
            PrepaidCashRow row = new PrepaidCashRow();
            row.itemId = it.getId();
            row.planId = it.getPlanId();
            row.roomId = it.getRoomId() != null ? it.getRoomId() : (plan == null ? null : plan.getRoomId());
            row.billMonth = it.getBillMonth();
            row.feeCategory = it.getFeeCategory() == null || it.getFeeCategory().isBlank()
                    ? "OTHER" : it.getFeeCategory().trim();
            row.cashAmount = it.getCashAmount().setScale(2, RoundingMode.HALF_UP);
            row.payChannel = plan == null ? "PREPAID" : (plan.getPayChannel() == null || plan.getPayChannel().isBlank()
                    ? "PREPAID" : plan.getPayChannel());
            row.confirmedAt = plan == null ? null : plan.getConfirmedAt();
            row.createdAt = it.getCreatedAt();
            rows.add(row);
        }
        return rows;
    }

    private List<PrepaidPlanItem> listActiveItems(Long communityId, String fromMonth, String toMonth) {
        if (communityId == null || fromMonth == null || toMonth == null) {
            return List.of();
        }
        List<PrepaidPlan> plans = planMapper.selectList(new LambdaQueryWrapper<PrepaidPlan>()
                .eq(PrepaidPlan::getCommunityId, communityId)
                .eq(PrepaidPlan::getStatus, "ACTIVE"));
        if (plans.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = plans.stream().map(PrepaidPlan::getId).collect(Collectors.toSet());
        return itemMapper.selectList(new LambdaQueryWrapper<PrepaidPlanItem>()
                .in(PrepaidPlanItem::getPlanId, ids)
                .ge(PrepaidPlanItem::getBillMonth, fromMonth)
                .le(PrepaidPlanItem::getBillMonth, toMonth));
    }

    public static final class PrepaidCashRow {
        public Long itemId;
        public Long planId;
        public Long roomId;
        public String billMonth;
        public String feeCategory;
        public BigDecimal cashAmount;
        public String payChannel;
        public LocalDateTime confirmedAt;
        public LocalDateTime createdAt;
    }

    public Map<String, Object> preview(Long roomId, List<String> billMonths, List<String> feeCategories) {
        AuthUser staff = StaffGuard.requireStaff();
        Long cid = staff.getCommunityId();
        requireRoom(cid, roomId);
        List<QuoteLine> quotes = quoteLines(cid, roomId, billMonths, feeCategories);
        BigDecimal list = quotes.stream().map(q -> q.listAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roomId", roomId);
        data.put("listAmount", list);
        data.put("items", quotes.stream().map(q -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("billMonth", q.billMonth);
            m.put("feeCategory", q.feeCategory);
            m.put("listAmount", q.listAmount);
            return m;
        }).toList());
        data.put("note", "实付 cashAmount ≤ 原价 listAmount；差额为预缴优惠，不计入财务公开收入");
        return data;
    }

    @Transactional
    public Map<String, Object> createPlan(Long roomId, List<String> billMonths, List<String> feeCategories,
                                          BigDecimal cashAmount, String payChannel, String remark, boolean confirm) {
        AuthUser staff = StaffGuard.requireStaff();
        Long cid = staff.getCommunityId();
        requireRoom(cid, roomId);
        if (billMonths == null || billMonths.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "billMonths 不能为空");
        }
        if (feeCategories == null || feeCategories.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "feeCategories 不能为空");
        }
        if (cashAmount == null || cashAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "cashAmount 须为正数");
        }
        List<String> months = billMonths.stream().map(String::trim).distinct().sorted().toList();
        List<String> cats = feeCategories.stream().map(String::trim).distinct().toList();
        for (String m : months) {
            if (!m.matches("\\d{4}-\\d{2}")) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "billMonth 须为 YYYY-MM: " + m);
            }
        }
        List<QuoteLine> quotes = quoteLines(cid, roomId, months, cats);
        BigDecimal list = quotes.stream().map(q -> q.listAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal cash = cashAmount.setScale(2, RoundingMode.HALF_UP);
        if (cash.compareTo(list) > 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "实付不能大于覆盖原价合计 " + list);
        }
        for (QuoteLine q : quotes) {
            if (isCovered(cid, roomId, q.billMonth, q.feeCategory)) {
                throw BizException.of(ErrorCodes.BAD_PARAM,
                        "已存在有效预缴覆盖：" + q.billMonth + "/" + q.feeCategory);
            }
        }
        // 已缴含覆盖费项则拒绝（须先冲红）
        billingService.assertNoPaidConflictForPrepaid(cid, roomId, months, cats);

        LocalDateTime now = LocalDateTime.now();
        PrepaidPlan plan = new PrepaidPlan();
        plan.setCommunityId(cid);
        plan.setRoomId(roomId);
        plan.setListAmount(list);
        plan.setCashAmount(cash);
        plan.setDiscountAmount(list.subtract(cash).setScale(2, RoundingMode.HALF_UP));
        plan.setStatus(confirm ? "ACTIVE" : "DRAFT");
        plan.setPayChannel(payChannel == null || payChannel.isBlank() ? "OTHER" : payChannel.trim());
        plan.setRemark(remark);
        plan.setCreatedBy(staff.getUserId());
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        if (confirm) {
            plan.setConfirmedBy(staff.getUserId());
            plan.setConfirmedAt(now);
        }
        planMapper.insert(plan);
        allocateAndInsertItems(plan, quotes, now);

        Map<String, Object> data = planView(plan);
        if (confirm) {
            Map<String, Object> adj = billingService.applyPrepaidCoverageToExistingBills(
                    cid, roomId, months, cats, plan.getId());
            data.put("billAdjust", adj);
            data.put("note", buildActiveNote(plan, adj));
        } else {
            data.put("note", "草稿已保存，确认后生效；若覆盖月有已缴账单须先冲红");
        }
        return data;
    }

    @Transactional
    public Map<String, Object> confirmPlan(Long planId) {
        AuthUser staff = StaffGuard.requireStaff();
        PrepaidPlan plan = requireStaffPlan(planId, staff.getCommunityId());
        if (!"DRAFT".equals(plan.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅草稿可确认");
        }
        List<PrepaidPlanItem> items = itemMapper.selectList(new LambdaQueryWrapper<PrepaidPlanItem>()
                .eq(PrepaidPlanItem::getPlanId, planId));
        for (PrepaidPlanItem it : items) {
            if (isCoveredExcludingPlan(plan.getCommunityId(), plan.getRoomId(), it.getBillMonth(),
                    it.getFeeCategory(), planId)) {
                throw BizException.of(ErrorCodes.BAD_PARAM,
                        "确认失败，与已有有效预缴重叠：" + it.getBillMonth() + "/" + it.getFeeCategory());
            }
        }
        List<String> months = items.stream().map(PrepaidPlanItem::getBillMonth).distinct().sorted().toList();
        List<String> cats = items.stream().map(PrepaidPlanItem::getFeeCategory).distinct().toList();
        billingService.assertNoPaidConflictForPrepaid(plan.getCommunityId(), plan.getRoomId(), months, cats);

        LocalDateTime now = LocalDateTime.now();
        plan.setStatus("ACTIVE");
        plan.setConfirmedBy(staff.getUserId());
        plan.setConfirmedAt(now);
        plan.setUpdatedAt(now);
        planMapper.updateById(plan);

        Map<String, Object> adj = billingService.applyPrepaidCoverageToExistingBills(
                plan.getCommunityId(), plan.getRoomId(), months, cats, plan.getId());
        Map<String, Object> data = planView(plan);
        data.put("billAdjust", adj);
        data.put("note", buildActiveNote(plan, adj));
        return data;
    }

    private String buildActiveNote(PrepaidPlan plan, Map<String, Object> adj) {
        int adjusted = adj == null ? 0 : ((Number) adj.getOrDefault("adjustedCount", 0)).intValue();
        int voided = adj == null ? 0 : ((Number) adj.getOrDefault("voidedCount", 0)).intValue();
        StringBuilder sb = new StringBuilder();
        sb.append("协议已生效：后续出账将跳过覆盖费项；实收按各账期分摊计入公开；优惠 ")
                .append(plan.getDiscountAmount()).append(" 不进收入。");
        if (adjusted > 0 || voided > 0) {
            sb.append(" 已自动调整未缴账单：改金额 ").append(adjusted)
                    .append(" 张、作废空单 ").append(voided).append(" 张。");
        } else {
            sb.append(" 覆盖月暂无未缴账单需调整。");
        }
        return sb.toString();
    }

    @Transactional
    public Map<String, Object> voidPlan(Long planId, String reason) {
        AuthUser staff = StaffGuard.requireStaff();
        PrepaidPlan plan = requireStaffPlan(planId, staff.getCommunityId());
        if ("VOID".equals(plan.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "协议已作废");
        }
        plan.setStatus("VOID");
        plan.setRemark((plan.getRemark() == null ? "" : plan.getRemark() + " | ")
                + "作废:" + (reason == null || reason.isBlank() ? "无" : reason.trim()));
        plan.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(plan);
        Map<String, Object> data = planView(plan);
        data.put("note", "作废后不再跳过出账；已改过的未缴账单不会自动加回费用（请单房重出/重新生成）；"
                + "已按账期计入公开的实收不自动回冲；失效记录可删除");
        return data;
    }

    /** 仅允许删除已作废或草稿协议（物理删头+明细）；ACTIVE 须先作废 */
    @Transactional
    public Map<String, Object> deletePlan(Long planId) {
        AuthUser staff = StaffGuard.requireStaff();
        PrepaidPlan plan = requireStaffPlan(planId, staff.getCommunityId());
        if ("ACTIVE".equals(plan.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "生效中的协议请先作废再删除");
        }
        if (!"VOID".equals(plan.getStatus()) && !"DRAFT".equals(plan.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅已作废或草稿协议可删除");
        }
        itemMapper.delete(new LambdaQueryWrapper<PrepaidPlanItem>()
                .eq(PrepaidPlanItem::getPlanId, planId));
        planMapper.deleteById(planId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", planId);
        data.put("deleted", true);
        data.put("note", "协议及明细已删除；不影响已计入公开的历史实收");
        return data;
    }

    public List<Map<String, Object>> listStaffPlans(Long roomId) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<PrepaidPlan> q = new LambdaQueryWrapper<PrepaidPlan>()
                .eq(PrepaidPlan::getCommunityId, cid)
                .orderByDesc(PrepaidPlan::getId);
        if (roomId != null) {
            q.eq(PrepaidPlan::getRoomId, roomId);
        }
        return planMapper.selectList(q).stream().map(this::planView).toList();
    }

    public Map<String, Object> getStaffPlan(Long planId) {
        return planView(requireStaffPlan(planId, StaffGuard.communityId()));
    }

    public Map<String, Object> residentOverview(Long roomId) {
        AuthUser u = AuthContext.require();
        if (!"RESIDENT".equals(u.getIdentityType()) || u.getCommunityId() == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换为住户身份");
        }
        Long cid = u.getCommunityId();
        List<Long> roomIds;
        if (roomId != null) {
            assertResidentRoom(u, roomId);
            roomIds = List.of(roomId);
        } else {
            roomIds = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                            .eq(RoomOccupant::getUserId, u.getUserId())
                            .eq(RoomOccupant::getCommunityId, cid)
                            .eq(RoomOccupant::getStatus, "ACTIVE"))
                    .stream()
                    .map(RoomOccupant::getRoomId)
                    .filter(id -> id != null)
                    .distinct()
                    .toList();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("prepaidEnabled", true);
        data.put("prepaidGuideText", null);
        if (roomIds.isEmpty()) {
            data.put("plans", List.of());
            return data;
        }
        data.put("plans", planMapper.selectList(new LambdaQueryWrapper<PrepaidPlan>()
                        .eq(PrepaidPlan::getCommunityId, cid)
                        .in(PrepaidPlan::getRoomId, roomIds)
                        .in(PrepaidPlan::getStatus, "ACTIVE", "VOID")
                        .orderByDesc(PrepaidPlan::getId))
                .stream().map(this::planView).toList());
        return data;
    }

    private boolean isCoveredExcludingPlan(Long communityId, Long roomId, String billMonth,
                                           String feeCategory, Long excludePlanId) {
        List<PrepaidPlanItem> items = itemMapper.selectList(new LambdaQueryWrapper<PrepaidPlanItem>()
                .eq(PrepaidPlanItem::getCommunityId, communityId)
                .eq(PrepaidPlanItem::getRoomId, roomId)
                .eq(PrepaidPlanItem::getBillMonth, billMonth)
                .eq(PrepaidPlanItem::getFeeCategory, feeCategory)
                .ne(PrepaidPlanItem::getPlanId, excludePlanId));
        if (items.isEmpty()) {
            return false;
        }
        Set<Long> planIds = items.stream().map(PrepaidPlanItem::getPlanId).collect(Collectors.toSet());
        return planMapper.selectCount(new LambdaQueryWrapper<PrepaidPlan>()
                .in(PrepaidPlan::getId, planIds)
                .eq(PrepaidPlan::getStatus, "ACTIVE")) > 0;
    }

    private void allocateAndInsertItems(PrepaidPlan plan, List<QuoteLine> quotes, LocalDateTime now) {
        BigDecimal list = plan.getListAmount();
        BigDecimal cash = plan.getCashAmount();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < quotes.size(); i++) {
            QuoteLine q = quotes.get(i);
            BigDecimal share;
            if (i == quotes.size() - 1) {
                share = cash.subtract(allocated).setScale(2, RoundingMode.HALF_UP);
            } else if (list.compareTo(BigDecimal.ZERO) <= 0) {
                share = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            } else {
                share = cash.multiply(q.listAmount).divide(list, 2, RoundingMode.HALF_UP);
                allocated = allocated.add(share);
            }
            PrepaidPlanItem it = new PrepaidPlanItem();
            it.setPlanId(plan.getId());
            it.setCommunityId(plan.getCommunityId());
            it.setRoomId(plan.getRoomId());
            it.setBillMonth(q.billMonth);
            it.setFeeCategory(q.feeCategory);
            it.setListAmount(q.listAmount);
            it.setCashAmount(share);
            it.setCreatedAt(now);
            itemMapper.insert(it);
        }
    }

    private List<QuoteLine> quoteLines(Long communityId, Long roomId, List<String> months, List<String> cats) {
        Set<String> catSet = new LinkedHashSet<>(cats);
        List<QuoteLine> out = new ArrayList<>();
        for (String month : months) {
            Map<String, BigDecimal> byCat = billingService.quoteCategoryAmounts(communityId, roomId, month, catSet);
            for (String cat : catSet) {
                // 已有账单明细则以账单金额为原价（与住户所见一致）
                BigDecimal fromBill = billingService.existingBillLineAmount(communityId, roomId, month, cat);
                BigDecimal amt = fromBill != null ? fromBill : byCat.get(cat);
                if (amt == null || amt.compareTo(BigDecimal.ZERO) <= 0) {
                    throw BizException.of(ErrorCodes.BAD_PARAM,
                            month + " 费项 " + cat + " 原价无法计算或为 0（检查费项启用与房屋数据）");
                }
                out.add(new QuoteLine(month, cat, amt.setScale(2, RoundingMode.HALF_UP)));
            }
        }
        return out;
    }

    private PrepaidPlan requireStaffPlan(Long planId, Long communityId) {
        PrepaidPlan plan = planMapper.selectById(planId);
        if (plan == null || !communityId.equals(plan.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "预缴协议不存在");
        }
        return plan;
    }

    private Map<String, Object> planView(PrepaidPlan plan) {
        List<PrepaidPlanItem> items = itemMapper.selectList(new LambdaQueryWrapper<PrepaidPlanItem>()
                .eq(PrepaidPlanItem::getPlanId, plan.getId())
                .orderByAsc(PrepaidPlanItem::getBillMonth)
                .orderByAsc(PrepaidPlanItem::getFeeCategory));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", plan.getId());
        data.put("communityId", plan.getCommunityId());
        data.put("roomId", plan.getRoomId());
        Room room = roomMapper.selectById(plan.getRoomId());
        data.put("roomNo", room == null ? null : room.getRoomNo());
        String path = room == null ? "" : occupantQueryService.buildAddress(room);
        data.put("roomLabel", path.isEmpty() ? ("房#" + plan.getRoomId()) : path);
        data.put("listAmount", plan.getListAmount());
        data.put("cashAmount", plan.getCashAmount());
        data.put("discountAmount", plan.getDiscountAmount());
        data.put("status", plan.getStatus());
        data.put("payChannel", plan.getPayChannel());
        data.put("remark", plan.getRemark());
        data.put("confirmedAt", plan.getConfirmedAt());
        data.put("createdAt", plan.getCreatedAt());
        data.put("items", items);
        data.put("billMonths", items.stream().map(PrepaidPlanItem::getBillMonth).distinct().toList());
        data.put("feeCategories", items.stream().map(PrepaidPlanItem::getFeeCategory).distinct().toList());
        return data;
    }

    private void requireRoom(Long communityId, Long roomId) {
        Room room = roomMapper.selectById(roomId);
        if (room == null || room.getDeletedAt() != null || !communityId.equals(room.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在");
        }
    }

    private void assertResidentRoom(AuthUser u, Long roomId) {
        long n = roomOccupantMapper.selectCount(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getUserId, u.getUserId())
                .eq(RoomOccupant::getRoomId, roomId)
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        if (n == 0) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "无权查看该房屋预缴");
        }
    }

    private record QuoteLine(String billMonth, String feeCategory, BigDecimal listAmount) {
    }
}
