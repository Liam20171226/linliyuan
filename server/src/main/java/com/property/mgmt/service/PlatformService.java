package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.DefaultWebPassword;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.common.StaffRoles;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PlatformService {

    private final CommunityMapper communityMapper;
    private final SysUserMapper sysUserMapper;
    private final StaffCommunityMapper staffCommunityMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final FloorMapper floorMapper;
    private final RoomMapper roomMapper;
    private final RoomOccupantMapper roomOccupantMapper;
    private final CommunityHouseTypeMapper houseTypeMapper;
    private final ParkingSpaceMapper parkingSpaceMapper;
    private final CommitteeMemberMapper committeeMemberMapper;
    private final UserWechatMapper userWechatMapper;
    private final AuditLogMapper auditLogMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final WechatBindService wechatBindService;

    public void requirePlatform() {
        var u = AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台管理员权限");
        }
    }

    @Transactional
    public Map<String, Object> createCommunity(String name, String address, String intro, String contactPhone, Integer hasFormalCommittee,
                                               String provinceCode, String provinceName, String cityCode, String cityName,
                                               String districtCode, String districtName, String addressDetail) {
        requirePlatform();
        Community c = new Community();
        c.setName(name);
        c.setAddress(RegionCatalog.resolveStoredAddress(address, provinceCode, provinceName,
                cityCode, cityName, districtCode, districtName, addressDetail));
        c.setIntro(intro);
        c.setContactPhone(contactPhone);
        c.setHasFormalCommittee(hasFormalCommittee == null ? 0 : hasFormalCommittee);
        c.setStatus(1);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        communityMapper.insert(c);
        return Map.of("id", c.getId());
    }

    public Map<String, Object> listCommunities(String name, String provinceName, String cityName, String districtName,
                                               int page, int pageSize) {
        requirePlatform();
        LambdaQueryWrapper<Community> q = new LambdaQueryWrapper<Community>()
                .isNull(Community::getDeletedAt)
                .orderByDesc(Community::getId);
        if (StringUtils.hasText(name)) {
            q.like(Community::getName, name.trim());
        }
        if (StringUtils.hasText(provinceName)) {
            String p = provinceName.trim();
            String c = StringUtils.hasText(cityName) ? cityName.trim() : "";
            String d = StringUtils.hasText(districtName) ? districtName.trim() : "";
            String composed = RegionCatalog.composeAddress(p, c, d, null);
            String raw = p + c + d;
            if (raw.equals(composed)) {
                q.likeRight(Community::getAddress, composed);
            } else {
                q.and(w -> w.likeRight(Community::getAddress, composed)
                        .or()
                        .likeRight(Community::getAddress, raw));
            }
        }
        Page<Community> pageResult = communityMapper.selectPage(new Page<>(page, pageSize), q);
        return Map.of("list", pageResult.getRecords(), "total", pageResult.getTotal(), "page", page, "pageSize", pageSize);
    }

    public Community getCommunity(Long id) {
        requirePlatform();
        Community c = communityMapper.selectById(id);
        if (c == null || c.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "小区不存在");
        }
        return c;
    }

    public void updateCommunity(Long id, String name, String address, String intro, String contactPhone, Integer hasFormalCommittee,
                                String provinceCode, String provinceName, String cityCode, String cityName,
                                String districtCode, String districtName, String addressDetail) {
        requirePlatform();
        Community c = getCommunity(id);
        if (StringUtils.hasText(name)) {
            c.setName(name);
        }
        String resolved = RegionCatalog.resolveStoredAddress(address, provinceCode, provinceName,
                cityCode, cityName, districtCode, districtName, addressDetail);
        if (resolved != null) {
            c.setAddress(resolved);
        }
        if (intro != null) {
            c.setIntro(intro);
        }
        if (contactPhone != null) {
            c.setContactPhone(contactPhone);
        }
        if (hasFormalCommittee != null) {
            c.setHasFormalCommittee(hasFormalCommittee);
        }
        c.setUpdatedAt(LocalDateTime.now());
        communityMapper.updateById(c);
    }

    /**
     * Physically delete community and related rows. If ACTIVE occupants exist and force=false, reject.
     */
    @Transactional
    public Map<String, Object> deleteCommunity(Long id, boolean force) {
        requirePlatform();
        Community c = getCommunity(id);
        long activeOcc = roomOccupantMapper.selectCount(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getCommunityId, id)
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        if (activeOcc > 0 && !force) {
            throw BizException.of(ErrorCodes.BAD_PARAM,
                    "该小区尚有 " + activeOcc + " 名有效住户绑定，确认删除将永久删除小区及下属数据");
        }

        String name = c.getName();
        int occupantRows = countByCommunity("room_occupant", id);
        int staffRows = countByCommunity("staff_community", id);
        purgeCommunityRows(id);
        communityMapper.deleteById(id);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("communityId", id);
        detail.put("communityName", name);
        detail.put("force", force);
        detail.put("hardDelete", true);
        detail.put("occupantRows", occupantRows);
        detail.put("staffRows", staffRows);
        writeAudit(null, AuthContext.require().getUserId(), "ADMIN_DELETE_COMMUNITY", "community", id, detail);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("hardDelete", true);
        out.put("occupantRows", occupantRows);
        out.put("staffRows", staffRows);
        return out;
    }

    private int countByCommunity(String table, Long communityId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE community_id = ?", Integer.class, communityId);
        return n == null ? 0 : n;
    }

    private void purgeCommunityRows(Long communityId) {
        jdbcTemplate.update("DELETE FROM vote_ballot WHERE vote_id IN (SELECT id FROM vote WHERE community_id = ?)", communityId);
        jdbcTemplate.update("DELETE FROM vote_option WHERE vote_id IN (SELECT id FROM vote WHERE community_id = ?)", communityId);
        jdbcTemplate.update("DELETE FROM vote WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM service_ticket WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM notice WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM public_revenue_item WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM finance_entry WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM payment_record WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM bill_line WHERE bill_id IN (SELECT id FROM bill WHERE community_id = ?)", communityId);
        jdbcTemplate.update("DELETE FROM bill WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM bill_meter_upload WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM fee_item_price_rule WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM fee_item WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM prepaid_plan_item WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM prepaid_plan WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM prepaid_ledger WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM prepaid_account WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM payment_config WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM room_vehicle WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM room_change_application WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM auth_application WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM room_occupant WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM parking_space WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM room WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM floor WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM unit WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM building WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM community_house_type WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM staff_community WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM committee_member WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM import_batch WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM attachment WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM todo_item WHERE community_id = ?", communityId);
        jdbcTemplate.update("DELETE FROM subscribe_notify_log WHERE community_id = ?", communityId);
        jdbcTemplate.update("UPDATE community_application SET community_id = NULL WHERE community_id = ?", communityId);
    }

    private void writeAudit(Long communityId, Long actorId, String action, String targetType,
                            Long targetId, Map<String, Object> detail) {
        AuditLog log = new AuditLog();
        log.setCommunityId(communityId);
        log.setActorId(actorId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        try {
            log.setDetailJson(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            log.setDetailJson("{}");
        }
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    @Transactional
    public Map<String, Object> createStaffUser(String mobile, String password, String realName) {
        requirePlatform();
        SysUser exist = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getMobile, mobile));
        if (exist != null) {
            throw BizException.of(ErrorCodes.MOBILE_TAKEN, "手机号已被占用");
        }
        SysUser user = new SysUser();
        user.setMobile(mobile);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setMustChangePassword(1);
        user.setRealName(realName);
        user.setIsPlatformAdmin(0);
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.insert(user);
        return Map.of("id", user.getId());
    }

    @Transactional
    public void assignStaff(Long communityId, Long userId, String staffRole) {
        requirePlatform();
        getCommunity(communityId);
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在");
        }
        // PROPERTY_ADMIN 经 normalize 视为客服；平台人员弹窗仍仅应传 PROPERTY_MANAGER
        ensureStaffRole(communityId, userId, staffRole);
    }

    /**
     * 写入或复活一条岗位。同一小区同一人可有多岗；经理岗全小区唯一。
     */
    public void ensureStaffRole(Long communityId, Long userId, String rawRole) {
        String staffRole = StaffRoles.normalize(rawRole);
        StaffRoles.requireKnown(staffRole);

        StaffCommunity existing = staffCommunityMapper.selectOne(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, communityId)
                .eq(StaffCommunity::getUserId, userId)
                .eq(StaffCommunity::getStaffRole, staffRole));
        if (existing != null && "ACTIVE".equals(existing.getStatus())) {
            return;
        }

        if (StaffRoles.isManager(staffRole)) {
            long managers = staffCommunityMapper.selectCount(new LambdaQueryWrapper<StaffCommunity>()
                    .eq(StaffCommunity::getCommunityId, communityId)
                    .eq(StaffCommunity::getStaffRole, StaffRoles.PROPERTY_MANAGER)
                    .eq(StaffCommunity::getStatus, "ACTIVE"));
            if (managers > 0) {
                throw BizException.of(ErrorCodes.MANAGER_EXISTS, "小区已有有效物业经理");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setStatus("ACTIVE");
            existing.setUpdatedAt(now);
            staffCommunityMapper.updateById(existing);
            return;
        }
        StaffCommunity sc = new StaffCommunity();
        sc.setCommunityId(communityId);
        sc.setUserId(userId);
        sc.setStaffRole(staffRole);
        sc.setStatus("ACTIVE");
        sc.setCreatedAt(now);
        sc.setUpdatedAt(now);
        staffCommunityMapper.insert(sc);
    }

    public List<StaffCommunity> listStaff(Long communityId) {
        requirePlatform();
        return staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, communityId)
                .orderByDesc(StaffCommunity::getId));
    }

    /** 按人聚合本小区岗位（一人多岗合并为一行） */
    public List<Map<String, Object>> listStaffDetail(Long communityId) {
        requirePlatform();
        return groupStaffByUser(listStaff(communityId));
    }

    private List<Map<String, Object>> groupStaffByUser(List<StaffCommunity> rows) {
        Map<Long, Map<String, Object>> byUser = new LinkedHashMap<>();
        for (StaffCommunity sc : rows) {
            Map<String, Object> m = byUser.computeIfAbsent(sc.getUserId(), uid -> {
                SysUser u = sysUserMapper.selectById(uid);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("userId", uid);
                row.put("communityId", sc.getCommunityId());
                row.put("mobile", u == null ? null : u.getMobile());
                row.put("realName", u == null ? null : u.getRealName());
                row.put("userStatus", u == null ? null : u.getStatus());
                row.put("roles", new ArrayList<String>());
                row.put("status", "INACTIVE");
                return row;
            });
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) m.get("roles");
            String role = StaffRoles.normalize(sc.getStaffRole());
            if (role != null && !roles.contains(role)) {
                roles.add(role);
            }
            if ("ACTIVE".equals(sc.getStatus())) {
                m.put("status", "ACTIVE");
            }
            m.put("id", sc.getId());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : byUser.values()) {
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) m.get("roles");
            List<String> sorted = StaffRoles.sortRoles(roles);
            m.put("roles", sorted);
            m.put("staffRoles", sorted);
            m.put("staffRole", StaffRoles.primary(sorted));
            m.put("roleLabels", StaffRoles.labelsJoined(sorted));
            out.add(m);
        }
        return out;
    }

    @Transactional
    public void resetUserPassword(String mobile, String newPassword) {
        requirePlatform();
        if (!StringUtils.hasText(mobile)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "手机号必填");
        }
        // 未指定新密码时使用默认密码
        String pwd = StringUtils.hasText(newPassword) ? newPassword.trim() : DefaultWebPassword.VALUE;
        if (pwd.length() < 6) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "新密码至少 6 位");
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getMobile, mobile.trim()));
        if (user == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在");
        }
        if (Objects.equals(user.getIsPlatformAdmin(), 1)) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "不可通过此接口重置平台管理员密码");
        }
        user.setPasswordHash(passwordEncoder.encode(pwd));
        user.setMustChangePassword(1);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        writeAudit(null, AuthContext.require().getUserId(), "ADMIN_RESET_PASSWORD", "sys_user", user.getId(),
                Map.of("mobile", user.getMobile(), "mustChangePassword", true,
                        "usedDefaultPassword", !StringUtils.hasText(newPassword)));
    }

    /**
     * 平台人员清单：仅曾有/现有物业任职（staff_community）的账号；不含纯住户/业委会。
     */
    public Map<String, Object> listUsers(String mobile, String realName, Long communityId, Integer status,
                                         boolean managerOnly, int page, int pageSize) {
        requirePlatform();
        java.util.Set<Long> staffUserIds = new java.util.LinkedHashSet<>();
        LambdaQueryWrapper<StaffCommunity> sq = new LambdaQueryWrapper<StaffCommunity>();
        if (communityId != null) {
            sq.eq(StaffCommunity::getCommunityId, communityId);
        }
        // managerOnly：平台仅任命物业经理，列表只保留有在任经理岗的账号，不掺入一线执行岗
        if (managerOnly) {
            sq.eq(StaffCommunity::getStaffRole, StaffRoles.PROPERTY_MANAGER)
                    .eq(StaffCommunity::getStatus, "ACTIVE");
        }
        for (StaffCommunity sc : staffCommunityMapper.selectList(sq)) {
            staffUserIds.add(sc.getUserId());
        }
        if (staffUserIds.isEmpty()) {
            return Map.of("list", List.of(), "total", 0L, "page", page, "pageSize", pageSize);
        }

        LambdaQueryWrapper<SysUser> q = new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getId, staffUserIds)
                .isNotNull(SysUser::getMobile)
                .ne(SysUser::getMobile, "")
                .like(StringUtils.hasText(mobile), SysUser::getMobile, StringUtils.hasText(mobile) ? mobile.trim() : null)
                .like(StringUtils.hasText(realName), SysUser::getRealName, StringUtils.hasText(realName) ? realName.trim() : null)
                .eq(status != null, SysUser::getStatus, status)
                .orderByDesc(SysUser::getId);
        Page<SysUser> p = sysUserMapper.selectPage(new Page<>(page, pageSize), q);
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysUser u : p.getRecords()) {
            list.add(toUserRow(u));
        }
        return Map.of("list", list, "total", p.getTotal(), "page", page, "pageSize", pageSize);
    }

    @Transactional
    public void updateUser(Long id, String realName, String mobile) {
        requirePlatform();
        SysUser user = requireNonPlatformUser(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("userId", id);
        detail.put("oldMobile", user.getMobile());
        boolean mobileChanged = false;
        if (realName != null) {
            user.setRealName(realName.trim().isEmpty() ? null : realName.trim());
        }
        if (mobile != null) {
            String next = mobile.trim();
            if (!next.matches("\\d{11}")) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "手机号须为 11 位数字");
            }
            SysUser taken = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getMobile, next)
                    .ne(SysUser::getId, id));
            if (taken != null) {
                throw BizException.of(ErrorCodes.MOBILE_TAKEN, "手机号已被占用");
            }
            String old = Optional.ofNullable(user.getMobile()).orElse("");
            if (!next.equals(old)) {
                user.setMobile(next);
                mobileChanged = true;
            }
        }
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        if (mobileChanged) {
            int unbound = wechatBindService.unbindAllForUser(id);
            detail.put("wechatUnbound", true);
            detail.put("wechatUnboundRows", unbound);
        }
        detail.put("mobile", user.getMobile());
        detail.put("realName", user.getRealName());
        writeAudit(null, AuthContext.require().getUserId(), "ADMIN_UPDATE_USER", "sys_user", id, detail);
    }

    /**
     * Re-enable a disabled account (status=1). Does not auto-restore INACTIVE affiliations.
     */
    @Transactional
    public Map<String, Object> enableUser(Long id) {
        requirePlatform();
        SysUser user = requireNonPlatformUser(id);
        if (Objects.equals(user.getStatus(), 1)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "账号已是正常状态");
        }
        user.setStatus(1);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        writeAudit(null, AuthContext.require().getUserId(), "ADMIN_ENABLE_USER", "sys_user", id,
                Map.of("userId", id, "mobile", user.getMobile()));
        return Map.of("id", id, "status", 1);
    }

    /**
     * Disable account (status=0): cannot login. Does not physically delete the row.
     * Cascades ACTIVE staff / occupant / committee to INACTIVE.
     */
    @Transactional
    public Map<String, Object> disableUser(Long id) {
        requirePlatform();
        SysUser user = requireNonPlatformUser(id);
        if (Objects.equals(AuthContext.require().getUserId(), id)) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "不可停用当前登录账号");
        }
        if (Objects.equals(user.getStatus(), 0)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "账号已停用");
        }
        LocalDateTime now = LocalDateTime.now();
        int staffOff = 0;
        int occOff = 0;
        int committeeOff = 0;

        List<StaffCommunity> staffs = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getUserId, id)
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        for (StaffCommunity sc : staffs) {
            assertCanDeactivateStaffRole(sc);
        }
        for (StaffCommunity sc : staffs) {
            sc.setStatus("INACTIVE");
            sc.setUpdatedAt(now);
            staffCommunityMapper.updateById(sc);
            staffOff++;
        }
        List<RoomOccupant> occs = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getUserId, id)
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        for (RoomOccupant o : occs) {
            o.setStatus("INACTIVE");
            o.setUpdatedAt(now);
            roomOccupantMapper.updateById(o);
            occOff++;
        }
        List<CommitteeMember> cms = committeeMemberMapper.selectList(new LambdaQueryWrapper<CommitteeMember>()
                .eq(CommitteeMember::getUserId, id)
                .eq(CommitteeMember::getStatus, "ACTIVE"));
        for (CommitteeMember cm : cms) {
            cm.setStatus("INACTIVE");
            cm.setUpdatedAt(now);
            committeeMemberMapper.updateById(cm);
            committeeOff++;
        }

        user.setStatus(0);
        user.setUpdatedAt(now);
        sysUserMapper.updateById(user);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("userId", id);
        detail.put("mobile", user.getMobile());
        detail.put("staffDeactivated", staffOff);
        detail.put("occupantsReleased", occOff);
        detail.put("committeeDeactivated", committeeOff);
        writeAudit(null, AuthContext.require().getUserId(), "ADMIN_DISABLE_USER", "sys_user", id, detail);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("staffDeactivated", staffOff);
        out.put("occupantsReleased", occOff);
        out.put("committeeDeactivated", committeeOff);
        return out;
    }

    /**
     * Physical delete after disable only. Removes identity bindings + wechat + user row.
     * Historical bill/repair rows may retain orphan user ids (no FK hard-block in H2).
     */
    @Transactional
    public Map<String, Object> purgeUser(Long id) {
        requirePlatform();
        SysUser user = requireNonPlatformUser(id);
        if (Objects.equals(AuthContext.require().getUserId(), id)) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "不可删除当前登录账号");
        }
        if (!Objects.equals(user.getStatus(), 0)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请先停用账号后再删除");
        }
        String mobile = user.getMobile();
        int staffRows = staffCommunityMapper.delete(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getUserId, id));
        int occRows = roomOccupantMapper.delete(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getUserId, id));
        int cmRows = committeeMemberMapper.delete(new LambdaQueryWrapper<CommitteeMember>()
                .eq(CommitteeMember::getUserId, id));
        int wxRows = userWechatMapper.delete(new LambdaQueryWrapper<UserWechat>()
                .eq(UserWechat::getUserId, id));
        sysUserMapper.deleteById(id);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("userId", id);
        detail.put("mobile", mobile);
        detail.put("staffRowsDeleted", staffRows);
        detail.put("occupantRowsDeleted", occRows);
        detail.put("committeeRowsDeleted", cmRows);
        detail.put("wechatRowsDeleted", wxRows);
        writeAudit(null, AuthContext.require().getUserId(), "ADMIN_PURGE_USER", "sys_user", id, detail);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("mobile", mobile);
        out.put("purged", true);
        return out;
    }

    /**
     * 平台人员弹窗：仅同步多社区<strong>物业经理</strong>岗位（一线岗由物业经理在本小区任命）。
     * 未出现在 items 中的 ACTIVE 经理任职会被停用；同人其它一线岗保留不动。
     */
    @Transactional
    public Map<String, Object> setUserCommunityRolesBatch(Long userId, List<Map<String, Object>> items) {
        requirePlatform();
        SysUser user = requireNonPlatformUser(userId);
        if (!Objects.equals(user.getStatus(), 1)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "已停用账号不可改岗位，请先启用");
        }
        if (items == null) {
            items = List.of();
        }

        Set<Long> desiredManagerCommunities = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            if (item == null) {
                continue;
            }
            Object cidObj = item.get("communityId");
            if (cidObj == null) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "每条任职须指定 communityId");
            }
            Long communityId = cidObj instanceof Number ? ((Number) cidObj).longValue() : Long.parseLong(String.valueOf(cidObj));
            getCommunity(communityId);
            String staffRole = item.get("staffRole") == null ? null : String.valueOf(item.get("staffRole")).trim();
            String committeeTitle = item.get("committeeTitle") == null ? null : String.valueOf(item.get("committeeTitle")).trim();
            if (StringUtils.hasText(committeeTitle)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "业委会须在小区「住户」中任命，且须为本小区业主；平台人员仅配置物业岗位");
            }
            if (!StringUtils.hasText(staffRole)) {
                staffRole = StaffRoles.PROPERTY_MANAGER;
            }
            staffRole = StaffRoles.normalize(staffRole);
            if (!StaffRoles.isManager(staffRole)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "平台仅可任命物业经理；一线岗位请在物业后台「岗位人员」配置");
            }
            if (!desiredManagerCommunities.add(communityId)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "同一小区不可重复配置物业经理");
            }
        }

        for (Long communityId : desiredManagerCommunities) {
            ensureStaffRole(communityId, userId, StaffRoles.PROPERTY_MANAGER);
        }
        int staffCleared = 0;
        LocalDateTime now = LocalDateTime.now();
        List<StaffCommunity> activeManagers = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getUserId, userId)
                .eq(StaffCommunity::getStaffRole, StaffRoles.PROPERTY_MANAGER)
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        for (StaffCommunity sc : activeManagers) {
            if (!desiredManagerCommunities.contains(sc.getCommunityId())) {
                assertCanDeactivateStaffRole(sc);
                sc.setStatus("INACTIVE");
                sc.setUpdatedAt(now);
                staffCommunityMapper.updateById(sc);
                staffCleared++;
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("itemCount", items.size());
        detail.put("staffCleared", staffCleared);
        writeAudit(null, AuthContext.require().getUserId(), "ADMIN_SET_USER_ROLES", "sys_user", userId, detail);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("itemCount", items.size());
        out.put("staffCleared", staffCleared);
        return out;
    }

    /** 兼容旧单小区接口：仅同步该小区物业经理（会清掉此人其他小区的经理岗；不改一线岗/业委会） */
    @Transactional
    public Map<String, Object> setUserCommunityRoles(Long userId, Long communityId, String staffRole, String committeeTitle) {
        if (StringUtils.hasText(committeeTitle)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "业委会须在小区「住户」中任命，且须为本小区业主");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("communityId", communityId);
        item.put("staffRole", StringUtils.hasText(staffRole) ? staffRole : StaffRoles.PROPERTY_MANAGER);
        return setUserCommunityRolesBatch(userId, List.of(item));
    }

    /**
     * Set / replace property staff community for an active user（仅经理岗；清掉其它小区经理任职）。
     */
    @Transactional
    public Map<String, Object> setUserStaffCommunity(Long userId, Long communityId, String staffRole) {
        requirePlatform();
        SysUser user = requireNonPlatformUser(userId);
        if (!Objects.equals(user.getStatus(), 1)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "已停用账号不可改社区，请先使用正常账号或重建");
        }
        if (communityId == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "小区必选");
        }
        staffRole = StaffRoles.normalize(StringUtils.hasText(staffRole) ? staffRole : StaffRoles.PROPERTY_MANAGER);
        if (!StaffRoles.isManager(staffRole)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "平台仅可任命物业经理");
        }
        getCommunity(communityId);

        LocalDateTime now = LocalDateTime.now();
        int deactivated = 0;
        List<StaffCommunity> activeManagers = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getUserId, userId)
                .eq(StaffCommunity::getStaffRole, StaffRoles.PROPERTY_MANAGER)
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        boolean alreadyHere = false;
        for (StaffCommunity sc : activeManagers) {
            if (Objects.equals(sc.getCommunityId(), communityId)) {
                alreadyHere = true;
            } else {
                assertCanDeactivateStaffRole(sc);
                sc.setStatus("INACTIVE");
                sc.setUpdatedAt(now);
                staffCommunityMapper.updateById(sc);
                deactivated++;
            }
        }
        if (alreadyHere) {
            writeAudit(communityId, AuthContext.require().getUserId(), "ADMIN_SET_USER_COMMUNITY",
                    "sys_user", userId, Map.of("communityId", communityId, "staffRole", staffRole, "noop", true));
            return Map.of("userId", userId, "communityId", communityId, "staffRole", staffRole, "unchanged", true);
        }
        ensureStaffRole(communityId, userId, StaffRoles.PROPERTY_MANAGER);
        writeAudit(communityId, AuthContext.require().getUserId(), "ADMIN_SET_USER_COMMUNITY", "sys_user", userId,
                Map.of("communityId", communityId, "staffRole", staffRole, "otherStaffDeactivated", deactivated));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("communityId", communityId);
        out.put("staffRole", staffRole);
        out.put("otherStaffDeactivated", deactivated);
        return out;
    }

    private SysUser requireNonPlatformUser(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "用户不存在");
        }
        if (!StringUtils.hasText(user.getMobile())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "该账号无手机号，不在人员清单范围");
        }
        if (Objects.equals(user.getIsPlatformAdmin(), 1)) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "不可操作平台管理员账号");
        }
        return user;
    }

    private Map<String, Object> toUserRow(SysUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("mobile", u.getMobile());
        m.put("realName", u.getRealName());
        m.put("status", u.getStatus());
        m.put("isPlatformAdmin", u.getIsPlatformAdmin());
        m.put("hasPassword", StringUtils.hasText(u.getPasswordHash()));
        m.put("mustChangePassword", Objects.equals(u.getMustChangePassword(), 1));
        List<Map<String, Object>> affiliations = new ArrayList<>();
        List<String> summaryParts = new ArrayList<>();

        List<StaffCommunity> staffs = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getUserId, u.getId())
                .orderByDesc(StaffCommunity::getId));
        Map<Long, Map<String, Object>> byCommunity = new LinkedHashMap<>();
        for (StaffCommunity sc : staffs) {
            Map<String, Object> a = byCommunity.computeIfAbsent(sc.getCommunityId(), cid -> {
                Community c = communityMapper.selectById(cid);
                String cname = c == null ? ("#" + cid) : c.getName();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("type", "STAFF");
                row.put("communityId", cid);
                row.put("communityName", cname);
                row.put("roles", new ArrayList<String>());
                row.put("status", "INACTIVE");
                row.put("refId", sc.getId());
                return row;
            });
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) a.get("roles");
            String role = StaffRoles.normalize(sc.getStaffRole());
            if (role != null && !roles.contains(role)) {
                roles.add(role);
            }
            if ("ACTIVE".equals(sc.getStatus())) {
                a.put("status", "ACTIVE");
            }
        }
        for (Map<String, Object> a : byCommunity.values()) {
            @SuppressWarnings("unchecked")
            List<String> roles = StaffRoles.sortRoles((List<String>) a.get("roles"));
            a.put("roles", roles);
            a.put("staffRoles", roles);
            a.put("staffRole", StaffRoles.primary(roles));
            a.put("roleLabels", StaffRoles.labelsJoined(roles));
            affiliations.add(a);
            if ("ACTIVE".equals(a.get("status"))) {
                summaryParts.add(a.get("communityName") + "(" + a.get("roleLabels") + ")");
            }
        }

        m.put("affiliations", affiliations);
        m.put("communitySummary", summaryParts.isEmpty() ? "无物业任职" : String.join("；", summaryParts));
        return m;
    }

    public void updateStaff(Long communityId, Long userId, String staffRole, String status) {
        requirePlatform();
        List<StaffCommunity> actives = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, communityId)
                .eq(StaffCommunity::getUserId, userId)
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        if (actives.isEmpty()) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "任职不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (StringUtils.hasText(status) && !"ACTIVE".equals(status)) {
            for (StaffCommunity sc : actives) {
                assertCanDeactivateStaffRole(sc);
            }
            for (StaffCommunity sc : actives) {
                sc.setStatus(status);
                sc.setUpdatedAt(now);
                staffCommunityMapper.updateById(sc);
            }
            return;
        }
        if (StringUtils.hasText(staffRole)) {
            String role = StaffRoles.normalize(staffRole);
            if (!StaffRoles.isManager(role)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "平台仅可任命物业经理");
            }
            ensureStaffRole(communityId, userId, StaffRoles.PROPERTY_MANAGER);
        }
    }

    /**
     * 账号移交策略：不可清掉小区唯一物业经理（应改姓名/密码/手机号交接，而非删岗）。
     */
    private void assertCanDeactivateStaffRole(StaffCommunity sc) {
        if (sc == null || !StaffRoles.isManager(sc.getStaffRole())) {
            return;
        }
        if (!"ACTIVE".equals(sc.getStatus())) {
            return;
        }
        long others = staffCommunityMapper.selectCount(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, sc.getCommunityId())
                .eq(StaffCommunity::getStaffRole, StaffRoles.PROPERTY_MANAGER)
                .eq(StaffCommunity::getStatus, "ACTIVE")
                .ne(StaffCommunity::getId, sc.getId()));
        if (others == 0) {
            throw BizException.of(ErrorCodes.LAST_MANAGER,
                    "不可移除小区唯一物业经理。请通过账号移交（改姓名/重置密码/改手机号）交接给新人，勿删除或停用该经理任职");
        }
    }

    public Map<String, Object> changePlatformPassword(String oldPassword, String newPassword) {
        requirePlatform();
        SysUser user = sysUserMapper.selectById(AuthContext.require().getUserId());
        if (user == null || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "原密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        return new HashMap<>();
    }
}
