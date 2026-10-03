package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.common.StaffRoles;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.integration.WechatMiniApi;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final UserWechatMapper userWechatMapper;
    private final StaffCommunityMapper staffCommunityMapper;
    private final CommitteeMemberMapper committeeMemberMapper;
    private final RoomOccupantMapper roomOccupantMapper;
    private final CommunityMapper communityMapper;
    private final RoomMapper roomMapper;
    private final OccupantQueryService occupantQueryService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final WechatMiniApi wechatMiniApi;
    private final com.property.mgmt.config.WxProperties wxProperties;

    public Map<String, Object> platformLogin(String username, String password) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getIsPlatformAdmin, 1)
                .eq(SysUser::getStatus, 1));
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw BizException.of(ErrorCodes.UNAUTHORIZED, "用户名或密码错误");
        }
        touchLogin(user);
        AuthUser auth = AuthUser.builder()
                .userId(user.getId())
                .identityType("PLATFORM")
                .platformAdmin(true)
                .build();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", jwtService.issue(auth));
        data.put("user", Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "isPlatformAdmin", true
        ));
        return data;
    }

    public Map<String, Object> staffLogin(String mobile, String password) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getMobile, mobile)
                .eq(SysUser::getStatus, 1));
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw BizException.of(ErrorCodes.UNAUTHORIZED, "手机号或密码错误");
        }
        List<StaffCommunity> staffs = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getUserId, user.getId())
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        if (staffs.isEmpty()) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "无有效物业任职");
        }
        // Web 管理端仅对「客服 / 物业经理」开放；保安、保洁、绿化、机电维修等一线岗只能登录小程序接单
        boolean webAllowed = staffs.stream()
                .map(sc -> StaffRoles.normalize(sc.getStaffRole()))
                .filter(Objects::nonNull)
                .anyMatch(StaffRoles::canLoginWeb);
        if (!webAllowed) {
            throw BizException.of(ErrorCodes.FORBIDDEN,
                    "该岗位无 Web 管理端权限，请使用小程序处理工单");
        }
        touchLogin(user);
        AuthUser auth = AuthUser.builder()
                .userId(user.getId())
                .platformAdmin(false)
                .webStaffSession(true)
                .build();
        List<Map<String, Object>> communities = new ArrayList<>();
        Map<Long, List<String>> rolesByCommunity = new LinkedHashMap<>();
        for (StaffCommunity sc : staffs) {
            String role = StaffRoles.normalize(sc.getStaffRole());
            if (role == null) {
                continue;
            }
            rolesByCommunity.computeIfAbsent(sc.getCommunityId(), k -> new ArrayList<>()).add(role);
        }
        for (Map.Entry<Long, List<String>> e : rolesByCommunity.entrySet()) {
            Community c = communityMapper.selectById(e.getKey());
            List<String> roles = StaffRoles.sortRoles(e.getValue());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("communityId", e.getKey());
            row.put("communityName", c == null ? "" : c.getName());
            row.put("staffRole", StaffRoles.primary(roles));
            row.put("staffRoles", roles);
            row.put("roleLabels", StaffRoles.labelsJoined(roles));
            communities.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", jwtService.issue(auth));
        data.put("mustChangePassword", Objects.equals(user.getMustChangePassword(), 1));
        data.put("user", Map.of(
                "id", user.getId(),
                "mobile", Optional.ofNullable(user.getMobile()).orElse(""),
                "realName", Optional.ofNullable(user.getRealName()).orElse("")
        ));
        data.put("communities", communities);
        return data;
    }

    /**
     * 物业首次登录改密：校验原（临时）密码后写入新密码并清除 must_change_password。
     */
    @Transactional
    public Map<String, Object> changeStaffPassword(String oldPassword, String newPassword) {
        return changePasswordInternal(oldPassword, newPassword, false);
    }

    /**
     * App 密码登录（住户 / 业委会 / 物业含一线岗）。
     * 与 Web 物业登录共用 password_hash；不限制 Web 岗，一线岗可登 App。
     */
    public Map<String, Object> appLogin(String mobile, String password) {
        if (mobile == null || mobile.isBlank() || password == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "手机号与密码必填");
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getMobile, mobile.trim())
                .eq(SysUser::getStatus, 1));
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw BizException.of(ErrorCodes.UNAUTHORIZED, "手机号或密码错误");
        }
        if (Objects.equals(user.getIsPlatformAdmin(), 1)) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "平台账号请使用管理后台");
        }
        List<Map<String, Object>> identities = listIdentities(user.getId());
        // 游客（含小程序先登录再 H5 设密）允许无业务绑定时以 GUEST 进入
        if (identities.isEmpty()) {
            if ("APP_GUEST".equals(user.getRegisterSource())) {
                Map<String, Object> guest = new LinkedHashMap<>();
                guest.put("identityType", "GUEST");
                identities = new ArrayList<>(List.of(guest));
            } else {
                throw BizException.of(ErrorCodes.FORBIDDEN, "账号无小区身份，请先完成住户认证或物业任职");
            }
        }
        touchLogin(user);
        AuthUser auth = AuthUser.builder()
                .userId(user.getId())
                .platformAdmin(false)
                .appPasswordSession(true)
                .build();
        boolean needChoose = identities.size() > 1;
        if (!needChoose) {
            Map<String, Object> only = identities.get(0);
            auth.setIdentityType((String) only.get("identityType"));
            Object cid = only.get("communityId");
            if (cid instanceof Number n) {
                auth.setCommunityId(n.longValue());
            }
            Object rid = only.get("roomId");
            if (rid instanceof Number n) {
                auth.setRoomId(n.longValue());
            }
            if (only.get("staffRole") != null) {
                auth.setStaffRole((String) only.get("staffRole"));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", jwtService.issue(auth));
        data.put("mustChangePassword", Objects.equals(user.getMustChangePassword(), 1));
        data.put("needChooseIdentity", needChoose);
        data.put("user", Map.of(
                "id", user.getId(),
                "mobile", Optional.ofNullable(user.getMobile()).orElse(""),
                "realName", Optional.ofNullable(user.getRealName()).orElse("")
        ));
        data.put("identities", identities);
        data.put("current", needChoose ? null : identities.get(0));
        return data;
    }

    /** App 首次/主动改密 */
    @Transactional
    public Map<String, Object> changeAppPassword(String oldPassword, String newPassword) {
        return changePasswordInternal(oldPassword, newPassword, true);
    }

    private Map<String, Object> changePasswordInternal(String oldPassword, String newPassword, boolean app) {
        Long userId = AuthContext.require().getUserId();
        if (newPassword == null || newPassword.length() < 6) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "新密码至少 6 位");
        }
        if (Objects.equals(oldPassword, newPassword)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "新密码不能与原密码相同");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || Objects.equals(user.getIsPlatformAdmin(), 1)) {
            throw BizException.of(ErrorCodes.FORBIDDEN, app ? "仅 App 用户可改密" : "仅物业账号可改密");
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "原密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(0);
        if ("APP_GUEST".equals(user.getRegisterSource())) {
            if (newPassword.length() > 13) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "游客密码最多 13 位");
            }
            user.setPasswordPlain(newPassword);
        }
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        return Map.of("mustChangePassword", false);
    }

    /**
     * 注册前查号：已设密码视为占用；仅有小程序建号、尚未设 App 密码时可在注册流程中补密（claimable）。
     */
    public Map<String, Object> isMobileTaken(String mobile) {
        if (mobile == null || !mobile.trim().matches("1\\d{10}")) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请输入正确手机号");
        }
        String m = mobile.trim();
        SysUser exist = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, m));
        if (exist == null) {
            return Map.of("taken", false, "claimable", false, "mobile", m);
        }
        boolean hasPwd = StringUtils.hasText(exist.getPasswordHash());
        return Map.of("taken", hasPwd, "claimable", !hasPwd, "mobile", m);
    }

    /**
     * App 游客自助注册：手机号 + 密码；默认姓名「游客」。
     * 若号码已被小程序占用且尚未设密码，则为同一账号补密并登录（不新建）。
     */
    @Transactional
    public Map<String, Object> appRegister(String mobile, String password) {
        if (mobile == null || !mobile.trim().matches("1\\d{10}")) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请使用 11 位手机号注册");
        }
        String m = mobile.trim();
        if (password == null || password.length() < 6 || password.length() > 13) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "密码须为 6～13 位");
        }
        SysUser exist = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, m));
        if (exist != null) {
            if (StringUtils.hasText(exist.getPasswordHash())) {
                throw BizException.of(ErrorCodes.MOBILE_TAKEN, "当前手机号码正在使用，请联系工作人员。");
            }
            // 小程序等先建号、无 App 密码：补密后登录同一账号
            exist.setPasswordHash(passwordEncoder.encode(password));
            exist.setPasswordPlain(password);
            exist.setMustChangePassword(0);
            if (!StringUtils.hasText(exist.getRegisterSource())) {
                exist.setRegisterSource("APP_GUEST");
            }
            if (!StringUtils.hasText(exist.getRealName())) {
                exist.setRealName("游客");
            }
            exist.setUpdatedAt(LocalDateTime.now());
            sysUserMapper.updateById(exist);
            return appLogin(m, password);
        }
        SysUser user = new SysUser();
        user.setMobile(m);
        user.setRealName("游客");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setPasswordPlain(password);
        user.setRegisterSource("APP_GUEST");
        user.setMustChangePassword(0);
        user.setIsPlatformAdmin(0);
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.insert(user);
        return appLogin(m, password);
    }
    public Map<String, Object> code2session(String code) {
        String openid = wechatMiniApi.resolveOpenid(code);
        UserWechat bind = userWechatMapper.selectOne(new LambdaQueryWrapper<UserWechat>()
                .eq(UserWechat::getAppId, wxProperties.getAppId())
                .eq(UserWechat::getOpenid, openid));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mock", wechatMiniApi.isMock());
        if (bind != null) {
            SysUser user = sysUserMapper.selectById(bind.getUserId());
            if (user != null && user.getMobile() != null && !user.getMobile().isBlank()) {
                List<Map<String, Object>> identities = listIdentities(user.getId());
                AuthUser auth = AuthUser.builder()
                        .userId(user.getId())
                        .platformAdmin(Objects.equals(user.getIsPlatformAdmin(), 1))
                        .build();
                data.put("token", jwtService.issue(auth));
                data.put("needPhoneAuth", false);
                data.put("user", Map.of(
                        "id", user.getId(),
                        "mobile", user.getMobile(),
                        "realName", Optional.ofNullable(user.getRealName()).orElse("")
                ));
                data.put("identities", identities);
                return data;
            }
        }
        data.put("sessionToken", jwtService.issueSessionToken(openid));
        data.put("openidBound", mask(openid));
        data.put("needPhoneAuth", true);
        return data;
    }

    @Transactional
    public Map<String, Object> phoneMatch(String sessionToken, String phoneCode) {
        String openid;
        try {
            var claims = jwtService.parse(sessionToken);
            if (!"SESSION".equals(claims.get("type", String.class))) {
                throw BizException.of(ErrorCodes.UNAUTHORIZED, "会话无效");
            }
            openid = claims.get("openid", String.class);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.of(ErrorCodes.UNAUTHORIZED, "会话无效或已过期");
        }
        String mobile = wechatMiniApi.resolveMobile(phoneCode);

        SysUser byMobile = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, mobile));
        UserWechat existingWx = userWechatMapper.selectOne(new LambdaQueryWrapper<UserWechat>()
                .eq(UserWechat::getAppId, wxProperties.getAppId())
                .eq(UserWechat::getOpenid, openid));

        SysUser user;
        if (byMobile != null) {
            if (existingWx != null && !existingWx.getUserId().equals(byMobile.getId())) {
                throw BizException.of(ErrorCodes.MOBILE_TAKEN, "请联系物业管理人员");
            }
            UserWechat otherWx = userWechatMapper.selectOne(new LambdaQueryWrapper<UserWechat>()
                    .eq(UserWechat::getUserId, byMobile.getId())
                    .eq(UserWechat::getAppId, wxProperties.getAppId()));
            if (otherWx != null && !otherWx.getOpenid().equals(openid)) {
                throw BizException.of(ErrorCodes.MOBILE_TAKEN, "请联系物业管理人员");
            }
            user = byMobile;
            if (existingWx == null) {
                bindWechat(user.getId(), openid);
            }
        } else if (existingWx != null) {
            user = sysUserMapper.selectById(existingWx.getUserId());
            if (user == null) {
                // 用户已删、微信绑定残留（如管理端删游客未清绑定）→ 清孤儿绑定后重建
                userWechatMapper.deleteById(existingWx.getId());
                user = new SysUser();
                user.setMobile(mobile);
                user.setRealName("游客");
                user.setRegisterSource("APP_GUEST");
                user.setStatus(1);
                user.setIsPlatformAdmin(0);
                user.setCreatedAt(LocalDateTime.now());
                user.setUpdatedAt(LocalDateTime.now());
                sysUserMapper.insert(user);
                bindWechat(user.getId(), openid);
            } else {
                if (user.getMobile() != null && !user.getMobile().equals(mobile)) {
                    SysUser conflict = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, mobile));
                    if (conflict != null) {
                        throw BizException.of(ErrorCodes.MOBILE_TAKEN, "请联系物业管理人员");
                    }
                }
                user.setMobile(mobile);
                if (!StringUtils.hasText(user.getRegisterSource())) {
                    user.setRegisterSource("APP_GUEST");
                }
                if (!StringUtils.hasText(user.getRealName())) {
                    user.setRealName("游客");
                }
                user.setUpdatedAt(LocalDateTime.now());
                sysUserMapper.updateById(user);
            }
        } else {
            user = new SysUser();
            user.setMobile(mobile);
            user.setRealName("游客");
            user.setRegisterSource("APP_GUEST");
            user.setStatus(1);
            user.setIsPlatformAdmin(0);
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            sysUserMapper.insert(user);
            bindWechat(user.getId(), openid);
        }

        List<Map<String, Object>> identities = listIdentities(user.getId());
        boolean needChoose = identities.size() > 1;
        AuthUser auth = AuthUser.builder()
                .userId(user.getId())
                .platformAdmin(Objects.equals(user.getIsPlatformAdmin(), 1))
                .build();
        if (!needChoose && identities.size() == 1) {
            Map<String, Object> only = identities.get(0);
            auth.setIdentityType((String) only.get("identityType"));
            Object cid = only.get("communityId");
            if (cid instanceof Number n) {
                auth.setCommunityId(n.longValue());
            }
            if (only.get("staffRole") != null) {
                auth.setStaffRole((String) only.get("staffRole"));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", jwtService.issue(auth));
        data.put("user", Map.of(
                "id", user.getId(),
                "mobile", Optional.ofNullable(user.getMobile()).orElse(""),
                "realName", Optional.ofNullable(user.getRealName()).orElse("")
        ));
        data.put("identities", identities);
        data.put("needChooseIdentity", needChoose);
        return data;
    }

    public List<Map<String, Object>> listIdentities(Long userId) {
        List<Map<String, Object>> list = new ArrayList<>();
        List<RoomOccupant> occupants = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getUserId, userId)
                .eq(RoomOccupant::getStatus, "ACTIVE")
                .orderByAsc(RoomOccupant::getId));
        // 同房多条 ACTIVE 时按角色优先级保留一条（防御脏数据）
        Map<Long, RoomOccupant> bestByRoom = new LinkedHashMap<>();
        for (RoomOccupant o : occupants) {
            RoomOccupant prev = bestByRoom.get(o.getRoomId());
            if (prev == null || RoomOccupantBindService.compareRolePriority(o.getResidentRole(), prev.getResidentRole()) < 0) {
                bestByRoom.put(o.getRoomId(), o);
            }
        }
        Map<Long, List<Map<String, Object>>> roomsByCommunity = new LinkedHashMap<>();
        for (RoomOccupant o : bestByRoom.values()) {
            Room room = roomMapper.selectById(o.getRoomId());
            Map<String, Object> roomRow = new LinkedHashMap<>();
            roomRow.put("roomId", o.getRoomId());
            roomRow.put("roomNo", room == null ? "" : room.getRoomNo());
            roomRow.put("address", room == null ? "" : occupantQueryService.buildAddress(room));
            roomRow.put("residentRole", o.getResidentRole());
            roomsByCommunity.computeIfAbsent(o.getCommunityId(), k -> new ArrayList<>()).add(roomRow);
        }
        for (Map.Entry<Long, List<Map<String, Object>>> e : roomsByCommunity.entrySet()) {
            Community c = communityMapper.selectById(e.getKey());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("identityType", "RESIDENT");
            item.put("communityId", e.getKey());
            item.put("communityName", c == null ? "" : c.getName());
            item.put("rooms", e.getValue());
            list.add(item);
        }

        List<CommitteeMember> committees = committeeMemberMapper.selectList(new LambdaQueryWrapper<CommitteeMember>()
                .eq(CommitteeMember::getUserId, userId)
                .eq(CommitteeMember::getStatus, "ACTIVE"));
        for (CommitteeMember cm : committees) {
            Community c = communityMapper.selectById(cm.getCommunityId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("identityType", "COMMITTEE");
            item.put("communityId", cm.getCommunityId());
            item.put("communityName", c == null ? "" : c.getName());
            item.put("committeeTitle", cm.getTitle());
            list.add(item);
        }

        List<StaffCommunity> staffs = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getUserId, userId)
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        Map<Long, List<String>> staffRolesByCommunity = new LinkedHashMap<>();
        for (StaffCommunity sc : staffs) {
            String role = StaffRoles.normalize(sc.getStaffRole());
            if (role == null) {
                continue;
            }
            staffRolesByCommunity.computeIfAbsent(sc.getCommunityId(), k -> new ArrayList<>()).add(role);
        }
        for (Map.Entry<Long, List<String>> e : staffRolesByCommunity.entrySet()) {
            Community c = communityMapper.selectById(e.getKey());
            List<String> roles = StaffRoles.sortRoles(e.getValue());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("identityType", "STAFF");
            item.put("communityId", e.getKey());
            item.put("communityName", c == null ? "" : c.getName());
            item.put("staffRole", StaffRoles.primary(roles));
            item.put("staffRoles", roles);
            item.put("roleLabels", StaffRoles.labelsJoined(roles));
            list.add(item);
        }

        SysUser user = sysUserMapper.selectById(userId);
        if (user != null && Objects.equals(user.getIsPlatformAdmin(), 1)) {
            list.add(Map.of("identityType", "PLATFORM"));
        }

        if (occupants.isEmpty()) {
            Map<String, Object> guest = new LinkedHashMap<>();
            guest.put("identityType", "GUEST");
            list.add(guest);
        }
        return list;
    }

    /**
     * 平台管理员进入指定小区工作台：保持 PLATFORM 身份，JWT 写入 communityId，
     * 以便调用 /staff/** 接口（权限不低于物业经理）。
     */
    public Map<String, Object> platformEnterCommunity(Long communityId) {
        AuthUser cur = AuthContext.require();
        if (!cur.isPlatformAdmin() && !"PLATFORM".equals(cur.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台管理员权限");
        }
        Community c = communityMapper.selectById(communityId);
        if (c == null || c.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "小区不存在");
        }
        AuthUser auth = AuthUser.builder()
                .userId(cur.getUserId())
                .identityType("PLATFORM")
                .communityId(communityId)
                .platformAdmin(true)
                .staffRole("PROPERTY_MANAGER")
                .build();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", jwtService.issue(auth));
        data.put("community", Map.of(
                "id", c.getId(),
                "name", Optional.ofNullable(c.getName()).orElse("")
        ));
        return data;
    }

    /** 退出小区工作台，回到平台全局（无 communityId）。 */
    public Map<String, Object> platformExitCommunity() {
        AuthUser cur = AuthContext.require();
        if (!cur.isPlatformAdmin() && !"PLATFORM".equals(cur.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台管理员权限");
        }
        AuthUser auth = AuthUser.builder()
                .userId(cur.getUserId())
                .identityType("PLATFORM")
                .platformAdmin(true)
                .build();
        return Map.of("token", jwtService.issue(auth));
    }

    public Map<String, Object> switchContext(Long userId, String identityType, Long communityId, Long roomId) {
        List<Map<String, Object>> identities = listIdentities(userId);
        final Long requestedCommunityId = communityId;
        boolean ok = identities.stream().anyMatch(i -> {
            if (!identityType.equals(i.get("identityType"))) {
                return false;
            }
            if ("GUEST".equals(identityType) || "PLATFORM".equals(identityType)) {
                return true;
            }
            Object cid = i.get("communityId");
            return cid != null && requestedCommunityId != null
                    && ((Number) cid).longValue() == requestedCommunityId;
        });
        if (!ok) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "目标身份不可用");
        }
        if ("GUEST".equals(identityType)) {
            long active = roomOccupantMapper.selectCount(new LambdaQueryWrapper<RoomOccupant>()
                    .eq(RoomOccupant::getUserId, userId)
                    .eq(RoomOccupant::getStatus, "ACTIVE"));
            if (active > 0) {
                throw BizException.of(ErrorCodes.GUEST_FORBIDDEN, "已有住户绑定，不可再使用游客身份");
            }
        }
        Long resolvedCommunityId = communityId;
        Long resolvedRoomId = roomId;
        String staffRole = null;
        if ("STAFF".equals(identityType) && resolvedCommunityId != null) {
            List<StaffCommunity> scs = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                    .eq(StaffCommunity::getUserId, userId)
                    .eq(StaffCommunity::getCommunityId, resolvedCommunityId)
                    .eq(StaffCommunity::getStatus, "ACTIVE"));
            List<String> roles = new ArrayList<>();
            for (StaffCommunity sc : scs) {
                String r = StaffRoles.normalize(sc.getStaffRole());
                if (r != null) {
                    roles.add(r);
                }
            }
            staffRole = StaffRoles.primary(roles);
        }
        if ("RESIDENT".equals(identityType)) {
            if (resolvedRoomId == null && resolvedCommunityId != null) {
                final Long cidForPick = resolvedCommunityId;
                // 未指定房屋时取该小区名下第一套，保证 JWT 带 roomId，便于账单/报修等按房过滤
                resolvedRoomId = identities.stream()
                        .filter(i -> "RESIDENT".equals(i.get("identityType"))
                                && i.get("communityId") != null
                                && ((Number) i.get("communityId")).longValue() == cidForPick)
                        .map(i -> (List<?>) i.get("rooms"))
                        .filter(rooms -> rooms != null && !rooms.isEmpty())
                        .map(rooms -> (Map<?, ?>) rooms.get(0))
                        .map(r -> ((Number) r.get("roomId")).longValue())
                        .findFirst()
                        .orElse(null);
            }
            if (resolvedRoomId != null) {
                List<RoomOccupant> occs = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                        .eq(RoomOccupant::getUserId, userId)
                        .eq(RoomOccupant::getRoomId, resolvedRoomId)
                        .eq(RoomOccupant::getStatus, "ACTIVE")
                        .orderByAsc(RoomOccupant::getId));
                if (occs.isEmpty()) {
                    throw BizException.of(ErrorCodes.FORBIDDEN, "房屋不在名下");
                }
                // 以房屋所属小区校正 JWT，避免跨小区脏 communityId
                Room room = roomMapper.selectById(resolvedRoomId);
                if (room == null || room.getDeletedAt() != null) {
                    throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在");
                }
                resolvedCommunityId = room.getCommunityId();
                final Long roomCid = resolvedCommunityId;
                boolean roomCommunityOk = identities.stream().anyMatch(i ->
                        "RESIDENT".equals(i.get("identityType"))
                                && i.get("communityId") != null
                                && ((Number) i.get("communityId")).longValue() == roomCid);
                if (!roomCommunityOk) {
                    throw BizException.of(ErrorCodes.FORBIDDEN, "房屋所属小区不在可用身份中");
                }
            }
        }
        SysUser user = sysUserMapper.selectById(userId);
        AuthUser cur = AuthContext.get();
        AuthUser auth = AuthUser.builder()
                .userId(userId)
                .identityType(identityType)
                .communityId(resolvedCommunityId)
                .roomId(resolvedRoomId)
                .staffRole(staffRole)
                .platformAdmin(user != null && Objects.equals(user.getIsPlatformAdmin(), 1))
                .webStaffSession(cur != null && cur.isWebStaffSession())
                .appPasswordSession(cur != null && cur.isAppPasswordSession())
                .build();
        return Map.of("token", jwtService.issue(auth));
    }

    /** 当前 JWT 上下文摘要，供小程序弹窗高亮「当前身份」。 */
    public Map<String, Object> currentContext() {
        AuthUser a = AuthContext.require();
        Map<String, Object> cur = new LinkedHashMap<>();
        if (a.getIdentityType() != null) {
            cur.put("identityType", a.getIdentityType());
        }
        if (a.getCommunityId() != null) {
            cur.put("communityId", a.getCommunityId());
        }
        if (a.getRoomId() != null) {
            cur.put("roomId", a.getRoomId());
        }
        if (a.getStaffRole() != null) {
            cur.put("staffRole", a.getStaffRole());
        }
        return cur;
    }

    /** 小程序首页问候用语：真实姓名；空则回退手机尾号。 */
    public Map<String, Object> currentUserBrief() {
        SysUser user = sysUserMapper.selectById(AuthContext.require().getUserId());
        Map<String, Object> m = new LinkedHashMap<>();
        if (user == null) {
            return m;
        }
        m.put("id", user.getId());
        m.put("realName", Optional.ofNullable(user.getRealName()).orElse(""));
        m.put("mobile", Optional.ofNullable(user.getMobile()).orElse(""));
        return m;
    }

    /**
     * 本人修改显示姓名（小程序「我的」；平台改名仍走人员清单）。
     */
    @Transactional
    public Map<String, Object> updateProfile(String realName) {
        if (realName == null || realName.trim().isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "姓名不能为空");
        }
        String name = realName.trim();
        if (name.length() > 32) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "姓名最多 32 字");
        }
        SysUser user = sysUserMapper.selectById(AuthContext.require().getUserId());
        if (user == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在");
        }
        user.setRealName(name);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        return currentUserBrief();
    }

    @Transactional
    public Map<String, Object> staffBind(Long userId, String mobile, String password) {
        SysUser account = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getMobile, mobile)
                .eq(SysUser::getStatus, 1));
        if (account == null || account.getPasswordHash() == null
                || !passwordEncoder.matches(password, account.getPasswordHash())) {
            throw BizException.of(ErrorCodes.UNAUTHORIZED, "物业账号校验失败");
        }
        long staffCount = staffCommunityMapper.selectCount(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getUserId, account.getId())
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        if (staffCount == 0) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "非物业人员");
        }
        // 将当前微信用户与物业账号对齐：若 userId != account.id，需已是同一人或合并策略简化为要求手机号匹配当前用户
        SysUser current = sysUserMapper.selectById(userId);
        if (current == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在");
        }
        if (current.getMobile() != null && !current.getMobile().equals(mobile) && !current.getId().equals(account.getId())) {
            throw BizException.of(ErrorCodes.MOBILE_TAKEN, "请联系物业管理人员");
        }
        if (!current.getId().equals(account.getId())) {
            // 开发简化：要求当前用户 mobile 已是该物业手机
            if (current.getMobile() == null) {
                current.setMobile(mobile);
                current.setPasswordHash(account.getPasswordHash());
                current.setRealName(account.getRealName());
                current.setUpdatedAt(LocalDateTime.now());
                sysUserMapper.updateById(current);
            } else {
                throw BizException.of(ErrorCodes.BAD_PARAM, "请使用物业手机号授权登录后再绑定");
            }
        }
        AuthUser auth = AuthUser.builder().userId(current.getId()).platformAdmin(false).build();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", jwtService.issue(auth));
        data.put("user", Map.of(
                "id", current.getId(),
                "mobile", Optional.ofNullable(current.getMobile()).orElse(""),
                "realName", Optional.ofNullable(current.getRealName()).orElse("")
        ));
        data.put("identities", listIdentities(current.getId()));
        return data;
    }

    private void bindWechat(Long userId, String openid) {
        UserWechat wx = new UserWechat();
        wx.setUserId(userId);
        wx.setAppId(wxProperties.getAppId());
        wx.setOpenid(openid);
        wx.setCreatedAt(LocalDateTime.now());
        wx.setUpdatedAt(LocalDateTime.now());
        userWechatMapper.insert(wx);
    }

    private void touchLogin(SysUser user) {
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
    }

    private String mask(String openid) {
        if (openid == null || openid.length() < 8) {
            return "***";
        }
        return openid.substring(0, 4) + "****" + openid.substring(openid.length() - 4);
    }
}
