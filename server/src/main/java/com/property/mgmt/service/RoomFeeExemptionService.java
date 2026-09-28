package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.FeeExemptionLedger;
import com.property.mgmt.domain.FeeItem;
import com.property.mgmt.domain.Room;
import com.property.mgmt.domain.RoomFeeExemption;
import com.property.mgmt.mapper.FeeExemptionLedgerMapper;
import com.property.mgmt.mapper.FeeItemMapper;
import com.property.mgmt.mapper.RoomFeeExemptionMapper;
import com.property.mgmt.mapper.RoomMapper;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RoomFeeExemptionService {

    private final RoomFeeExemptionMapper exemptionMapper;
    private final FeeExemptionLedgerMapper ledgerMapper;
    private final RoomMapper roomMapper;
    private final FeeItemMapper feeItemMapper;

    public List<RoomFeeExemption> list(Long roomId, String billMonth) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<RoomFeeExemption> q = new LambdaQueryWrapper<RoomFeeExemption>()
                .eq(RoomFeeExemption::getCommunityId, cid)
                .orderByDesc(RoomFeeExemption::getId);
        if (roomId != null) {
            q.eq(RoomFeeExemption::getRoomId, roomId);
        }
        List<RoomFeeExemption> all = exemptionMapper.selectList(q);
        if (billMonth == null || billMonth.isBlank()) {
            return all;
        }
        List<RoomFeeExemption> hit = new ArrayList<>();
        for (RoomFeeExemption e : all) {
            if (covers(e, billMonth)) {
                hit.add(e);
            }
        }
        return hit;
    }

    public List<FeeExemptionLedger> listLedger(String billMonth, Long roomId) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<FeeExemptionLedger> q = new LambdaQueryWrapper<FeeExemptionLedger>()
                .eq(FeeExemptionLedger::getCommunityId, cid)
                .orderByDesc(FeeExemptionLedger::getId);
        if (billMonth != null && !billMonth.isBlank()) {
            q.eq(FeeExemptionLedger::getBillMonth, billMonth);
        }
        if (roomId != null) {
            q.eq(FeeExemptionLedger::getRoomId, roomId);
        }
        return ledgerMapper.selectList(q);
    }

    @Transactional
    public RoomFeeExemption create(Long roomId, String feeCategory, Long feeItemId,
                                   String effectiveFrom, String effectiveTo, String reason) {
        AuthUser staff = StaffGuard.requireStaff();
        Long cid = staff.getCommunityId();
        if (roomId == null || feeCategory == null || feeCategory.isBlank()
                || effectiveFrom == null || !effectiveFrom.matches("\\d{4}-\\d{2}")) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "roomId/feeCategory/effectiveFrom(YYYY-MM) 必填");
        }
        if (effectiveTo != null && !effectiveTo.isBlank()) {
            if (!effectiveTo.matches("\\d{4}-\\d{2}")) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "effectiveTo 须为 YYYY-MM");
            }
            if (effectiveTo.compareTo(effectiveFrom) < 0) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "effectiveTo 不能早于 effectiveFrom");
            }
        } else {
            effectiveTo = null;
        }
        Room room = roomMapper.selectById(roomId);
        if (room == null || room.getDeletedAt() != null || !cid.equals(room.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在");
        }
        if (feeItemId != null) {
            FeeItem fi = feeItemMapper.selectById(feeItemId);
            if (fi == null || !cid.equals(fi.getCommunityId())) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "费项不存在");
            }
            if (!feeCategory.equals(fi.getFeeCategory())) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "feeItemId 与 feeCategory 不一致");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        RoomFeeExemption e = new RoomFeeExemption();
        e.setCommunityId(cid);
        e.setRoomId(roomId);
        e.setFeeCategory(feeCategory.trim());
        e.setFeeItemId(feeItemId);
        e.setEffectiveFrom(effectiveFrom);
        e.setEffectiveTo(effectiveTo);
        e.setReason(reason);
        e.setCreatedBy(staff.getUserId());
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        exemptionMapper.insert(e);
        return e;
    }

    @Transactional
    public void delete(Long id) {
        Long cid = StaffGuard.communityId();
        RoomFeeExemption e = exemptionMapper.selectById(id);
        if (e == null || !cid.equals(e.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "豁免配置不存在");
        }
        exemptionMapper.deleteById(id);
    }

    /** 该房该账期是否豁免某费项（category + 可选 feeItemId）。 */
    public boolean isExempt(Long communityId, Long roomId, String billMonth,
                            String feeCategory, Long feeItemId) {
        List<RoomFeeExemption> list = exemptionMapper.selectList(new LambdaQueryWrapper<RoomFeeExemption>()
                .eq(RoomFeeExemption::getCommunityId, communityId)
                .eq(RoomFeeExemption::getRoomId, roomId)
                .eq(RoomFeeExemption::getFeeCategory, feeCategory));
        for (RoomFeeExemption e : list) {
            if (!covers(e, billMonth)) {
                continue;
            }
            if (e.getFeeItemId() == null) {
                return true;
            }
            if (feeItemId != null && Objects.equals(e.getFeeItemId(), feeItemId)) {
                return true;
            }
        }
        return false;
    }

    public RoomFeeExemption findMatch(Long communityId, Long roomId, String billMonth,
                                      String feeCategory, Long feeItemId) {
        List<RoomFeeExemption> list = exemptionMapper.selectList(new LambdaQueryWrapper<RoomFeeExemption>()
                .eq(RoomFeeExemption::getCommunityId, communityId)
                .eq(RoomFeeExemption::getRoomId, roomId)
                .eq(RoomFeeExemption::getFeeCategory, feeCategory));
        for (RoomFeeExemption e : list) {
            if (!covers(e, billMonth)) {
                continue;
            }
            if (e.getFeeItemId() == null) {
                return e;
            }
            if (feeItemId != null && Objects.equals(e.getFeeItemId(), feeItemId)) {
                return e;
            }
        }
        return null;
    }

    public void writeLedger(Long communityId, Long roomId, String billMonth, Long billId,
                            RoomFeeExemption exemption, String feeCategory, Long feeItemId,
                            String title, BigDecimal amount) {
        FeeExemptionLedger row = new FeeExemptionLedger();
        row.setCommunityId(communityId);
        row.setRoomId(roomId);
        row.setBillMonth(billMonth);
        row.setBillId(billId);
        row.setExemptionId(exemption == null ? null : exemption.getId());
        row.setFeeCategory(feeCategory);
        row.setFeeItemId(feeItemId);
        row.setTitle(title);
        row.setAmount(amount == null ? BigDecimal.ZERO : amount);
        row.setCreatedAt(LocalDateTime.now());
        ledgerMapper.insert(row);
    }

    private static boolean covers(RoomFeeExemption e, String billMonth) {
        if (billMonth == null || e.getEffectiveFrom() == null) {
            return false;
        }
        if (billMonth.compareTo(e.getEffectiveFrom()) < 0) {
            return false;
        }
        return e.getEffectiveTo() == null || e.getEffectiveTo().isBlank()
                || billMonth.compareTo(e.getEffectiveTo()) <= 0;
    }
}
