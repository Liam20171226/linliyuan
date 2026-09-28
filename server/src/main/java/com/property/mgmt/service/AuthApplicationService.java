package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.common.RoomPaths;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthApplicationService {

    private static final Set<String> ROLES = Set.of(
            "OWNER", "OWNER_MEMBER", "TENANT", "TENANT_MEMBER");

    private final AuthApplicationMapper authApplicationMapper;
    private final RoomMapper roomMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final FloorMapper floorMapper;
    private final SysUserMapper sysUserMapper;
    private final StaffCommunityMapper staffCommunityMapper;
    private final RoomOccupantMapper roomOccupantMapper;
    private final AttachmentService attachmentService;
    private final TodoNotifyService todoNotifyService;
    private final RoomOccupantBindService roomOccupantBindService;

    @Transactional
    public AuthApplication submit(Long communityId, Long roomId, String applicantName,
                                  String applicantIdCardNo, String applyRole, String applyMessage,
                                  List<Long> attachmentIds) {
        AuthUser auth = AuthContext.require();
        String identity = auth.getIdentityType();
        if ("STAFF".equals(identity) || "COMMITTEE".equals(identity) || "PLATFORM".equals(identity)) {
            throw BizException.of(ErrorCodes.AUTH_IDENTITY_FORBIDDEN, "业委会/物业身份不可提交住户认证，请切游客或住户");
        }
        if (!StringUtils.hasText(applicantName) || !StringUtils.hasText(applyRole)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "姓名/申请角色必填");
        }
        if (!ROLES.contains(applyRole)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "applyRole 非法");
        }
        SysUser me = sysUserMapper.selectById(auth.getUserId());
        if (me == null || !StringUtils.hasText(me.getMobile())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "账号未绑定手机号，请重新微信授权登录");
        }
        String applicantMobile = me.getMobile().trim();

        Room room = roomMapper.selectById(roomId);
        if (room == null || room.getDeletedAt() != null || !communityId.equals(room.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在");
        }

        RoomOccupant existing = roomOccupantBindService.findActive(roomId, auth.getUserId());
        if (existing != null && applyRole.equals(existing.getResidentRole())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "您已是本房该角色，无需重复申请");
        }
        if (existing != null && "OWNER".equals(existing.getResidentRole()) && !"OWNER".equals(applyRole)) {
            throw BizException.of(ErrorCodes.BAD_PARAM,
                    "您已是本房业主，不可再申请降为其他住户角色。如需变更请联系物业处理");
        }
        long pending = authApplicationMapper.selectCount(new LambdaQueryWrapper<AuthApplication>()
                .eq(AuthApplication::getRoomId, roomId)
                .eq(AuthApplication::getApplicantUserId, auth.getUserId())
                .eq(AuthApplication::getStatus, "PENDING"));
        if (pending > 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "您已有待审核的本房认证申请");
        }

        if ("OWNER".equals(applyRole)) {
            if (!StringUtils.hasText(applicantIdCardNo)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "业主申请须填写身份证");
            }
            if (attachmentIds == null || attachmentIds.isEmpty()) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "业主申请须上传产权证明附件");
            }
            // 提交时即拦截「房已有其他业主」
            long otherOwners = roomOccupantMapper.selectCount(new LambdaQueryWrapper<RoomOccupant>()
                    .eq(RoomOccupant::getRoomId, roomId)
                    .eq(RoomOccupant::getResidentRole, "OWNER")
                    .eq(RoomOccupant::getStatus, "ACTIVE")
                    .ne(RoomOccupant::getUserId, auth.getUserId()));
            if (otherOwners > 0) {
                throw BizException.of(ErrorCodes.OWNER_EXISTS, "该房已有业主，不可再申请为业主");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        AuthApplication app = new AuthApplication();
        app.setCommunityId(communityId);
        app.setRoomId(roomId);
        app.setApplicantUserId(auth.getUserId());
        app.setApplicantName(applicantName.trim());
        app.setApplicantMobile(applicantMobile);
        app.setApplicantIdCardNo(StringUtils.hasText(applicantIdCardNo) ? applicantIdCardNo.trim() : null);
        app.setApplyRole(applyRole);
        app.setApplyMessage(applyMessage);
        app.setStatus("PENDING");
        app.setSource("USER_APPLY");
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        authApplicationMapper.insert(app);

        attachmentService.bindToBiz(attachmentIds, "AUTH_APPLY", app.getId(), communityId, auth.getUserId());

        List<StaffCommunity> staffs = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, communityId)
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        for (StaffCommunity sc : staffs) {
            todoNotifyService.createTodo(communityId, sc.getUserId(), "PENDING_REVIEW",
                    "待审核住户认证",
                    RoomPaths.format(
                            buildingMapper.selectById(room.getBuildingId()),
                            unitMapper.selectById(room.getUnitId()),
                            floorMapper.selectById(room.getFloorId()),
                            room) + " · " + applicantName + " 申请 " + applyRole,
                    "AUTH_APPLY", app.getId());
            todoNotifyService.skipSubscribe(communityId, sc.getUserId(), "PENDING_REVIEW_STAFF",
                    "AUTH_APPLY", app.getId(), "一阶段未接微信订阅下发");
        }
        return app;
    }

    public List<AuthApplication> mine() {
        Long uid = AuthContext.require().getUserId();
        return authApplicationMapper.selectList(new LambdaQueryWrapper<AuthApplication>()
                .eq(AuthApplication::getApplicantUserId, uid)
                .orderByDesc(AuthApplication::getId));
    }

    public List<Map<String, Object>> mineViews() {
        return mine().stream().map(this::toView).collect(Collectors.toList());
    }

    public AuthApplication getMine(Long id) {
        AuthApplication app = require(id);
        if (!AuthContext.require().getUserId().equals(app.getApplicantUserId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "无权查看");
        }
        return app;
    }

    public Map<String, Object> getMineView(Long id) {
        return toView(getMine(id));
    }

    public List<AuthApplication> staffList(String status) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<AuthApplication> q = new LambdaQueryWrapper<AuthApplication>()
                .eq(AuthApplication::getCommunityId, cid)
                .orderByDesc(AuthApplication::getId);
        if (StringUtils.hasText(status)) {
            q.eq(AuthApplication::getStatus, status);
        }
        return authApplicationMapper.selectList(q);
    }

    public List<Map<String, Object>> staffListViews(String status) {
        return staffList(status).stream().map(this::toView).collect(Collectors.toList());
    }

    public AuthApplication staffGet(Long id) {
        AuthApplication app = require(id);
        if (!StaffGuard.communityId().equals(app.getCommunityId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "非本小区申请");
        }
        return app;
    }

    public Map<String, Object> staffGetView(Long id) {
        return toView(staffGet(id));
    }

    private Map<String, Object> toView(AuthApplication app) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", app.getId());
        m.put("communityId", app.getCommunityId());
        m.put("roomId", app.getRoomId());
        m.put("applicantUserId", app.getApplicantUserId());
        m.put("applicantName", app.getApplicantName());
        m.put("applicantMobile", app.getApplicantMobile());
        m.put("applicantIdCardNo", app.getApplicantIdCardNo());
        m.put("applyRole", app.getApplyRole());
        m.put("applyMessage", app.getApplyMessage());
        m.put("status", app.getStatus());
        m.put("source", app.getSource());
        m.put("rejectReason", app.getRejectReason());
        m.put("reviewedBy", app.getReviewedBy());
        m.put("reviewedAt", app.getReviewedAt());
        m.put("resultOccupantId", app.getResultOccupantId());
        m.put("createdAt", app.getCreatedAt());
        m.put("updatedAt", app.getUpdatedAt());
        Room room = roomMapper.selectById(app.getRoomId());
        m.put("roomNo", room == null ? "" : room.getRoomNo());
        m.put("roomPath", room == null ? "" : RoomPaths.format(
                room.getBuildingId() == null ? null : buildingMapper.selectById(room.getBuildingId()),
                room.getUnitId() == null ? null : unitMapper.selectById(room.getUnitId()),
                room.getFloorId() == null ? null : floorMapper.selectById(room.getFloorId()),
                room));
        m.put("attachments", attachmentBriefs(app));
        return m;
    }

    private List<Map<String, Object>> attachmentBriefs(AuthApplication app) {
        List<Attachment> list = attachmentService.listByBiz("AUTH_APPLY", app.getId());
        if (list.isEmpty() && "APPROVED".equals(app.getStatus())) {
            list = attachmentService.listByBiz("ROOM_ARCHIVE", app.getRoomId()).stream()
                    .filter(a -> app.getApplicantUserId().equals(a.getUploadedBy()))
                    .collect(Collectors.toList());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Attachment a : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("fileName", a.getFileName());
            row.put("contentType", a.getContentType());
            row.put("sizeBytes", a.getSizeBytes());
            row.put("url", a.getUrl());
            row.put("image", isImage(a));
            out.add(row);
        }
        return out;
    }

    private static boolean isImage(Attachment a) {
        String ct = a.getContentType() == null ? "" : a.getContentType().toLowerCase();
        if (ct.startsWith("image/")) return true;
        String name = a.getFileName() == null ? "" : a.getFileName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
    }

    @Transactional
    public AuthApplication approve(Long id) {
        AuthUser staff = StaffGuard.requireStaff();
        AuthApplication app = staffGet(id);
        if (!"PENDING".equals(app.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "申请已处理");
        }

        RoomOccupant existingOcc = roomOccupantBindService.findActive(app.getRoomId(), app.getApplicantUserId());
        if (existingOcc != null && "OWNER".equals(existingOcc.getResidentRole())
                && !"OWNER".equals(app.getApplyRole())) {
            throw BizException.of(ErrorCodes.BAD_PARAM,
                    "申请人已是本房业主，不可审核通过为其他住户角色。请驳回该申请");
        }

        if ("OWNER".equals(app.getApplyRole())) {
            SysUser idClash = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getIdCardNo, app.getApplicantIdCardNo())
                    .ne(SysUser::getId, app.getApplicantUserId()));
            if (idClash != null) {
                throw BizException.of(ErrorCodes.ID_CARD_TAKEN, "身份证已被其他账号占用");
            }
        }

        SysUser otherMobile = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getMobile, app.getApplicantMobile())
                .ne(SysUser::getId, app.getApplicantUserId()));
        if (otherMobile != null) {
            throw BizException.of(ErrorCodes.MOBILE_TAKEN, "请联系物业管理人员");
        }

        LocalDateTime now = LocalDateTime.now();
        SysUser user = sysUserMapper.selectById(app.getApplicantUserId());
        if (user == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "申请人不存在");
        }
        if (!StringUtils.hasText(user.getMobile())) {
            user.setMobile(app.getApplicantMobile());
        }
        if (!StringUtils.hasText(user.getRealName())) {
            user.setRealName(app.getApplicantName());
        } else if (StringUtils.hasText(app.getApplicantName())) {
            user.setRealName(app.getApplicantName());
        }
        if ("OWNER".equals(app.getApplyRole()) && StringUtils.hasText(app.getApplicantIdCardNo())) {
            user.setIdCardNo(app.getApplicantIdCardNo());
        }
        user.setUpdatedAt(now);
        sysUserMapper.updateById(user);

        Room room = roomMapper.selectById(app.getRoomId());
        if (room == null || room.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "房屋不存在");
        }
        RoomOccupant occ = roomOccupantBindService.bindActive(
                room, app.getApplicantUserId(), app.getApplyRole(), "USER_APPLY", staff.getUserId());

        attachmentService.rebindRoomArchive(app.getId(), app.getRoomId(), app.getCommunityId());

        app.setStatus("APPROVED");
        app.setReviewedBy(staff.getUserId());
        app.setReviewedAt(now);
        app.setResultOccupantId(occ.getId());
        app.setUpdatedAt(now);
        authApplicationMapper.updateById(app);

        todoNotifyService.doneByBiz("AUTH_APPLY", app.getId(), "PENDING_REVIEW");
        todoNotifyService.createTodo(app.getCommunityId(), app.getApplicantUserId(), "AUTH_RESULT",
                "认证已通过",
                "您申请的房屋认证已通过（" + app.getApplyRole() + "）",
                "AUTH_APPLY", app.getId());
        todoNotifyService.skipSubscribe(app.getCommunityId(), app.getApplicantUserId(), "AUTH_RESULT",
                "AUTH_APPLY", app.getId(), "一阶段未接微信订阅下发");
        return app;
    }

    @Transactional
    public AuthApplication reject(Long id, String rejectReason) {
        AuthUser staff = StaffGuard.requireStaff();
        AuthApplication app = staffGet(id);
        if (!"PENDING".equals(app.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "申请已处理");
        }
        if (!StringUtils.hasText(rejectReason)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "拒绝原因必填");
        }
        LocalDateTime now = LocalDateTime.now();
        app.setStatus("REJECTED");
        app.setRejectReason(rejectReason.trim());
        app.setReviewedBy(staff.getUserId());
        app.setReviewedAt(now);
        app.setUpdatedAt(now);
        authApplicationMapper.updateById(app);

        todoNotifyService.doneByBiz("AUTH_APPLY", app.getId(), "PENDING_REVIEW");
        todoNotifyService.createTodo(app.getCommunityId(), app.getApplicantUserId(), "AUTH_RESULT",
                "认证未通过",
                rejectReason.trim(),
                "AUTH_APPLY", app.getId());
        todoNotifyService.skipSubscribe(app.getCommunityId(), app.getApplicantUserId(), "AUTH_RESULT",
                "AUTH_APPLY", app.getId(), "一阶段未接微信订阅下发");
        return app;
    }

    private AuthApplication require(Long id) {
        AuthApplication app = authApplicationMapper.selectById(id);
        if (app == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "申请不存在");
        }
        return app;
    }
}
