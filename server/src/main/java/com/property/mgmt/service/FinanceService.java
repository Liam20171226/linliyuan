package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.common.RoomPaths;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.CommitteeGuard;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FinanceService {

    private final FinanceEntryMapper financeEntryMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final BillMapper billMapper;
    private final BillLineMapper billLineMapper;
    private final RoomMapper roomMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final FloorMapper floorMapper;
    private final PrepaidService prepaidService;
    private final AttachmentService attachmentService;

    // ---------- finance entry ----------

    @Transactional
    public FinanceEntry createEntry(String entryType, BigDecimal amount, LocalDate occurDate,
                                    String category, String title, String remark,
                                    List<Long> attachmentIds) {
        AuthUser u = StaffGuard.requireStaff();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "金额不能为 0");
        }
        String type;
        BigDecimal absAmount;
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            type = "EXPENSE";
            absAmount = amount.abs();
        } else {
            type = "INCOME";
            absAmount = amount;
        }
        // 兼容旧客户端显式传 entryType（金额须为正）
        if (entryType != null && !entryType.isBlank()) {
            if (!"INCOME".equals(entryType) && !"EXPENSE".equals(entryType)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "entryType 须为 INCOME 或 EXPENSE");
            }
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                type = entryType;
                absAmount = amount;
            }
        }
        if (occurDate == null || title == null || title.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "occurDate/title 必填");
        }
        String remarkVal = normalizeRemark(remark);
        LocalDateTime now = LocalDateTime.now();
        FinanceEntry e = new FinanceEntry();
        e.setCommunityId(u.getCommunityId());
        e.setEntryType(type);
        e.setAmount(money2(absAmount));
        e.setOccurDate(occurDate);
        e.setCategory(category);
        e.setTitle(title.trim());
        e.setRemark(remarkVal);
        e.setStatus("APPROVED");
        e.setCreatedBy(u.getUserId());
        e.setApprovedBy(u.getUserId());
        e.setApprovedAt(now);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        financeEntryMapper.insert(e);
        attachmentService.bindToBiz(attachmentIds, "FINANCE_ENTRY", e.getId(), u.getCommunityId(), u.getUserId());
        return e;
    }

    @Transactional
    public FinanceEntry updateEntry(Long id, String entryType, BigDecimal amount, LocalDate occurDate,
                                    String category, String title, String remark) {
        FinanceEntry e = requireStaffEntry(id);
        if (entryType != null) {
            if (!"INCOME".equals(entryType) && !"EXPENSE".equals(entryType)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "entryType 非法");
            }
            e.setEntryType(entryType);
        }
        if (amount != null) {
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "金额不能为 0");
            }
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                e.setEntryType("EXPENSE");
                e.setAmount(money2(amount.abs()));
            } else {
                if (entryType == null || entryType.isBlank()) {
                    e.setEntryType("INCOME");
                }
                e.setAmount(money2(amount));
            }
        }
        if (occurDate != null) {
            e.setOccurDate(occurDate);
        }
        if (category != null) {
            e.setCategory(category);
        }
        if (title != null && !title.isBlank()) {
            e.setTitle(title.trim());
        }
        if (remark != null) {
            e.setRemark(normalizeRemark(remark));
        }
        if ("PENDING".equals(e.getStatus()) || "REJECTED".equals(e.getStatus())) {
            e.setStatus("APPROVED");
            e.setApprovedBy(StaffGuard.requireStaff().getUserId());
            e.setApprovedAt(LocalDateTime.now());
        }
        e.setUpdatedAt(LocalDateTime.now());
        financeEntryMapper.updateById(e);
        return e;
    }

    @Transactional
    public void deleteEntry(Long id) {
        FinanceEntry e = requireStaffEntry(id);
        for (Attachment a : attachmentService.listByBiz("FINANCE_ENTRY", e.getId())) {
            try {
                attachmentService.delete(a.getId());
            } catch (BizException ignored) {
                // 附件删失败不阻塞主记录删除
            }
        }
        financeEntryMapper.deleteById(e.getId());
    }

    /** @deprecated 已取消审核；保留接口兼容，直接视为已生效 */
    @Transactional
    public FinanceEntry approve(Long id) {
        FinanceEntry e = requireStaffEntry(id);
        if (!"APPROVED".equals(e.getStatus())) {
            AuthUser u = StaffGuard.requireStaff();
            e.setStatus("APPROVED");
            e.setApprovedBy(u.getUserId());
            e.setApprovedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            financeEntryMapper.updateById(e);
        }
        return e;
    }

    public FinanceEntry reject(Long id, String reason) {
        String tip = (reason == null || reason.isBlank()) ? "" : ("：" + reason.trim());
        throw BizException.of(ErrorCodes.BAD_PARAM, "收支登记无需审核，请直接删除" + tip);
    }

    public Map<String, Object> listEntries(LocalDate from, LocalDate to, String status, int page, int pageSize) {
        Long cid = StaffGuard.communityId();
        migratePendingEntries(cid);
        LambdaQueryWrapper<FinanceEntry> q = new LambdaQueryWrapper<FinanceEntry>()
                .eq(FinanceEntry::getCommunityId, cid)
                .orderByDesc(FinanceEntry::getId);
        if (from != null) {
            q.ge(FinanceEntry::getOccurDate, from);
        }
        if (to != null) {
            q.le(FinanceEntry::getOccurDate, to);
        }
        if (status != null && !status.isBlank()) {
            q.eq(FinanceEntry::getStatus, status);
        }
        Page<FinanceEntry> p = financeEntryMapper.selectPage(new Page<>(page, pageSize), q);
        List<Map<String, Object>> list = new ArrayList<>();
        for (FinanceEntry e : p.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("communityId", e.getCommunityId());
            row.put("entryType", e.getEntryType());
            row.put("amount", money2(e.getAmount()));
            row.put("occurDate", e.getOccurDate());
            row.put("category", e.getCategory());
            row.put("title", e.getTitle());
            row.put("remark", e.getRemark());
            row.put("status", e.getStatus());
            row.put("createdAt", e.getCreatedAt());
            List<Map<String, Object>> files = new ArrayList<>();
            for (Attachment a : attachmentService.listByBiz("FINANCE_ENTRY", e.getId())) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", a.getId());
                f.put("fileName", a.getFileName());
                f.put("url", a.getUrl());
                f.put("contentType", a.getContentType());
                files.add(f);
            }
            row.put("attachments", files);
            list.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    public Map<String, Object> summary(Long communityId, LocalDate from, LocalDate to) {
        return summary(communityId, from, to, "BY_BILL_MONTH");
    }

    /**
     * @param viewMode BY_BILL_MONTH（默认：按账单账期归属缴费净额）| BY_PAID_AT（兼容旧参数）
     */
    public Map<String, Object> summary(Long communityId, LocalDate from, LocalDate to, String viewMode) {
        if (communityId == null || from == null || to == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "communityId/from/to 必填");
        }
        migratePendingEntries(communityId);
        String mode = viewMode == null || viewMode.isBlank() ? "BY_BILL_MONTH" : viewMode.trim();
        if (!"BY_PAID_AT".equals(mode) && !"BY_BILL_MONTH".equals(mode)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "viewMode 须为 BY_PAID_AT 或 BY_BILL_MONTH");
        }

        List<PaymentRecord> payments;
        if ("BY_PAID_AT".equals(mode)) {
            // 非预缴：按 paid_at；预缴冲抵(PREPAID)：按账单账期（Q33 按月收缴公示）
            List<PaymentRecord> byPaidAt = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                    .eq(PaymentRecord::getCommunityId, communityId)
                    .and(w -> w.isNull(PaymentRecord::getPayChannel).or().ne(PaymentRecord::getPayChannel, "PREPAID"))
                    .ge(PaymentRecord::getPaidAt, from.atStartOfDay())
                    .le(PaymentRecord::getPaidAt, to.plusDays(1).atStartOfDay().minusNanos(1)));
            String fromMonth = String.format("%04d-%02d", from.getYear(), from.getMonthValue());
            String toMonth = String.format("%04d-%02d", to.getYear(), to.getMonthValue());
            List<Bill> prepaidBills = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                    .eq(Bill::getCommunityId, communityId)
                    .ge(Bill::getBillMonth, fromMonth)
                    .le(Bill::getBillMonth, toMonth));
            Set<Long> prepaidBillIds = prepaidBills.stream().map(Bill::getId).collect(java.util.stream.Collectors.toSet());
            List<PaymentRecord> prepaidPays = prepaidBillIds.isEmpty() ? List.of()
                    : paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                    .eq(PaymentRecord::getCommunityId, communityId)
                    .eq(PaymentRecord::getPayChannel, "PREPAID")
                    .in(PaymentRecord::getBillId, prepaidBillIds));
            payments = new ArrayList<>(byPaidAt);
            payments.addAll(prepaidPays);
        } else {
            String fromMonth = String.format("%04d-%02d", from.getYear(), from.getMonthValue());
            String toMonth = String.format("%04d-%02d", to.getYear(), to.getMonthValue());
            List<Bill> bills = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                    .eq(Bill::getCommunityId, communityId)
                    .ge(Bill::getBillMonth, fromMonth)
                    .le(Bill::getBillMonth, toMonth));
            Set<Long> billIds = bills.stream().map(Bill::getId).collect(java.util.stream.Collectors.toSet());
            if (billIds.isEmpty()) {
                payments = List.of();
            } else {
                payments = paymentRecordMapper.selectList(new LambdaQueryWrapper<PaymentRecord>()
                        .eq(PaymentRecord::getCommunityId, communityId)
                        .in(PaymentRecord::getBillId, billIds));
            }
        }
        BigDecimal paySum = payments.stream().map(PaymentRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String fromMonth = String.format("%04d-%02d", from.getYear(), from.getMonthValue());
        String toMonth = String.format("%04d-%02d", to.getYear(), to.getMonthValue());
        // 方案 A：ACTIVE 预缴协议按账期分摊的实收（折扣不在此列）
        BigDecimal prepaidCash = prepaidService.sumCashByBillMonthRange(communityId, fromMonth, toMonth);
        paySum = paySum.add(prepaidCash);

        List<FinanceEntry> entries = financeEntryMapper.selectList(new LambdaQueryWrapper<FinanceEntry>()
                .eq(FinanceEntry::getCommunityId, communityId)
                .eq(FinanceEntry::getStatus, "APPROVED")
                .ge(FinanceEntry::getOccurDate, from)
                .le(FinanceEntry::getOccurDate, to));
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (FinanceEntry e : entries) {
            if ("INCOME".equals(e.getEntryType())) {
                income = income.add(e.getAmount());
            } else if ("EXPENSE".equals(e.getEntryType())) {
                expense = expense.add(e.getAmount());
            }
        }
        BigDecimal totalIncome = paySum.add(income);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("income", money2(totalIncome));
        data.put("expense", money2(expense));
        data.put("balance", money2(totalIncome.subtract(expense)));
        data.put("paymentIncome", money2(paySum));
        data.put("prepaidIncome", money2(prepaidCash));
        data.put("entryIncome", money2(income));
        data.put("from", from);
        data.put("to", to);
        data.put("viewMode", mode);
        data.put("disclaimer",
                "上面三个数字按账单月份统计：该月发出的账单，只要已经交清（不论哪天交的），都算进本期收入；"
                        + "另含预缴按月摊入的实付款、已登记的其他收入；减去已登记的支出。"
                        + "还没交的账单、打折优惠都不计入。");

        // 收入构成：按费项（仅正数缴费对应账单的明细按比例分摊净额较复杂；简化为已缴账单明细合计×账单净缴费/账单总额）
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        Set<Long> paidBillIds = payments.stream()
                .filter(p -> p.getAmount() != null && p.getAmount().compareTo(BigDecimal.ZERO) != 0)
                .map(PaymentRecord::getBillId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, BigDecimal> netByBill = new LinkedHashMap<>();
        for (PaymentRecord p : payments) {
            netByBill.merge(p.getBillId(), p.getAmount(), BigDecimal::add);
        }
        if (!paidBillIds.isEmpty()) {
            List<BillLine> allLines = billLineMapper.selectList(new LambdaQueryWrapper<BillLine>()
                    .in(BillLine::getBillId, paidBillIds));
            Map<Long, List<BillLine>> linesByBill = allLines.stream()
                    .collect(java.util.stream.Collectors.groupingBy(BillLine::getBillId));
            for (Map.Entry<Long, BigDecimal> e : netByBill.entrySet()) {
                List<BillLine> ls = linesByBill.getOrDefault(e.getKey(), List.of());
                BigDecimal billTotal = ls.stream().map(BillLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (billTotal.compareTo(BigDecimal.ZERO) <= 0) {
                    byCategory.merge("OTHER::未分类", e.getValue(), BigDecimal::add);
                    continue;
                }
                for (BillLine line : ls) {
                    BigDecimal share = line.getAmount().multiply(e.getValue())
                            .divide(billTotal, 2, java.math.RoundingMode.HALF_UP);
                    byCategory.merge(breakdownKey(line), share, BigDecimal::add);
                }
            }
        }
        Map<String, BigDecimal> prepaidByCat = new LinkedHashMap<>(
                prepaidService.sumCashByFeeCategory(communityId, fromMonth, toMonth));
        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (String cat : PRIMARY_FEE_ORDER) {
            BigDecimal paid = byCategory.remove(cat);
            BigDecimal prepaid = prepaidByCat.remove(cat);
            if (paid == null) paid = BigDecimal.ZERO;
            if (prepaid == null) prepaid = BigDecimal.ZERO;
            if (paid.compareTo(BigDecimal.ZERO) == 0 && prepaid.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            breakdown.add(breakdownRow(cat, feeCategoryZh(cat), paid, prepaid));
        }
        List<String> otherKeys = byCategory.keySet().stream()
                .filter(k -> k.startsWith("OTHER::"))
                .sorted()
                .toList();
        BigDecimal otherPrepaid = prepaidByCat.remove("OTHER");
        if (otherPrepaid == null) {
            otherPrepaid = BigDecimal.ZERO;
        }
        for (String key : otherKeys) {
            BigDecimal paid = byCategory.remove(key);
            if (paid == null || paid.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            breakdown.add(breakdownRow("OTHER", breakdownLabel(key), paid, BigDecimal.ZERO));
        }
        if (otherPrepaid.compareTo(BigDecimal.ZERO) != 0) {
            breakdown.add(breakdownRow("OTHER", "其他", BigDecimal.ZERO, otherPrepaid));
        }
        for (Map.Entry<String, BigDecimal> e : byCategory.entrySet()) {
            if (e.getValue() == null || e.getValue().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            BigDecimal prepaid = prepaidByCat.remove(e.getKey());
            if (prepaid == null) prepaid = BigDecimal.ZERO;
            breakdown.add(breakdownRow(e.getKey(), breakdownLabel(e.getKey()), e.getValue(), prepaid));
        }
        for (Map.Entry<String, BigDecimal> e : prepaidByCat.entrySet()) {
            if (e.getValue() == null || e.getValue().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            breakdown.add(breakdownRow(e.getKey(), feeCategoryZh(e.getKey()), BigDecimal.ZERO, e.getValue()));
        }
        // 业主缴交构成：不含「其他经营收入」登记项
        data.put("incomeBreakdown", breakdown);

        List<Map<String, Object>> otherIncomeEntries = new ArrayList<>();
        entries.stream()
                .filter(e -> "INCOME".equals(e.getEntryType()))
                .sorted(java.util.Comparator
                        .comparing(FinanceEntry::getOccurDate, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(FinanceEntry::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", e.getId());
                    row.put("title", e.getTitle());
                    row.put("amount", money2(e.getAmount()));
                    row.put("occurDate", e.getOccurDate() != null ? e.getOccurDate().toString() : null);
                    row.put("remark", e.getRemark());
                    List<Map<String, Object>> files = new ArrayList<>();
                    for (Attachment a : attachmentService.listByBiz("FINANCE_ENTRY", e.getId())) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("id", a.getId());
                        f.put("fileName", a.getFileName());
                        f.put("url", a.getUrl());
                        files.add(f);
                    }
                    row.put("attachments", files);
                    otherIncomeEntries.add(row);
                });
        data.put("otherIncomeEntries", otherIncomeEntries);

        List<Map<String, Object>> expenseBreakdown = new ArrayList<>();
        entries.stream()
                .filter(e -> "EXPENSE".equals(e.getEntryType()))
                .sorted(java.util.Comparator
                        .comparing(FinanceEntry::getOccurDate, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(FinanceEntry::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .forEach(e -> {
                    String title = e.getTitle() == null || e.getTitle().isBlank() ? "未命名支出" : e.getTitle().trim();
                    int month = e.getOccurDate() != null ? e.getOccurDate().getMonthValue() : 0;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", e.getId());
                    row.put("label", month > 0 ? (month + "月-" + title) : title);
                    row.put("title", title);
                    row.put("month", month);
                    row.put("occurDate", e.getOccurDate() != null ? e.getOccurDate().toString() : null);
                    row.put("amount", money2(e.getAmount()));
                    row.put("remark", e.getRemark());
                    List<Map<String, Object>> files = new ArrayList<>();
                    for (Attachment a : attachmentService.listByBiz("FINANCE_ENTRY", e.getId())) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("id", a.getId());
                        f.put("fileName", a.getFileName());
                        f.put("url", a.getUrl());
                        files.add(f);
                    }
                    row.put("attachments", files);
                    expenseBreakdown.add(row);
                });
        data.put("expenseBreakdown", expenseBreakdown);

        Set<Long> detailBillIds = payments.stream()
                .map(PaymentRecord::getBillId)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, Bill> billById = new LinkedHashMap<>();
        if (!detailBillIds.isEmpty()) {
            for (Bill b : billMapper.selectList(new LambdaQueryWrapper<Bill>().in(Bill::getId, detailBillIds))) {
                billById.put(b.getId(), b);
            }
        }

        Set<Long> detailRoomIds = new java.util.LinkedHashSet<>();
        for (PaymentRecord p : payments) {
            if (p.getRoomId() != null) {
                detailRoomIds.add(p.getRoomId());
            }
            Bill bill = billById.get(p.getBillId());
            if (bill != null && bill.getRoomId() != null) {
                detailRoomIds.add(bill.getRoomId());
            }
        }
        Map<Long, String> roomLabelById = loadRoomLabels(detailRoomIds);

        Map<Long, List<BillLine>> linesByDetailBill = new LinkedHashMap<>();
        if (!detailBillIds.isEmpty()) {
            List<BillLine> detailLines = billLineMapper.selectList(new LambdaQueryWrapper<BillLine>()
                    .in(BillLine::getBillId, detailBillIds)
                    .orderByAsc(BillLine::getId));
            linesByDetailBill = detailLines.stream()
                    .collect(java.util.stream.Collectors.groupingBy(BillLine::getBillId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        }

        List<Map<String, Object>> paymentDetails = new ArrayList<>();
        for (PaymentRecord p : payments) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("billId", p.getBillId());
            Bill bill = billById.get(p.getBillId());
            Long roomId = p.getRoomId() != null ? p.getRoomId() : (bill == null ? null : bill.getRoomId());
            row.put("roomId", roomId);
            row.put("amount", money2(p.getAmount()));
            row.put("payChannel", p.getPayChannel());
            row.put("paidAt", p.getPaidAt());
            row.put("remark", p.getRemark());
            row.put("billMonth", bill == null ? null : bill.getBillMonth());
            row.put("roomNo", roomNoOf(roomId, roomLabelById));
            row.put("roomLabel", roomLabelById.getOrDefault(roomId, "—"));
            List<BillLine> lines = linesByDetailBill.getOrDefault(p.getBillId(), List.of());
            String snapLabel = p.getFeeTypeLabel();
            String snapCats = p.getFeeCategories();
            // 优先按账单明细的一级大类展示（垃圾费等）；快照仅作兜底
            if (!lines.isEmpty() && feeLinesMatchPayment(lines, p.getAmount())) {
                List<String> feeCategories = lines.stream()
                        .map(FinanceService::resolveFeeCategory)
                        .filter(c -> c != null && !c.isBlank())
                        .distinct()
                        .toList();
                row.put("feeCategories", feeCategories);
                row.put("feeTypeLabel", feeTypeLabelOf(lines));
            } else if (snapCats != null && !snapCats.isBlank()) {
                List<String> feeCategories = java.util.Arrays.stream(snapCats.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                row.put("feeCategories", feeCategories);
                row.put("feeTypeLabel", feeCategories.stream()
                        .map(FinanceService::feeCategoryZh)
                        .distinct()
                        .reduce((a, b) -> a + "、" + b)
                        .orElse("—"));
            } else if (snapLabel != null && !snapLabel.isBlank()) {
                row.put("feeTypeLabel", snapLabel);
                row.put("feeCategories", List.of());
            } else {
                row.put("feeCategories", List.of());
                row.put("feeTypeLabel", "原账单已调整");
            }
            String kind = paymentKind(p);
            row.put("kind", kind);
            row.put("kindLabel", paymentKindLabel(kind));
            paymentDetails.add(row);
        }

        List<PrepaidService.PrepaidCashRow> prepaidRows =
                prepaidService.listCashRowsByBillMonthRange(communityId, fromMonth, toMonth);
        Set<Long> prepaidRoomIds = prepaidRows.stream()
                .map(r -> r.roomId)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (!prepaidRoomIds.isEmpty()) {
            Map<Long, String> moreLabels = loadRoomLabels(prepaidRoomIds);
            moreLabels.forEach(roomLabelById::putIfAbsent);
        }
        for (PrepaidService.PrepaidCashRow pr : prepaidRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "prepaid-" + pr.itemId);
            row.put("billId", null);
            row.put("roomId", pr.roomId);
            row.put("amount", money2(pr.cashAmount));
            row.put("payChannel", pr.payChannel);
            LocalDateTime at = pr.confirmedAt != null ? pr.confirmedAt : pr.createdAt;
            row.put("paidAt", at);
            row.put("remark", "预缴按账期确收");
            row.put("billMonth", pr.billMonth);
            row.put("roomNo", roomNoOf(pr.roomId, roomLabelById));
            row.put("roomLabel", roomLabelById.getOrDefault(pr.roomId, "—"));
            row.put("feeCategories", List.of(pr.feeCategory));
            row.put("feeTypeLabel", feeCategoryZh(pr.feeCategory));
            row.put("kind", "PREPAID_CONFIRM");
            row.put("kindLabel", "预缴确认");
            paymentDetails.add(row);
        }

        paymentDetails.sort((a, b) -> {
            String ma = a.get("billMonth") == null ? "" : String.valueOf(a.get("billMonth"));
            String mb = b.get("billMonth") == null ? "" : String.valueOf(b.get("billMonth"));
            int cmpMonth = mb.compareTo(ma);
            if (cmpMonth != 0) {
                return cmpMonth;
            }
            LocalDateTime ta = (LocalDateTime) a.get("paidAt");
            LocalDateTime tb = (LocalDateTime) b.get("paidAt");
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });
        data.put("paymentDetails", paymentDetails);
        return data;
    }

    private static BigDecimal money2(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String normalizeRemark(String remark) {
        if (remark == null) {
            return null;
        }
        String t = remark.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > 200) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "备注最多 200 字");
        }
        return t;
    }

    private Map<Long, String> loadRoomLabels(Set<Long> roomIds) {
        Map<Long, String> labels = new LinkedHashMap<>();
        if (roomIds == null || roomIds.isEmpty()) {
            return labels;
        }
        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>().in(Room::getId, roomIds));
        Map<Long, Room> roomById = new LinkedHashMap<>();
        Set<Long> buildingIds = new java.util.LinkedHashSet<>();
        Set<Long> unitIds = new java.util.LinkedHashSet<>();
        Set<Long> floorIds = new java.util.LinkedHashSet<>();
        for (Room r : rooms) {
            roomById.put(r.getId(), r);
            if (r.getBuildingId() != null) {
                buildingIds.add(r.getBuildingId());
            }
            if (r.getUnitId() != null) {
                unitIds.add(r.getUnitId());
            }
            if (r.getFloorId() != null) {
                floorIds.add(r.getFloorId());
            }
        }
        Map<Long, String> buildingName = new LinkedHashMap<>();
        if (!buildingIds.isEmpty()) {
            for (Building b : buildingMapper.selectList(new LambdaQueryWrapper<Building>().in(Building::getId, buildingIds))) {
                buildingName.put(b.getId(), b.getName() == null ? "" : b.getName());
            }
        }
        Map<Long, String> unitName = new LinkedHashMap<>();
        if (!unitIds.isEmpty()) {
            for (Unit u : unitMapper.selectList(new LambdaQueryWrapper<Unit>().in(Unit::getId, unitIds))) {
                unitName.put(u.getId(), u.getName() == null ? "" : u.getName());
            }
        }
        Map<Long, String> floorName = new LinkedHashMap<>();
        if (!floorIds.isEmpty()) {
            for (Floor f : floorMapper.selectList(new LambdaQueryWrapper<Floor>().in(Floor::getId, floorIds))) {
                String n = f.getName() != null && !f.getName().isBlank()
                        ? f.getName()
                        : (f.getFloorNo() == null ? "" : String.valueOf(f.getFloorNo()));
                floorName.put(f.getId(), n);
            }
        }
        for (Long id : roomIds) {
            Room room = roomById.get(id);
            if (room == null) {
                continue;
            }
            String path = RoomPaths.format(
                    buildingName.getOrDefault(room.getBuildingId(), ""),
                    unitName.getOrDefault(room.getUnitId(), ""),
                    floorName.getOrDefault(room.getFloorId(), ""),
                    room.getRoomNo() == null ? "" : room.getRoomNo());
            if (!path.isEmpty()) {
                labels.put(id, path);
            } else if (room.getRoomNo() != null && !room.getRoomNo().isBlank()) {
                labels.put(id, room.getRoomNo());
            }
        }
        return labels;
    }

    private static boolean feeLinesMatchPayment(List<BillLine> lines, BigDecimal paymentAmount) {
        if (lines == null || lines.isEmpty() || paymentAmount == null) {
            return false;
        }
        BigDecimal lineSum = lines.stream()
                .map(BillLine::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs();
        return lineSum.compareTo(paymentAmount.abs()) == 0;
    }

    private static Map<String, Object> breakdownRow(String feeCategory, String label,
                                                     BigDecimal paid, BigDecimal prepaid) {
        BigDecimal p = paid == null ? BigDecimal.ZERO : paid;
        BigDecimal pre = prepaid == null ? BigDecimal.ZERO : prepaid;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("feeCategory", feeCategory);
        row.put("label", label);
        row.put("paidAmount", money2(p));
        row.put("prepaidAmount", money2(pre));
        row.put("amount", money2(p.add(pre)));
        return row;
    }

    private static final List<String> PRIMARY_FEE_ORDER = List.of(
            "PROPERTY_FEE", "PARKING_MGMT", "PARKING_MONTHLY", "SHARED",
            "GARBAGE", "WATER", "ELECTRIC", "GAS"
    );

    /** 收入构成分组键：一级大类；其他大类按二级名称拆分。 */
    private static String breakdownKey(BillLine line) {
        String resolved = resolveFeeCategory(line);
        if ("OTHER".equals(resolved)) {
            String title = secondaryOtherName(line);
            return "OTHER::" + title;
        }
        return resolved;
    }

    /**
     * 归一到九大类编码。历史数据若把「垃圾费」等误记在 OTHER，按名称归回一级大类。
     */
    private static String resolveFeeCategory(BillLine line) {
        if (line == null) {
            return "OTHER";
        }
        String cat = line.getFeeCategory();
        if (cat != null && !cat.isBlank() && !"OTHER".equals(cat)) {
            return cat.trim();
        }
        String mapped = titleToPrimaryCategory(line.getTitle());
        if (mapped != null) {
            return mapped;
        }
        return (cat == null || cat.isBlank()) ? "OTHER" : cat.trim();
    }

    private static String titleToPrimaryCategory(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return null;
        }
        String t = rawTitle.trim();
        if (t.startsWith("其他-") || t.startsWith("其他：") || t.startsWith("其他:")) {
            t = t.replaceFirst("^其他[-：:]", "").trim();
        }
        return switch (t) {
            case "垃圾费" -> "GARBAGE";
            case "物业管理费", "物业费" -> "PROPERTY_FEE";
            case "车位管理费" -> "PARKING_MGMT";
            case "车辆月保费", "月保费" -> "PARKING_MONTHLY";
            case "公摊费" -> "SHARED";
            case "代收水费", "水费" -> "WATER";
            case "代收电费", "电费" -> "ELECTRIC";
            case "代收煤气费", "煤气费" -> "GAS";
            default -> null;
        };
    }

    private static String secondaryOtherName(BillLine line) {
        String title = line.getTitle() == null ? "" : line.getTitle().trim();
        if (title.isBlank()) {
            return "未命名";
        }
        if (title.startsWith("其他-") || title.startsWith("其他：") || title.startsWith("其他:")) {
            title = title.replaceFirst("^其他[-：:]", "").trim();
            if (title.isBlank()) {
                return "未命名";
            }
        }
        return title;
    }

    private static String breakdownLabel(String key) {
        if (key == null || key.isBlank()) {
            return "—";
        }
        if (key.startsWith("OTHER::")) {
            return "其他-" + key.substring("OTHER::".length());
        }
        if ("OTHER".equals(key)) {
            return "其他";
        }
        if ("PREPAID_INCOME".equals(key)) {
            return "预缴摊入";
        }
        if ("ENTRY_INCOME".equals(key)) {
            return "其他经营收入";
        }
        return feeCategoryZh(key);
    }

    private static String feeTypeLabelOf(List<BillLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "—";
        }
        List<String> labels = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (BillLine line : lines) {
            String label = lineDisplayLabel(line);
            if (label != null && !"—".equals(label) && seen.add(label)) {
                labels.add(label);
            }
        }
        return labels.isEmpty() ? "—" : String.join("、", labels);
    }

    /** 缴费明细：一律按一级大类标准名；其他大类为「其他-二级名」。 */
    private static String lineDisplayLabel(BillLine line) {
        if (line == null) {
            return "—";
        }
        String resolved = resolveFeeCategory(line);
        if ("OTHER".equals(resolved)) {
            return "其他-" + secondaryOtherName(line);
        }
        return feeCategoryZh(resolved);
    }

    private static String feeCategoryZh(String code) {
        if (code == null) {
            return "—";
        }
        return switch (code) {
            case "PROPERTY_FEE" -> "物业管理费";
            case "PARKING_MGMT" -> "车位管理费";
            case "PARKING_MONTHLY" -> "车辆月保费";
            case "SHARED" -> "公摊费";
            case "GARBAGE" -> "垃圾费";
            case "WATER" -> "代收水费";
            case "ELECTRIC" -> "代收电费";
            case "GAS" -> "代收煤气费";
            case "OTHER" -> "其他";
            default -> code;
        };
    }

    private static String roomNoOf(Long roomId, Map<Long, String> roomLabelById) {
        if (roomId == null) {
            return null;
        }
        return roomLabelById.get(roomId);
    }

    private static String paymentKind(PaymentRecord p) {
        BigDecimal amt = p.getAmount();
        String remark = p.getRemark() == null ? "" : p.getRemark();
        if (amt != null && amt.compareTo(BigDecimal.ZERO) < 0) {
            return "CREDIT";
        }
        if (remark.contains("补收") || "SUPPLEMENT".equalsIgnoreCase(p.getPayChannel())) {
            return "SUPPLEMENT";
        }
        return "RECEIVE";
    }

    private static String paymentKindLabel(String kind) {
        return switch (kind) {
            case "CREDIT" -> "冲红";
            case "SUPPLEMENT" -> "补收";
            case "PREPAID_CONFIRM" -> "预缴确认";
            default -> "收款";
        };
    }

    public Map<String, Object> summaryForCurrent(String identity, LocalDate from, LocalDate to, Long platformCommunityId) {
        return summaryForCurrent(identity, from, to, platformCommunityId, "BY_BILL_MONTH");
    }

    public Map<String, Object> summaryForCurrent(String identity, LocalDate from, LocalDate to,
                                                 Long platformCommunityId, String viewMode) {
        Long cid;
        if ("STAFF".equals(identity)) {
            cid = StaffGuard.communityId();
        } else if ("COMMITTEE".equals(identity)) {
            cid = CommitteeGuard.communityId();
        } else if ("RESIDENT".equals(identity)) {
            AuthUser u = AuthContext.require();
            if (!"RESIDENT".equals(u.getIdentityType()) || u.getCommunityId() == null) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换为住户身份");
            }
            cid = u.getCommunityId();
        } else {
            AuthUser u = AuthContext.require();
            if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台身份");
            }
            cid = platformCommunityId;
        }
        // 业主默认仅支付日视图；业委会/物业/平台可切账期
        String mode = viewMode;
        if ("RESIDENT".equals(identity) && mode != null && "BY_BILL_MONTH".equals(mode)
                && !"COMMITTEE".equals(identity)) {
            // 业主也可只读账期视图（便于看懂年付），允许
        }
        return summary(cid, from, to, mode);
    }

    /** 历史待审收支自动生效（已取消审核）。 */
    private void migratePendingEntries(Long communityId) {
        if (communityId == null) {
            return;
        }
        List<FinanceEntry> pending = financeEntryMapper.selectList(new LambdaQueryWrapper<FinanceEntry>()
                .eq(FinanceEntry::getCommunityId, communityId)
                .eq(FinanceEntry::getStatus, "PENDING"));
        if (pending.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (FinanceEntry e : pending) {
            e.setStatus("APPROVED");
            if (e.getApprovedAt() == null) {
                e.setApprovedAt(now);
            }
            e.setUpdatedAt(now);
            financeEntryMapper.updateById(e);
        }
    }

    private FinanceEntry requireStaffEntry(Long id) {
        FinanceEntry e = financeEntryMapper.selectById(id);
        if (e == null || !e.getCommunityId().equals(StaffGuard.communityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "收支记录不存在");
        }
        return e;
    }

    private static Map<String, Object> pageOf(Page<?> p, int page, int pageSize) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", p.getRecords());
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }
}
