package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.PublicRevenueItem;
import com.property.mgmt.mapper.PublicRevenueItemMapper;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.CommitteeGuard;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 公共收益：物业提交后直接 PUBLISHED（01-Q28），不计入经营汇总三数；业委会/业主只读查阅。
 */
@Service
@RequiredArgsConstructor
public class PublicRevenueService {

    private static final Pattern MONTH = Pattern.compile("^\\d{4}-\\d{2}$");

    private final PublicRevenueItemMapper publicRevenueItemMapper;

    @Transactional
    public PublicRevenueItem create(String title, BigDecimal amount, String occurMonth, String remark) {
        AuthUser u = StaffGuard.requireStaff();
        if (!StringUtils.hasText(title)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "title 必填");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "amount 须为正数");
        }
        String month = normalizeMonth(occurMonth);
        LocalDateTime now = LocalDateTime.now();
        PublicRevenueItem item = new PublicRevenueItem();
        item.setCommunityId(u.getCommunityId());
        item.setTitle(title.trim());
        item.setAmount(money2(amount));
        item.setOccurMonth(month);
        item.setRemark(blankToNull(remark));
        item.setStatus("PUBLISHED");
        item.setSubmittedBy(u.getUserId());
        item.setConfirmedBy(u.getUserId());
        item.setConfirmedAt(now);
        item.setRejectReason(null);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        publicRevenueItemMapper.insert(item);
        return item;
    }

    @Transactional
    public PublicRevenueItem update(Long id, String title, BigDecimal amount, String occurMonth, String remark) {
        PublicRevenueItem item = requireStaffItem(id);
        if (title != null) {
            if (!StringUtils.hasText(title)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "title 不能为空");
            }
            item.setTitle(title.trim());
        }
        if (amount != null) {
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "amount 须为正数");
            }
            item.setAmount(money2(amount));
        }
        if (occurMonth != null) {
            item.setOccurMonth(occurMonth.isBlank() ? null : normalizeMonth(occurMonth));
        }
        if (remark != null) {
            item.setRemark(blankToNull(remark));
        }
        item.setUpdatedAt(LocalDateTime.now());
        publicRevenueItemMapper.updateById(item);
        return item;
    }

    @Transactional
    public void delete(Long id) {
        PublicRevenueItem item = requireStaffItem(id);
        publicRevenueItemMapper.deleteById(item.getId());
    }

    public Map<String, Object> listStaff(String status, int page, int pageSize) {
        Long cid = StaffGuard.communityId();
        return pageList(cid, status, page, pageSize);
    }

    /** 住户/业主：仅已公示 */
    public Map<String, Object> listResident(int page, int pageSize) {
        AuthUser u = AuthContext.require();
        if (u.getCommunityId() == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先选择小区");
        }
        return pageList(u.getCommunityId(), "PUBLISHED", page, pageSize);
    }

    /** 业委会：只读已公示（无确认闸门） */
    public Map<String, Object> listCommittee(int page, int pageSize) {
        Long cid = CommitteeGuard.communityId();
        return pageList(cid, "PUBLISHED", page, pageSize);
    }

    public Map<String, Object> listPlatform(Long communityId, String status, int page, int pageSize) {
        AuthUser u = AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台权限");
        }
        return pageList(communityId, status, page, pageSize);
    }

    private Map<String, Object> pageList(Long communityId, String status, int page, int pageSize) {
        int p = Math.max(page, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        LambdaQueryWrapper<PublicRevenueItem> q = new LambdaQueryWrapper<>();
        if (communityId != null) {
            q.eq(PublicRevenueItem::getCommunityId, communityId);
        }
        if (StringUtils.hasText(status)) {
            q.eq(PublicRevenueItem::getStatus, status.trim());
        }
        q.orderByDesc(PublicRevenueItem::getId);
        Page<PublicRevenueItem> pg = publicRevenueItemMapper.selectPage(new Page<>(p, size), q);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", pg.getRecords());
        data.put("total", pg.getTotal());
        data.put("page", p);
        data.put("pageSize", size);
        return data;
    }

    private PublicRevenueItem requireStaffItem(Long id) {
        StaffGuard.requireStaff();
        PublicRevenueItem item = publicRevenueItemMapper.selectById(id);
        if (item == null || !item.getCommunityId().equals(StaffGuard.communityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "公共收益不存在");
        }
        return item;
    }

    private static String normalizeMonth(String occurMonth) {
        if (!StringUtils.hasText(occurMonth)) {
            return null;
        }
        String m = occurMonth.trim();
        if (!MONTH.matcher(m).matches()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "occurMonth 格式须为 YYYY-MM");
        }
        return m;
    }

    private static BigDecimal money2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
