package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.Community;
import com.property.mgmt.domain.CommunityApplication;
import com.property.mgmt.domain.StaffCommunity;
import com.property.mgmt.domain.SysUser;
import com.property.mgmt.mapper.CommunityApplicationMapper;
import com.property.mgmt.mapper.CommunityMapper;
import com.property.mgmt.mapper.StaffCommunityMapper;
import com.property.mgmt.mapper.SysUserMapper;
import com.property.mgmt.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommunityApplicationService {

    private final CommunityApplicationMapper applicationMapper;
    private final CommunityMapper communityMapper;
    private final SysUserMapper sysUserMapper;
    private final StaffCommunityMapper staffCommunityMapper;
    private final StaffSpaceService staffSpaceService;
    private final PlatformService platformService;

    public List<Map<String, Object>> templates() {
        AuthContext.require();
        return CommunitySpaceTemplates.list();
    }

    public List<Map<String, Object>> regions() {
        AuthContext.require();
        return RegionCatalog.tree();
    }

    @Transactional
    public Map<String, Object> submit(Map<String, Object> body) {
        Long uid = AuthContext.require().getUserId();
        String name = str(body.get("applicantName"));
        String role = str(body.get("applicantRole"));
        String mobile = str(body.get("applicantMobile"));
        String reason = str(body.get("applyReason"));
        String communityName = str(body.get("communityName"));
        String provinceCode = str(body.get("provinceCode"));
        String provinceName = str(body.get("provinceName"));
        String cityCode = str(body.get("cityCode"));
        String cityName = str(body.get("cityName"));
        String districtCode = str(body.get("districtCode"));
        String districtName = str(body.get("districtName"));
        String templateCode = str(body.get("templateCode"));

        if (!StringUtils.hasText(name) || !StringUtils.hasText(mobile) || !StringUtils.hasText(communityName)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "申请人、电话、小区名称必填");
        }
        if (!"RESIDENT".equals(role) && !"STAFF".equals(role)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "申请人角色须为住户或物业");
        }
        if (mobile.length() < 8 || mobile.length() > 20) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "申请电话格式不正确");
        }
        if (reason != null && reason.length() > 20) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "申请原因最多 20 字");
        }
        RegionCatalog.validate(provinceCode, cityCode, districtCode, provinceName, cityName, districtName);
        templateCode = resolveTemplateCode(templateCode, body);
        // 物业角色申请同名小区：直接拒绝（住户申请不拦，避免与既有小区撞名影响认证路径外的诉求）
        rejectStaffDuplicateCommunityName(role, communityName.trim());

        long pending = applicationMapper.selectCount(new LambdaQueryWrapper<CommunityApplication>()
                .eq(CommunityApplication::getApplicantUserId, uid)
                .eq(CommunityApplication::getStatus, "PENDING"));
        if (pending >= 5) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "待审申请过多，请等待平台处理");
        }

        CommunityApplication app = new CommunityApplication();
        app.setApplicantUserId(uid);
        app.setApplicantName(name.trim());
        app.setApplicantRole(role);
        app.setApplicantMobile(mobile.trim());
        app.setApplyReason(StringUtils.hasText(reason) ? reason.trim() : null);
        app.setProvinceCode(provinceCode);
        app.setProvinceName(provinceName);
        app.setCityCode(cityCode);
        app.setCityName(cityName);
        app.setDistrictCode(districtCode);
        app.setDistrictName(districtName);
        app.setCommunityName(communityName.trim());
        app.setTemplateCode(templateCode);
        app.setStatus("PENDING");
        app.setCreatedAt(LocalDateTime.now());
        app.setUpdatedAt(LocalDateTime.now());
        applicationMapper.insert(app);
        return Map.of("id", app.getId());
    }

    public Map<String, Object> mine() {
        Long uid = AuthContext.require().getUserId();
        List<CommunityApplication> list = applicationMapper.selectList(new LambdaQueryWrapper<CommunityApplication>()
                .eq(CommunityApplication::getApplicantUserId, uid)
                .orderByDesc(CommunityApplication::getId));
        return Map.of("list", list);
    }

    public Map<String, Object> platformList(String status, int page, int pageSize) {
        platformService.requirePlatform();
        LambdaQueryWrapper<CommunityApplication> q = new LambdaQueryWrapper<CommunityApplication>()
                .eq(StringUtils.hasText(status), CommunityApplication::getStatus, status)
                .orderByDesc(CommunityApplication::getId);
        Page<CommunityApplication> p = applicationMapper.selectPage(new Page<>(page, pageSize), q);
        return Map.of("list", p.getRecords(), "total", p.getTotal(), "page", page, "pageSize", pageSize);
    }

    @Transactional
    public Map<String, Object> approve(Long id) {
        platformService.requirePlatform();
        CommunityApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "申请不存在");
        }
        if (!"PENDING".equals(app.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "申请已处理");
        }
        // 审核时再拦一次：物业角色 + 已有同名小区
        rejectStaffDuplicateCommunityName(app.getApplicantRole(), app.getCommunityName());

        String address = RegionCatalog.composeAddress(
                app.getProvinceName(), app.getCityName(), app.getDistrictName(), app.getCommunityName());
        Community c = new Community();
        c.setName(app.getCommunityName());
        c.setAddress(address);
        c.setContactPhone(app.getApplicantMobile());
        c.setHasFormalCommittee(0);
        c.setStatus(1);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        communityMapper.insert(c);

        if (StringUtils.hasText(app.getTemplateCode())) {
            staffSpaceService.generateFromTemplate(c.getId(), app.getTemplateCode());
        }

        SysUser user = sysUserMapper.selectById(app.getApplicantUserId());
        if (user == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "申请人用户不存在");
        }
        if (StringUtils.hasText(app.getApplicantName())) {
            user.setRealName(app.getApplicantName());
        }
        if (!StringUtils.hasText(user.getMobile())) {
            SysUser mobileTaken = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getMobile, app.getApplicantMobile()));
            if (mobileTaken != null && !mobileTaken.getId().equals(user.getId())) {
                throw BizException.of(ErrorCodes.MOBILE_TAKEN, "申请电话已被其他账号占用");
            }
            user.setMobile(app.getApplicantMobile());
        }
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);

        if ("STAFF".equals(app.getApplicantRole())) {
            StaffCommunity sc = new StaffCommunity();
            sc.setCommunityId(c.getId());
            sc.setUserId(user.getId());
            sc.setStaffRole("PROPERTY_MANAGER");
            sc.setStatus("ACTIVE");
            sc.setCreatedAt(LocalDateTime.now());
            sc.setUpdatedAt(LocalDateTime.now());
            staffCommunityMapper.insert(sc);
        }

        app.setStatus("APPROVED");
        app.setCommunityId(c.getId());
        app.setReviewedBy(AuthContext.require().getUserId());
        app.setReviewedAt(LocalDateTime.now());
        app.setUpdatedAt(LocalDateTime.now());
        applicationMapper.updateById(app);

        Map<String, Object> out = new HashMap<>();
        out.put("communityId", c.getId());
        out.put("applicationId", app.getId());
        return out;
    }

    @Transactional
    public void reject(Long id, String rejectReason) {
        platformService.requirePlatform();
        CommunityApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "申请不存在");
        }
        if (!"PENDING".equals(app.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "申请已处理");
        }
        if (!StringUtils.hasText(rejectReason)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请填写拒绝原因");
        }
        app.setStatus("REJECTED");
        app.setRejectReason(rejectReason.trim());
        app.setReviewedBy(AuthContext.require().getUserId());
        app.setReviewedAt(LocalDateTime.now());
        app.setUpdatedAt(LocalDateTime.now());
        applicationMapper.updateById(app);
    }

    /** 物业角色申请/审核：已存在同名未删除小区则拒绝 */
    private void rejectStaffDuplicateCommunityName(String role, String communityName) {
        if (!"STAFF".equals(role) || !StringUtils.hasText(communityName)) {
            return;
        }
        Long n = communityMapper.selectCount(new LambdaQueryWrapper<Community>()
                .eq(Community::getName, communityName.trim())
                .isNull(Community::getDeletedAt));
        if (n != null && n > 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "已存在同名小区，物业角色不可重复申请创建");
        }
    }

    /**
     * 优先自定义数量 buildings / unitsPerBuilding / floorsPerUnit / roomsPerFloor；
     * 否则校验预设或已编码的 templateCode（含 C_b_u_f_r）。
     */
    private static String resolveTemplateCode(String templateCode, Map<String, Object> body) {
        Integer buildings = asPositiveInt(body.get("buildings"));
        Integer units = asPositiveInt(body.get("unitsPerBuilding"));
        Integer floors = asPositiveInt(body.get("floorsPerUnit"));
        Integer rooms = asPositiveInt(body.get("roomsPerFloor"));
        boolean anyCount = buildings != null || units != null || floors != null || rooms != null;
        if (anyCount) {
            if (buildings == null || units == null || floors == null || rooms == null) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "自定义初始化须同时填写楼栋、单元、楼层、每层房间数");
            }
            return CommunitySpaceTemplates.ofCounts(buildings, units, floors, rooms).code();
        }
        if (StringUtils.hasText(templateCode)) {
            return CommunitySpaceTemplates.require(templateCode).code();
        }
        return null;
    }

    private static Integer asPositiveInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            int v = n.intValue();
            return v > 0 ? v : null;
        }
        String s = String.valueOf(o).trim();
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "房屋数量须为正整数");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
