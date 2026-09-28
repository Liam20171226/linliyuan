package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.DefaultWebPassword;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.common.StaffRoles;
import com.property.mgmt.domain.StaffCommunity;
import com.property.mgmt.domain.SysUser;
import com.property.mgmt.mapper.StaffCommunityMapper;
import com.property.mgmt.mapper.SysUserMapper;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 本小区岗位人员：物业经理配置一线岗（客服/保安/保洁/绿化/机电）。
 */
@Service
@RequiredArgsConstructor
public class StaffTeamService {

    /** Web 登录密码最短长度 */
    private static final int WEB_PASSWORD_MIN = 6;

    private final StaffCommunityMapper staffCommunityMapper;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final PlatformService platformService;

    public Map<String, Object> listTeam() {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = u.getCommunityId();
        List<StaffCommunity> rows = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, cid)
                .eq(StaffCommunity::getStatus, "ACTIVE")
                .orderByDesc(StaffCommunity::getId));
        boolean platform = StaffGuard.isPlatformScope(u);
        List<String> ownRoles = platform ? List.of() : StaffGuard.activeRoles(u.getUserId(), cid);
        boolean manager = platform || ownRoles.contains(StaffRoles.PROPERTY_MANAGER);
        Map<String, Object> out = new LinkedHashMap<>();
        // canManage：新增人员 / 兼岗设置 / 删除兼岗 —— 仅物业经理与平台管理员
        out.put("canManage", manager);
        out.put("isManager", manager);
        out.put("isCustomerService", ownRoles.contains(StaffRoles.CUSTOMER_SERVICE));
        // 平台管理员进小区代管：可重置本小区任意可登录 Web 岗位（客服 / 经理）的密码
        out.put("isPlatform", platform);
        // 客服可自助重置本人密码，前端靠它判断"哪一行是我"
        out.put("selfUserId", u.getUserId());
        out.put("members", groupByUser(cid, rows));
        return out;
    }

    private List<Map<String, Object>> groupByUser(Long communityId, List<StaffCommunity> rows) {
        Map<Long, Map<String, Object>> byUser = new LinkedHashMap<>();
        for (StaffCommunity sc : rows) {
            Map<String, Object> m = byUser.computeIfAbsent(sc.getUserId(), uid -> {
                SysUser user = sysUserMapper.selectById(uid);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("userId", uid);
                row.put("communityId", communityId);
                row.put("mobile", user == null ? null : user.getMobile());
                row.put("realName", user == null ? null : user.getRealName());
                // 客服岗须登录 Web 管理端，无密码时要提示先设置/重置密码
                row.put("hasPassword", user != null && StringUtils.hasText(user.getPasswordHash()));
                // 供列表展示「须改密 / 已设密 / 未设密」三态
                row.put("mustChangePassword", user != null && Objects.equals(user.getMustChangePassword(), 1));
                row.put("roles", new ArrayList<String>());
                return row;
            });
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) m.get("roles");
            String role = StaffRoles.normalize(sc.getStaffRole());
            if (role != null && !roles.contains(role)) {
                roles.add(role);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : byUser.values()) {
            @SuppressWarnings("unchecked")
            List<String> roles = StaffRoles.sortRoles((List<String>) m.get("roles"));
            m.put("roles", roles);
            m.put("staffRoles", roles);
            m.put("staffRole", StaffRoles.primary(roles));
            m.put("roleLabels", StaffRoles.labelsJoined(roles));
            m.put("isManager", roles.contains(StaffRoles.PROPERTY_MANAGER));
            out.add(m);
        }
        out.sort((a, b) -> {
            boolean am = Boolean.TRUE.equals(a.get("isManager"));
            boolean bm = Boolean.TRUE.equals(b.get("isManager"));
            if (am != bm) {
                return am ? -1 : 1;
            }
            String an = String.valueOf(a.get("realName") == null ? "" : a.get("realName"));
            String bn = String.valueOf(b.get("realName") == null ? "" : b.get("realName"));
            return an.compareTo(bn);
        });
        return out;
    }

    /**
     * 同步某人在本小区的一线岗位（不含经理）。经理岗不可通过此接口增减。
     */
    @Transactional
    public Map<String, Object> syncLineRoles(Long userId, List<String> lineRoles) {
        return syncLineRoles(userId, lineRoles, null);
    }

    /**
     * @param webPassword 兼岗为客服岗且该账号无登录密码时，用它初始化 Web 登录密码（至少 6 位）
     */
    @Transactional
    public Map<String, Object> syncLineRoles(Long userId, List<String> lineRoles, String webPassword) {
        AuthUser actor = StaffGuard.requireStaff();
        StaffGuard.requireManagerOrPlatform(actor);
        Long cid = actor.getCommunityId();
        if (userId == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "userId 必填");
        }
        SysUser target = sysUserMapper.selectById(userId);
        if (target == null || !Objects.equals(target.getStatus(), 1)) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在或已停用");
        }

        Set<String> desired = new LinkedHashSet<>();
        if (lineRoles != null) {
            for (String r : lineRoles) {
                String n = StaffRoles.normalize(r);
                if (n == null) {
                    continue;
                }
                StaffRoles.requireLine(n);
                desired.add(n);
            }
        }
        // 客服岗须登录 Web 管理端：非客服转客服时，若账号无密码则用本次传入的密码初始化
        boolean passwordSet = false;
        if (desired.contains(StaffRoles.CUSTOMER_SERVICE) && !StringUtils.hasText(target.getPasswordHash())) {
            // 未指定密码时用默认密码，并强制首次登录修改
            setWebPassword(target, resolvePassword(webPassword));
            passwordSet = true;
        }

        LocalDateTime now = LocalDateTime.now();
        List<StaffCommunity> existing = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, cid)
                .eq(StaffCommunity::getUserId, userId));

        Set<String> haveActiveLine = new HashSet<>();
        for (StaffCommunity sc : existing) {
            String role = StaffRoles.normalize(sc.getStaffRole());
            if (StaffRoles.isManager(role)) {
                continue;
            }
            if (!StaffRoles.isLine(role)) {
                continue;
            }
            if ("ACTIVE".equals(sc.getStatus())) {
                if (desired.contains(role)) {
                    haveActiveLine.add(role);
                } else {
                    sc.setStatus("INACTIVE");
                    sc.setUpdatedAt(now);
                    staffCommunityMapper.updateById(sc);
                }
            }
        }

        for (String role : desired) {
            if (haveActiveLine.contains(role)) {
                continue;
            }
            platformService.ensureStaffRole(cid, userId, role);
        }

        // 若此人无任何 ACTIVE 岗（含经理），且 desired 为空，允许清光一线
        // 岗位变动后：此人在任一小区都不再持有「客服 / 经理」（能登录 Web 的岗位）时，
        // 清掉那个永远用不上的 Web 密码，避免团队列表里显示成"有密码"的误导状态
        boolean passwordCleared = clearStaleWebPassword(userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("communityId", cid);
        out.put("lineRoles", StaffRoles.sortRoles(desired));
        out.put("passwordSet", passwordSet);
        out.put("passwordCleared", passwordCleared);
        return out;
    }

    /**
     * 此人在任一小区都不再持有可登录 Web 的岗位（客服 / 经理）时，清空其 Web 登录密码。
     * 保安、保洁、绿化、机电等一线岗只用小程序接单，留着密码既无用又会造成"已设密码"的误解。
     *
     * @return 是否真的清掉了密码
     */
    private boolean clearStaleWebPassword(Long userId) {
        Long webCapable = staffCommunityMapper.selectCount(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getUserId, userId)
                .eq(StaffCommunity::getStatus, "ACTIVE")
                .in(StaffCommunity::getStaffRole,
                        Arrays.asList(StaffRoles.CUSTOMER_SERVICE, StaffRoles.PROPERTY_MANAGER)));
        if (webCapable != null && webCapable > 0) {
            return false;
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || !StringUtils.hasText(user.getPasswordHash())) {
            return false;
        }
        // 注意：MyBatis-Plus 的 updateById 会忽略 null 字段，置空必须用 wrapper 显式 set
        LocalDateTime now = LocalDateTime.now();
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getPasswordHash, null)
                .set(SysUser::getMustChangePassword, 0)
                .set(SysUser::getUpdatedAt, now));
        return true;
    }

    /**
     * 重置团队成员的 Web 登录密码。
     * <p>权限：平台管理员不限；物业经理只能重置<b>本人与客服</b>；客服只能重置<b>本人</b>。
     * 保安 / 保洁 / 绿化 / 机电不登录 Web 管理端，其密码不在此处重置。
     * 重置后 must_change_password=1，当事人首次登录 Web 管理端须修改密码。
     */
    @Transactional
    public Map<String, Object> resetMemberPassword(Long userId, String newPassword) {
        AuthUser actor = StaffGuard.requireStaff();
        Long cid = actor.getCommunityId();
        if (userId == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "userId 必填");
        }
        // 未指定新密码时使用默认密码
        String pwd = resolvePassword(newPassword);
        boolean usedDefault = !StringUtils.hasText(newPassword);
        SysUser target = sysUserMapper.selectById(userId);
        if (target == null || !Objects.equals(target.getStatus(), 1)) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在或已停用");
        }
        if (Objects.equals(target.getIsPlatformAdmin(), 1) && !actor.isPlatformAdmin()) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "平台管理员密码仅平台管理员可重置");
        }
        Long inTeam = staffCommunityMapper.selectCount(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, cid)
                .eq(StaffCommunity::getUserId, userId)
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        if (inTeam == null || inTeam == 0) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "该用户不是本小区在册人员，无法重置密码");
        }
        checkResetPasswordPermission(actor, cid, userId);
        setWebPassword(target, pwd);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("mobile", target.getMobile());
        out.put("realName", target.getRealName());
        out.put("mustChangePassword", true);
        out.put("usedDefaultPassword", usedDefault);
        return out;
    }

    /**
     * Web 密码重置的授权范围：
     * <ul>
     *   <li>平台管理员：本小区任意在册人员（含另一位物业经理）均可重置；</li>
     *   <li>物业经理：可自助重置，并可协助重置客服；</li>
     *   <li>客服：只能重置本人；</li>
     *   <li>其余一线岗（保安/保洁/绿化/机电）没有 Web 密码，经理也不得代重置。</li>
     * </ul>
     */
    private void checkResetPasswordPermission(AuthUser actor, Long cid, Long targetUserId) {
        if (StaffGuard.isPlatformScope(actor)) {
            return;
        }
        boolean self = Objects.equals(actor.getUserId(), targetUserId);
        if (self) {
            return;
        }
        List<String> actorRoles = StaffGuard.activeRoles(actor.getUserId(), cid);
        if (!actorRoles.contains(StaffRoles.PROPERTY_MANAGER)) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "仅可重置本人密码，客服不能重置他人密码");
        }
        List<String> targetRoles = StaffGuard.activeRoles(targetUserId, cid);
        if (!targetRoles.contains(StaffRoles.CUSTOMER_SERVICE)) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "物业经理仅可重置本人或客服的密码");
        }
    }

    /** 写入 Web 登录密码，并标记首次登录须修改 */
    private void setWebPassword(SysUser user, String rawPassword) {
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setMustChangePassword(1);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
    }

    /** 未指定密码时用默认密码；指定了则校验长度 */
    private String resolvePassword(String input) {
        if (!StringUtils.hasText(input)) {
            return DefaultWebPassword.VALUE;
        }
        String pwd = input.trim();
        if (pwd.length() < WEB_PASSWORD_MIN) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "密码至少 " + WEB_PASSWORD_MIN + " 位");
        }
        return pwd;
    }

    /**
     * 按手机号新增一线人员：无账号则建号；只有客服岗须临时密码（客服才能登录 Web 管理端），
     * 其余一线岗仅使用小程序，不设密码。
     */
    @Transactional
    public Map<String, Object> addMember(String mobile, String realName, String tempPassword, List<String> lineRoles) {
        AuthUser actor = StaffGuard.requireStaff();
        StaffGuard.requireManagerOrPlatform(actor);
        Long cid = actor.getCommunityId();
        if (!StringUtils.hasText(mobile)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "手机号必填");
        }
        String mob = mobile.trim();
        if (lineRoles == null || lineRoles.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请至少选择一个一线岗位");
        }
        Set<String> incoming = new LinkedHashSet<>();
        for (String r : lineRoles) {
            StaffRoles.requireLine(StaffRoles.normalize(r));
            incoming.add(StaffRoles.normalize(r));
        }

        // 本次是否真的写入/变更了 Web 登录密码，供前端如实提示（已有密码的账号不会被覆盖）
        boolean passwordSet = false;
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getMobile, mob));
        if (user == null) {
            // 新账号：只有客服岗需要密码
            boolean willBeCs = incoming.contains(StaffRoles.CUSTOMER_SERVICE);
            // 客服岗须登录 Web 管理端：未指定密码时使用默认密码
            String newPwd = willBeCs ? resolvePassword(tempPassword) : null;
            user = new SysUser();
            user.setMobile(mob);
            user.setRealName(StringUtils.hasText(realName) ? realName.trim() : null);
            if (willBeCs) {
                user.setPasswordHash(passwordEncoder.encode(newPwd));
                user.setMustChangePassword(1);
                passwordSet = true;
            }
            user.setIsPlatformAdmin(0);
            user.setStatus(1);
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            sysUserMapper.insert(user);
        } else {
            if (Objects.equals(user.getIsPlatformAdmin(), 1)) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "不可将平台管理员加入物业团队");
            }
            if (!Objects.equals(user.getStatus(), 1)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "该账号已停用，请先由平台启用");
            }
            // 已有账号：合并现有一线岗后判断是否会持有客服岗
            Set<String> currentLine = new LinkedHashSet<>();
            for (StaffCommunity sc : staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                    .eq(StaffCommunity::getCommunityId, cid)
                    .eq(StaffCommunity::getUserId, user.getId())
                    .eq(StaffCommunity::getStatus, "ACTIVE"))) {
                String r = StaffRoles.normalize(sc.getStaffRole());
                if (StaffRoles.isLine(r)) {
                    currentLine.add(r);
                }
            }
            boolean willBeCs = currentLine.contains(StaffRoles.CUSTOMER_SERVICE)
                    || incoming.contains(StaffRoles.CUSTOMER_SERVICE);
            if (StringUtils.hasText(realName) && !StringUtils.hasText(user.getRealName())) {
                user.setRealName(realName.trim());
                user.setUpdatedAt(LocalDateTime.now());
                sysUserMapper.updateById(user);
            }
            if (!StringUtils.hasText(user.getPasswordHash()) && willBeCs) {
                // 未指定密码时使用默认密码
                user.setPasswordHash(passwordEncoder.encode(resolvePassword(tempPassword)));
                user.setMustChangePassword(1);
                user.setUpdatedAt(LocalDateTime.now());
                sysUserMapper.updateById(user);
                passwordSet = true;
            }
        }

        // 合并已有一线岗 + 新增
        Set<String> merged = new LinkedHashSet<>();
        for (StaffCommunity sc : staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, cid)
                .eq(StaffCommunity::getUserId, user.getId())
                .eq(StaffCommunity::getStatus, "ACTIVE"))) {
            String r = StaffRoles.normalize(sc.getStaffRole());
            if (StaffRoles.isLine(r)) {
                merged.add(r);
            }
        }
        for (String r : lineRoles) {
            merged.add(StaffRoles.normalize(r));
        }
        syncLineRoles(user.getId(), new ArrayList<>(merged));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.getId());
        out.put("mobile", user.getMobile());
        out.put("realName", user.getRealName());
        out.put("lineRoles", StaffRoles.sortRoles(merged));
        // passwordSet=false 表示该账号原本就有密码、本次未变更，前端不可再提示「登录密码为 xxx」
        out.put("passwordSet", passwordSet);
        out.put("hasPassword", StringUtils.hasText(user.getPasswordHash()));
        return out;
    }

    public Map<String, Object> roleCatalog() {
        StaffGuard.requireStaff();
        List<Map<String, Object>> line = new ArrayList<>();
        for (String r : StaffRoles.LINE) {
            line.add(Map.of("value", r, "label", StaffRoles.label(r)));
        }
        return Map.of(
                "manager", Map.of("value", StaffRoles.PROPERTY_MANAGER, "label", StaffRoles.label(StaffRoles.PROPERTY_MANAGER)),
                "lineRoles", line
        );
    }
}
