package com.property.mgmt.common;

import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 物业岗位枚举与展示文案。
 * <p>原「物业管理员」已废除，完全等同一线岗「客服」({@link #CUSTOMER_SERVICE})；
 * 入参若仍传 {@link #LEGACY_ADMIN} 会归一为客服。
 */
public final class StaffRoles {

    public static final String PROPERTY_MANAGER = "PROPERTY_MANAGER";
    /** 客服（原「物业管理员」） */
    public static final String CUSTOMER_SERVICE = "CUSTOMER_SERVICE";
    public static final String SECURITY = "SECURITY";
    public static final String CLEANING = "CLEANING";
    public static final String LANDSCAPING = "LANDSCAPING";
    public static final String FACILITY_MAINT = "FACILITY_MAINT";

    /** 历史编码 PROPERTY_ADMIN，读写时一律视为客服 */
    public static final String LEGACY_ADMIN = "PROPERTY_ADMIN";

    public static final Set<String> LINE = Set.of(
            CUSTOMER_SERVICE, SECURITY, CLEANING, LANDSCAPING, FACILITY_MAINT);

    public static final Set<String> ALL;

    static {
        Set<String> all = new LinkedHashSet<>();
        all.add(PROPERTY_MANAGER);
        all.addAll(LINE);
        ALL = Collections.unmodifiableSet(all);
    }

    private StaffRoles() {}

    public static String normalize(String role) {
        if (!StringUtils.hasText(role)) {
            return null;
        }
        String r = role.trim();
        if (LEGACY_ADMIN.equals(r)) {
            return CUSTOMER_SERVICE;
        }
        return r;
    }

    public static boolean isKnown(String role) {
        String r = normalize(role);
        return r != null && ALL.contains(r);
    }

    public static boolean isManager(String role) {
        return PROPERTY_MANAGER.equals(normalize(role));
    }

    public static boolean isLine(String role) {
        String r = normalize(role);
        return r != null && LINE.contains(r);
    }

    /**
     * 是否允许登录 Web 管理端。
     * <p>规则：仅「客服」与「物业经理」可登 Web；保安 / 保洁 / 绿化 / 机电维修等一线执行岗
     * 只能登录小程序接收与处理工单，避免后台权限扩散。
     */
    public static boolean canLoginWeb(String role) {
        String r = normalize(role);
        return CUSTOMER_SERVICE.equals(r) || PROPERTY_MANAGER.equals(r);
    }

    /** 可接收工单的岗位：客服、物业经理 + 保安 / 保洁 / 绿化 / 机电维修 */
    public static boolean canTakeTicket(String role) {
        return isKnown(role);
    }

    public static void requireKnown(String role) {
        if (!isKnown(role)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "未知物业岗位: " + role);
        }
    }

    public static void requireLine(String role) {
        requireKnown(role);
        if (!isLine(role)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅允许一线岗位（客服/保安/保洁/绿化/机电）");
        }
    }

    public static String label(String role) {
        String r = normalize(role);
        if (r == null) {
            return "";
        }
        return switch (r) {
            case PROPERTY_MANAGER -> "物业经理";
            case CUSTOMER_SERVICE -> "客服";
            case SECURITY -> "保安";
            case CLEANING -> "保洁";
            case LANDSCAPING -> "绿化";
            case FACILITY_MAINT -> "机电维修";
            default -> r;
        };
    }

    /** 展示优先经理，其余按固定顺序 */
    public static String primary(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        Set<String> set = new HashSet<>();
        for (String role : roles) {
            String n = normalize(role);
            if (n != null) {
                set.add(n);
            }
        }
        if (set.contains(PROPERTY_MANAGER)) {
            return PROPERTY_MANAGER;
        }
        for (String line : LINE) {
            if (set.contains(line)) {
                return line;
            }
        }
        return set.iterator().next();
    }

    public static List<String> sortRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> set = new LinkedHashSet<>();
        for (String role : roles) {
            String n = normalize(role);
            if (n != null && ALL.contains(n)) {
                set.add(n);
            }
        }
        if (set.remove(PROPERTY_MANAGER)) {
            out.add(PROPERTY_MANAGER);
        }
        for (String line : LINE) {
            if (set.remove(line)) {
                out.add(line);
            }
        }
        out.addAll(set);
        return out;
    }

    public static String labelsJoined(Collection<String> roles) {
        List<String> sorted = sortRoles(roles);
        if (sorted.isEmpty()) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (String r : sorted) {
            labels.add(label(r));
        }
        return String.join("·", labels);
    }
}
