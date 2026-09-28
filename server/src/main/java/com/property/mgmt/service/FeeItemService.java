package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.FeeItem;
import com.property.mgmt.domain.FeeItemPriceRule;
import com.property.mgmt.mapper.FeeItemMapper;
import com.property.mgmt.mapper.FeeItemPriceRuleMapper;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 费项九大类（01-Q34）：大类写死；除 OTHER 外单例；计费方式 FIXED/FORMULA/IMPORT 三选一。
 */
@Service
@RequiredArgsConstructor
public class FeeItemService {

    public static final Set<String> CATEGORIES = Set.of(
            "PROPERTY_FEE", "PARKING_MGMT", "PARKING_MONTHLY", "SHARED",
            "GARBAGE", "WATER", "ELECTRIC", "GAS", "OTHER");

    /** 除「其他」外各大类本小区仅一条 */
    public static final Set<String> SINGLETON_CATEGORIES = Set.of(
            "PROPERTY_FEE", "PARKING_MGMT", "PARKING_MONTHLY", "SHARED",
            "GARBAGE", "WATER", "ELECTRIC", "GAS");

    public static final Set<String> FORMULA_ALLOWED = Set.of(
            "PROPERTY_FEE", "PARKING_MGMT", "PARKING_MONTHLY");

    public static final String DEFAULT_PROPERTY_FEE_REMARK =
            "{\"billingMode\":\"FORMULA\",\"useArea\":true,\"useHouseTypePrice\":true,\"discountRate\":1}";

    private static final ObjectMapper OM = new ObjectMapper();

    private final FeeItemMapper feeItemMapper;
    private final FeeItemPriceRuleMapper priceRuleMapper;

    public List<FeeItem> list() {
        Long cid = StaffGuard.communityId();
        seedDefaultsIfEmpty(cid);
        migrateLegacyFeeCategories(cid);
        return feeItemMapper.selectList(new LambdaQueryWrapper<FeeItem>()
                .eq(FeeItem::getCommunityId, cid)
                .orderByAsc(FeeItem::getId));
    }

    @Transactional
    public void seedDefaultsIfEmpty(Long communityId) {
        long cnt = feeItemMapper.selectCount(new LambdaQueryWrapper<FeeItem>()
                .eq(FeeItem::getCommunityId, communityId));
        if (cnt > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        FeeItem property = createSeed(communityId, "物业管理费", "PROPERTY_FEE", "AREA_X_HOUSE_TYPE_PRICE", null, now);
        property.setRemark(DEFAULT_PROPERTY_FEE_REMARK);
        feeItemMapper.updateById(property);
        FeeItem mgmt = createSeed(communityId, "车位管理费", "PARKING_MGMT", "PER_PARKING_BOUND", null, now);
        mgmt.setRemark("{\"billingMode\":\"FORMULA\"}");
        feeItemMapper.updateById(mgmt);
        FeeItem monthly = createSeed(communityId, "车辆月保费", "PARKING_MONTHLY", "PER_VEHICLE_UNBOUND", null, now);
        monthly.setRemark("{\"billingMode\":\"FORMULA\"}");
        feeItemMapper.updateById(monthly);
        FeeItem garbage = createSeed(communityId, "垃圾费", "GARBAGE", "FIXED_MONTHLY", new BigDecimal("5.00"), now);
        garbage.setRemark("{\"billingMode\":\"FIXED\"}");
        feeItemMapper.updateById(garbage);
        upsertRule(communityId, mgmt.getId(), "DEFAULT", new BigDecimal("50.0000"));
        upsertRule(communityId, monthly.getId(), "DEFAULT", new BigDecimal("200.0000"));
    }

    /**
     * 存量兼容：把名为「垃圾费」的 OTHER 升为 GARBAGE；补全 remark.billingMode。
     */
    @Transactional
    public void migrateLegacyFeeCategories(Long communityId) {
        List<FeeItem> all = feeItemMapper.selectList(new LambdaQueryWrapper<FeeItem>()
                .eq(FeeItem::getCommunityId, communityId));
        boolean hasGarbage = all.stream().anyMatch(f -> "GARBAGE".equals(f.getFeeCategory()));
        LocalDateTime now = LocalDateTime.now();
        for (FeeItem f : all) {
            boolean dirty = false;
            if (!hasGarbage && "OTHER".equals(f.getFeeCategory())
                    && f.getName() != null && f.getName().contains("垃圾")) {
                f.setFeeCategory("GARBAGE");
                if (f.getCalcType() == null || f.getCalcType().isBlank()) {
                    f.setCalcType("FIXED_MONTHLY");
                }
                dirty = true;
                hasGarbage = true;
            }
            if (readBillingMode(f.getRemark()) == null) {
                f.setRemark(mergeRemark(f.getRemark(), billingModeOf(f), f.getFeeCategory()));
                dirty = true;
            }
            if (dirty) {
                f.setUpdatedAt(now);
                feeItemMapper.updateById(f);
            }
        }
    }

    private FeeItem createSeed(Long cid, String name, String cat, String calc, BigDecimal monthly, LocalDateTime now) {
        FeeItem f = new FeeItem();
        f.setCommunityId(cid);
        f.setName(name);
        f.setFeeCategory(cat);
        f.setCalcType(calc);
        f.setMonthlyAmount(monthly);
        f.setStatus(1);
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        feeItemMapper.insert(f);
        return f;
    }

    @Transactional
    public FeeItem create(String name, String feeCategory, String calcType, BigDecimal monthlyAmount, String remark) {
        return create(name, feeCategory, null, calcType, monthlyAmount, remark);
    }

    @Transactional
    public FeeItem create(String name, String feeCategory, String billingMode,
                          String calcType, BigDecimal monthlyAmount, String remark) {
        Long cid = StaffGuard.communityId();
        if (name == null || name.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "name 必填");
        }
        if (feeCategory == null || !CATEGORIES.contains(feeCategory)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "feeCategory 非法，须为九大类之一");
        }
        if (SINGLETON_CATEGORIES.contains(feeCategory)) {
            long exists = feeItemMapper.selectCount(new LambdaQueryWrapper<FeeItem>()
                    .eq(FeeItem::getCommunityId, cid)
                    .eq(FeeItem::getFeeCategory, feeCategory));
            if (exists > 0) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "该大类本小区已存在，请直接编辑现有项（显示名可改）");
            }
        }
        String mode = normalizeBillingMode(feeCategory, billingMode, remark, calcType);
        if ("FORMULA".equals(mode) && !FORMULA_ALLOWED.contains(feeCategory)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "该大类不支持公式算费");
        }
        if ("FIXED".equals(mode) && (monthlyAmount == null || monthlyAmount.compareTo(BigDecimal.ZERO) < 0)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "固定价格须填写非负的月金额");
        }
        if (calcType == null || calcType.isBlank()) {
            calcType = defaultCalc(feeCategory, mode);
        }
        remark = mergeRemark(remark, mode, feeCategory);
        LocalDateTime now = LocalDateTime.now();
        FeeItem f = new FeeItem();
        f.setCommunityId(cid);
        f.setName(name.trim());
        f.setFeeCategory(feeCategory);
        f.setCalcType(calcType);
        f.setMonthlyAmount(monthlyAmount);
        f.setStatus(1);
        f.setRemark(remark);
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        feeItemMapper.insert(f);
        return f;
    }

    @Transactional
    public FeeItem update(Long id, String name, BigDecimal monthlyAmount, Integer status, String remark, String calcType) {
        return update(id, name, null, monthlyAmount, status, remark, calcType);
    }

    @Transactional
    public FeeItem update(Long id, String name, String billingMode, BigDecimal monthlyAmount,
                          Integer status, String remark, String calcType) {
        FeeItem f = requireMine(id);
        if (name != null && !name.isBlank()) {
            f.setName(name.trim());
        }
        if (monthlyAmount != null) {
            f.setMonthlyAmount(monthlyAmount);
        }
        if (status != null) {
            f.setStatus(status);
        }
        String mode = billingMode;
        if (mode == null || mode.isBlank()) {
            mode = billingModeOf(f);
        } else {
            mode = mode.trim().toUpperCase();
            if (!Set.of("FIXED", "FORMULA", "IMPORT").contains(mode)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "billingMode 须为 FIXED/FORMULA/IMPORT");
            }
            if ("FORMULA".equals(mode) && !FORMULA_ALLOWED.contains(f.getFeeCategory())) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "该大类不支持公式算费");
            }
        }
        if (remark != null) {
            f.setRemark(mergeRemark(remark, mode, f.getFeeCategory()));
        } else if (billingMode != null && !billingMode.isBlank()) {
            f.setRemark(mergeRemark(f.getRemark(), mode, f.getFeeCategory()));
        }
        if (calcType != null && !calcType.isBlank()) {
            f.setCalcType(calcType);
        } else if (billingMode != null && !billingMode.isBlank()) {
            f.setCalcType(defaultCalc(f.getFeeCategory(), mode));
        }
        if ("FIXED".equals(billingModeOf(f)) && f.getMonthlyAmount() == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "固定价格须填写月金额");
        }
        f.setUpdatedAt(LocalDateTime.now());
        feeItemMapper.updateById(f);
        return f;
    }

    @Transactional
    public void delete(Long id) {
        requireMine(id);
        priceRuleMapper.delete(new LambdaQueryWrapper<FeeItemPriceRule>()
                .eq(FeeItemPriceRule::getFeeItemId, id));
        feeItemMapper.deleteById(id);
    }

    /** 当前启用且计费方式为表格导入的费项 */
    public static boolean isImportMode(FeeItem f) {
        return "IMPORT".equals(billingModeOf(f));
    }

    /** @deprecated 用 {@link #isImportMode(FeeItem)}；保留类别级判断供旧调用 */
    public static boolean isImportConfigured(String feeCategory) {
        return "SHARED".equals(feeCategory) || "WATER".equals(feeCategory)
                || "ELECTRIC".equals(feeCategory) || "GAS".equals(feeCategory);
    }

    public List<FeeItem> enabledImportConfigured(Long communityId) {
        return enabledByCommunity(communityId).stream()
                .filter(FeeItemService::isImportMode)
                .toList();
    }

    public static String billingModeOf(FeeItem f) {
        if (f == null) {
            return "FIXED";
        }
        String fromRemark = readBillingMode(f.getRemark());
        if (fromRemark != null) {
            return fromRemark;
        }
        String cat = f.getFeeCategory();
        if (FORMULA_ALLOWED.contains(cat)) {
            return "FORMULA";
        }
        if ("SHARED".equals(cat) || "WATER".equals(cat) || "ELECTRIC".equals(cat) || "GAS".equals(cat)) {
            return "IMPORT";
        }
        return "FIXED";
    }

    public static String defaultDisplayName(String feeCategory) {
        return switch (feeCategory) {
            case "PROPERTY_FEE" -> "物业管理费";
            case "PARKING_MGMT" -> "车位管理费";
            case "PARKING_MONTHLY" -> "车辆月保费";
            case "SHARED" -> "公摊费";
            case "GARBAGE" -> "垃圾费";
            case "WATER" -> "代收水费";
            case "ELECTRIC" -> "代收电费";
            case "GAS" -> "代收煤气费";
            case "OTHER" -> "其他";
            default -> feeCategory;
        };
    }

    public static String categoryLabel(String feeCategory) {
        return defaultDisplayName(feeCategory);
    }

    public List<FeeItemPriceRule> listRules(Long feeItemId) {
        requireMine(feeItemId);
        return priceRuleMapper.selectList(new LambdaQueryWrapper<FeeItemPriceRule>()
                .eq(FeeItemPriceRule::getFeeItemId, feeItemId)
                .orderByAsc(FeeItemPriceRule::getId));
    }

    @Transactional
    public FeeItemPriceRule addRule(Long feeItemId, String matchKey, BigDecimal unitPrice) {
        FeeItem f = requireMine(feeItemId);
        if (matchKey == null || matchKey.isBlank() || unitPrice == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "matchKey/unitPrice 必填");
        }
        return upsertRule(f.getCommunityId(), feeItemId, matchKey.trim().toUpperCase(), unitPrice);
    }

    @Transactional
    public FeeItemPriceRule updateRule(Long ruleId, String matchKey, BigDecimal unitPrice) {
        FeeItemPriceRule r = priceRuleMapper.selectById(ruleId);
        if (r == null || !r.getCommunityId().equals(StaffGuard.communityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "单价规则不存在");
        }
        if (matchKey != null && !matchKey.isBlank()) {
            r.setMatchKey(matchKey.trim().toUpperCase());
        }
        if (unitPrice != null) {
            r.setUnitPrice(unitPrice);
        }
        r.setUpdatedAt(LocalDateTime.now());
        priceRuleMapper.updateById(r);
        return r;
    }

    public FeeItemPriceRule upsertRule(Long communityId, Long feeItemId, String matchKey, BigDecimal unitPrice) {
        FeeItemPriceRule exist = priceRuleMapper.selectOne(new LambdaQueryWrapper<FeeItemPriceRule>()
                .eq(FeeItemPriceRule::getFeeItemId, feeItemId)
                .eq(FeeItemPriceRule::getMatchKey, matchKey));
        LocalDateTime now = LocalDateTime.now();
        if (exist != null) {
            exist.setUnitPrice(unitPrice);
            exist.setUpdatedAt(now);
            priceRuleMapper.updateById(exist);
            return exist;
        }
        FeeItemPriceRule r = new FeeItemPriceRule();
        r.setCommunityId(communityId);
        r.setFeeItemId(feeItemId);
        r.setMatchKey(matchKey);
        r.setUnitPrice(unitPrice);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        priceRuleMapper.insert(r);
        return r;
    }

    public List<FeeItem> enabledByCommunity(Long communityId) {
        return feeItemMapper.selectList(new LambdaQueryWrapper<FeeItem>()
                .eq(FeeItem::getCommunityId, communityId)
                .eq(FeeItem::getStatus, 1));
    }

    public List<FeeItemPriceRule> rulesOf(Long feeItemId) {
        return priceRuleMapper.selectList(new LambdaQueryWrapper<FeeItemPriceRule>()
                .eq(FeeItemPriceRule::getFeeItemId, feeItemId));
    }

    private FeeItem requireMine(Long id) {
        FeeItem f = feeItemMapper.selectById(id);
        if (f == null || !f.getCommunityId().equals(StaffGuard.communityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "费项不存在");
        }
        return f;
    }

    private static String normalizeBillingMode(String feeCategory, String billingMode, String remark, String calcType) {
        if (billingMode != null && !billingMode.isBlank()) {
            String m = billingMode.trim().toUpperCase();
            if (!Set.of("FIXED", "FORMULA", "IMPORT").contains(m)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "billingMode 须为 FIXED/FORMULA/IMPORT");
            }
            return m;
        }
        String fromRemark = readBillingMode(remark);
        if (fromRemark != null) {
            return fromRemark;
        }
        if (calcType != null) {
            if ("FIXED_MONTHLY".equals(calcType)) {
                return "FIXED";
            }
            if ("UPLOAD_AMOUNT".equals(calcType) || "METER_READING".equals(calcType)) {
                return "IMPORT";
            }
            if ("AREA_X_HOUSE_TYPE_PRICE".equals(calcType) || "PER_PARKING_BOUND".equals(calcType)
                    || "PER_VEHICLE_UNBOUND".equals(calcType)) {
                return "FORMULA";
            }
        }
        if (FORMULA_ALLOWED.contains(feeCategory)) {
            return "FORMULA";
        }
        if ("SHARED".equals(feeCategory) || "WATER".equals(feeCategory)
                || "ELECTRIC".equals(feeCategory) || "GAS".equals(feeCategory)) {
            return "IMPORT";
        }
        return "FIXED";
    }

    @SuppressWarnings("unchecked")
    private static String readBillingMode(String remark) {
        if (remark == null || remark.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> m = OM.readValue(remark, Map.class);
            Object v = m.get("billingMode");
            if (v == null) {
                return null;
            }
            String s = String.valueOf(v).trim().toUpperCase();
            return Set.of("FIXED", "FORMULA", "IMPORT").contains(s) ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String mergeRemark(String remark, String mode, String feeCategory) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (remark != null && !remark.isBlank()) {
            try {
                Map<String, Object> exist = OM.readValue(remark, Map.class);
                if (exist != null) {
                    m.putAll(exist);
                }
            } catch (Exception ignored) {
                // keep empty
            }
        }
        m.put("billingMode", mode);
        if ("PROPERTY_FEE".equals(feeCategory) && "FORMULA".equals(mode)) {
            m.putIfAbsent("useArea", true);
            m.putIfAbsent("useHouseTypePrice", true);
            m.putIfAbsent("discountRate", 1);
        }
        try {
            return OM.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"billingMode\":\"" + mode + "\"}";
        }
    }

    private static String defaultCalc(String cat, String mode) {
        if ("FIXED".equals(mode)) {
            return "FIXED_MONTHLY";
        }
        if ("IMPORT".equals(mode)) {
            return ("WATER".equals(cat) || "ELECTRIC".equals(cat) || "GAS".equals(cat))
                    ? "METER_READING" : "UPLOAD_AMOUNT";
        }
        return switch (cat) {
            case "PROPERTY_FEE" -> "AREA_X_HOUSE_TYPE_PRICE";
            case "PARKING_MGMT" -> "PER_PARKING_BOUND";
            case "PARKING_MONTHLY" -> "PER_VEHICLE_UNBOUND";
            default -> "FIXED_MONTHLY";
        };
    }
}
