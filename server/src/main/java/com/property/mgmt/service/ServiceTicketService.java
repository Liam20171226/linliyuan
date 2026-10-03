package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.common.RoomPaths;
import com.property.mgmt.common.StaffRoles;
import com.property.mgmt.domain.Attachment;
import com.property.mgmt.domain.Building;
import com.property.mgmt.domain.Community;
import com.property.mgmt.domain.Room;
import com.property.mgmt.domain.RoomOccupant;
import com.property.mgmt.domain.ServiceTicket;
import com.property.mgmt.domain.StaffCommunity;
import com.property.mgmt.domain.SysUser;
import com.property.mgmt.domain.Unit;
import com.property.mgmt.domain.Floor;
import com.property.mgmt.mapper.BuildingMapper;
import com.property.mgmt.mapper.CommunityMapper;
import com.property.mgmt.mapper.RoomMapper;
import com.property.mgmt.mapper.ServiceTicketMapper;
import com.property.mgmt.mapper.StaffCommunityMapper;
import com.property.mgmt.mapper.SysUserMapper;
import com.property.mgmt.mapper.UnitMapper;
import com.property.mgmt.mapper.FloorMapper;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.CommitteeGuard;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Objects;

/**
 * 报事报修统一服务：报修（公区报障/室内维修）与投诉/表扬/咨询建议共用一张 service_ticket 表。
 * 主工单为 node_type=TICKET 的行；处理留言为 REPLY 子行；派单/转派/接单为 DISPATCH 子行。
 * 由 kind 字段（REPAIR / COMPLAINT）区分业务，不同业务走各自状态机，但存储与查询统一。
 */
@Service
@RequiredArgsConstructor
public class ServiceTicketService {

    private static final String NODE_TICKET = "TICKET";
    private static final String NODE_REPLY = "REPLY";
    private static final String NODE_DISPATCH = "DISPATCH";
    private static final String KIND_REPAIR = "REPAIR";
    private static final String KIND_COMPLAINT = "COMPLAINT";
    private static final Set<String> REPAIR_CATEGORIES = Set.of("公区报障", "室内维修");
    private static final Set<String> COMPLAINT_CATEGORIES = Set.of("投诉", "表扬", "咨询建议");

    private final ServiceTicketMapper ticketMapper;
    private final RoomOccupantBindService roomOccupantBindService;
    private final StaffCommunityMapper staffCommunityMapper;
    private final SysUserMapper sysUserMapper;
    private final RoomMapper roomMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final CommunityMapper communityMapper;
    private final FloorMapper floorMapper;
    private final AttachmentService attachmentService;
    private final TodoNotifyService todoNotifyService;

    // ===================== 提交 =====================

    @Transactional
    public ServiceTicket createRepair(Long roomId, String category, String location, String description,
                                     List<Long> attachmentIds) {
        AuthUser u = AuthContext.require();
        if (!"RESIDENT".equals(u.getIdentityType()) || u.getCommunityId() == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换为住户身份");
        }
        if (roomId == null || category == null || description == null || description.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "roomId/category/description 必填");
        }
        String cat = category.trim();
        if (!REPAIR_CATEGORIES.contains(cat)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "非报修分类");
        }
        String loc = location == null ? null : location.trim();
        // 公区报障不再单独填位置：请住户在问题描述中写明具体位置
        RoomOccupant occ = roomOccupantBindService.findActive(roomId, u.getUserId());
        if (occ == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "非本房住户");
        }
        LocalDateTime now = LocalDateTime.now();
        ServiceTicket t = new ServiceTicket();
        t.setParentId(null);
        t.setNodeType(NODE_TICKET);
        t.setKind(KIND_REPAIR);
        t.setCommunityId(u.getCommunityId());
        t.setRoomId(roomId);
        t.setApplicantUserId(u.getUserId());
        t.setCategory(cat);
        t.setLocation(loc == null || loc.isBlank() ? null : loc);
        t.setContent(description.trim());
        t.setStatus("PENDING_ASSIGN");
        t.setEscalatedToManager(0);
        t.setAutoCompleted(0);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        ticketMapper.insert(t);
        attachmentService.bindToBiz(attachmentIds, "REPAIR", t.getId(), u.getCommunityId(), u.getUserId());

        notifyAllStaff(u.getCommunityId(), t.getId(), "REPAIR_NEW", "新报修待派单", t.getContent(), KIND_REPAIR);
        return t;
    }

    @Transactional
    public ServiceTicket createComplaint(Long roomId, String contactName, String contactMobile,
                                        String category, String targetName, String content,
                                        List<Long> attachmentIds) {
        AuthUser u = AuthContext.require();
        if (!"RESIDENT".equals(u.getIdentityType()) || u.getCommunityId() == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换为住户身份");
        }
        if (category == null || content == null || content.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "分类/内容必填");
        }
        String cat = category.trim();
        if (!COMPLAINT_CATEGORIES.contains(cat)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "非投诉/建议分类");
        }
        SysUser profile = sysUserMapper.selectById(u.getUserId());
        String name = contactName == null ? "" : contactName.trim();
        if (name.isBlank()) {
            name = profile != null && profile.getRealName() != null && !profile.getRealName().isBlank()
                    ? profile.getRealName().trim() : "住户";
        }
        String mobile = contactMobile == null ? "" : contactMobile.trim();
        if (mobile.isBlank()) {
            mobile = profile != null && profile.getMobile() != null ? profile.getMobile().trim() : "";
        }
        if (mobile.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "账号未绑定手机号，无法提交");
        }
        if (roomId != null) {
            RoomOccupant occ = roomOccupantBindService.findActive(roomId, u.getUserId());
            if (occ == null) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "非本房住户");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        ServiceTicket t = new ServiceTicket();
        t.setParentId(null);
        t.setNodeType(NODE_TICKET);
        t.setKind(KIND_COMPLAINT);
        t.setCommunityId(u.getCommunityId());
        t.setRoomId(roomId);
        t.setApplicantUserId(u.getUserId());
        t.setContactName(name);
        t.setContactMobile(mobile);
        t.setCategory(cat);
        t.setContent(content.trim());
        t.setStatus("PENDING");
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        ticketMapper.insert(t);
        attachmentService.bindToBiz(attachmentIds, "COMPLAINT", t.getId(), u.getCommunityId(), u.getUserId());

        notifyAllStaff(u.getCommunityId(), t.getId(), "COMPLAINT_NEW", "新投诉待处理", t.getContent(), KIND_COMPLAINT);
        return t;
    }

    // ===================== 住户端 =====================

    public Map<String, Object> residentRepairs(int page, int pageSize) {
        return listByApplicantKind(KIND_REPAIR, page, pageSize);
    }

    public Map<String, Object> residentComplaints(int page, int pageSize) {
        return listByApplicantKind(KIND_COMPLAINT, page, pageSize);
    }

    private Map<String, Object> listByApplicantKind(String kind, int page, int pageSize) {
        AuthUser u = AuthContext.require();
        Page<ServiceTicket> p = ticketMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ServiceTicket>()
                        .eq(ServiceTicket::getNodeType, NODE_TICKET)
                        .eq(ServiceTicket::getKind, kind)
                        .eq(ServiceTicket::getApplicantUserId, u.getUserId())
                        .orderByDesc(ServiceTicket::getId));
        if (KIND_COMPLAINT.equals(kind)) {
            enrichComplaintReply(p.getRecords());
        }
        return pageOf(p, page, pageSize);
    }

    /**
     * 为投诉列表批量填充最新物业回复（REPLY 子行），避免列表接口逐个回查。
     * 回复内容存于独立的 REPLY 子行，主单行不冗余存储，故列表展示时按需聚合。
     */
    private void enrichComplaintReply(List<ServiceTicket> tickets) {
        if (tickets == null || tickets.isEmpty()) return;
        List<Long> ids = tickets.stream().map(ServiceTicket::getId).collect(Collectors.toList());
        List<ServiceTicket> replies = ticketMapper.selectList(new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_REPLY)
                .in(ServiceTicket::getParentId, ids)
                .orderByAsc(ServiceTicket::getId));
        Map<Long, String> lastReply = new LinkedHashMap<>();
        for (ServiceTicket r : replies) {
            lastReply.put(r.getParentId(), r.getContent()); // 同主单多条回复取最新（id 最大）一条
        }
        for (ServiceTicket t : tickets) {
            t.setReplyContent(lastReply.get(t.getId()));
        }
    }

    public Map<String, Object> residentRepairGet(Long id) {
        ServiceTicket t = requireMine(id, KIND_REPAIR);
        return detail(t, true);
    }

    public Map<String, Object> residentComplaintGet(Long id) {
        ServiceTicket t = requireMine(id, KIND_COMPLAINT);
        return detail(t, true);
    }

    // ===================== 派单 / 接单 / 留言 / 完工 / 评价（报修） =====================

    @Transactional
    public ServiceTicket assign(Long id, Long assigneeUserId, String assigneeRole, String remark) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = u.getCommunityId();
        // 公区报障 / 室内维修 / 投诉 / 咨询建议 / 表扬 均可派给任一岗位
        ServiceTicket o = requireCommunity(id, cid, null);
        autoCompleteIfNeeded(o);
        if (!canAssignStatus(o.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "当前状态不可派单");
        }
        String role = StaffRoles.normalize(assigneeRole);
        boolean byRole = assigneeUserId == null;
        if (byRole && (role == null || !StaffRoles.canTakeTicket(role))) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请选择接收岗位");
        }
        if (!byRole && assigneeUserId.equals(u.getUserId())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "不能派给自己，可直接留言处理");
        }
        LocalDateTime now = LocalDateTime.now();
        Long prevAssignee = o.getAssigneeUserId();
        String prevRole = o.getAssigneeRole();
        String action = (prevAssignee == null && prevRole == null) ? "ASSIGN" : "TRANSFER";
        if (byRole) {
            o.setAssigneeRole(role);
            o.setAssigneeUserId(null);
        } else {
            o.setAssigneeRole(null);
            o.setAssigneeUserId(assigneeUserId);
        }
        o.setAssignedAt(now);
        o.setStatus("ASSIGNED");
        o.setUpdatedAt(now);
        // 显式用 wrapper 写库：updateById 默认字段策略会跳过 null 字段，
        // 导致「接单后又转派到岗位」时旧的 assigneeUserId 残留，前指派人仍能看到工单。
        ticketMapper.update(null, new LambdaUpdateWrapper<ServiceTicket>()
                .eq(ServiceTicket::getId, o.getId())
                .set(ServiceTicket::getAssigneeRole, byRole ? role : null)
                .set(ServiceTicket::getAssigneeUserId, byRole ? null : assigneeUserId)
                .set(ServiceTicket::getAssignedAt, now)
                .set(ServiceTicket::getStatus, "ASSIGNED")
                .set(ServiceTicket::getUpdatedAt, now));

        insertDispatch(o.getId(), cid, u.getUserId(), byRole ? null : assigneeUserId, byRole ? role : null, action, remark, now);

        if (remark != null && !remark.isBlank()) {
            insertReply(o.getId(), cid, u.getUserId(),
                    action.equals("TRANSFER") ? "转派说明：" + remark.trim() : "派单说明：" + remark.trim(), now);
        }

        String kind = o.getKind();
        String assignedType = KIND_REPAIR.equals(kind) ? "REPAIR_ASSIGNED" : "COMPLAINT_ASSIGNED";
        // 同一详情只保留最新派单待办：先清旧再发新
        todoNotifyService.doneAllByBiz(kind, o.getId());
        String titlePrefix = KIND_REPAIR.equals(kind) ? "报修" : o.getCategory();
        if (byRole) {
            List<StaffCommunity> targets = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                    .eq(StaffCommunity::getCommunityId, cid)
                    .eq(StaffCommunity::getStaffRole, role)
                    .eq(StaffCommunity::getStatus, "ACTIVE"));
            for (StaffCommunity s : targets) {
                todoNotifyService.createTodo(cid, s.getUserId(), assignedType,
                        titlePrefix + "已派单[" + StaffRoles.label(role) + "岗]", o.getContent(), kind, o.getId());
            }
        } else {
            todoNotifyService.createTodo(cid, assigneeUserId, assignedType,
                    titlePrefix + "已派单", o.getContent(), kind, o.getId());
        }
        return o;
    }

    private boolean canAssignStatus(String status) {
        return "PENDING_ASSIGN".equals(status)
                || "PENDING".equals(status)
                || "PROCESSING".equals(status)
                || "REPLIED".equals(status)
                || "ASSIGNED".equals(status)
                || "IN_PROGRESS".equals(status);
    }

    @Transactional
    public ServiceTicket claim(Long id) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = u.getCommunityId();
        if (cid == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换到物业身份");
        }
        ServiceTicket o = requireCommunity(id, cid, null);
        autoCompleteIfNeeded(o);
        if (!"ASSIGNED".equals(o.getStatus()) || o.getAssigneeUserId() != null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "工单已被接单或当前不可接单");
        }
        String role = o.getAssigneeRole();
        Set<String> myRoles = myRoleSet(u.getUserId(), cid);
        if (role != null && !myRoles.contains(role)) {
            throw BizException.of(ErrorCodes.FORBIDDEN,
                    "工单派给「" + StaffRoles.label(role) + "」岗，您没有该岗位身份");
        }
        LocalDateTime now = LocalDateTime.now();
        o.setAssigneeUserId(u.getUserId());
        o.setUpdatedAt(now);
        ticketMapper.updateById(o);

        insertDispatch(o.getId(), cid, null, u.getUserId(), role, "CLAIM", null, now);

        String assignedType = KIND_REPAIR.equals(o.getKind()) ? "REPAIR_ASSIGNED" : "COMPLAINT_ASSIGNED";
        todoNotifyService.doneByBiz(o.getKind(), o.getId(), assignedType);
        return o;
    }

    @Transactional
    public ServiceTicket reply(Long id, String content, List<Long> attachmentIds) {
        AuthUser u = StaffGuard.requireStaff();
        ServiceTicket o = requireCommunity(id, u.getCommunityId(), KIND_REPAIR);
        autoCompleteIfNeeded(o);
        if ("COMPLETED".equals(o.getStatus()) || "CLOSED".equals(o.getStatus())
                || "DONE_WAIT_RATE".equals(o.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "工单已完成/已关闭，不能再回复");
        }
        if (content == null || content.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "content 必填");
        }
        assertCanReplyAssigned(o, u);
        LocalDateTime now = LocalDateTime.now();
        ServiceTicket reply = insertReply(o.getId(), o.getCommunityId(), u.getUserId(), content.trim(), now);
        attachmentService.bindToBiz(attachmentIds, "REPAIR_REPLY", reply.getId(), o.getCommunityId(), u.getUserId());

        if (o.getFirstReplyAt() == null) {
            o.setFirstReplyAt(now);
        }
        if ("ASSIGNED".equals(o.getStatus()) || "PENDING_ASSIGN".equals(o.getStatus())) {
            o.setStatus("IN_PROGRESS");
        }
        // 经理/客服直接办理：未指派时以回复人作为处理人；岗位人员回复后记为自己办理完成
        List<String> myRoles = StaffGuard.activeRoles(u.getUserId(), u.getCommunityId());
        boolean office = myRoles.contains(StaffRoles.PROPERTY_MANAGER) || myRoles.contains(StaffRoles.CUSTOMER_SERVICE);
        boolean platform = u.isPlatformAdmin() || "PLATFORM".equals(u.getIdentityType());
        if ((office || platform) && o.getAssigneeUserId() == null) {
            o.setAssigneeUserId(u.getUserId());
            o.setAssigneeRole(null);
        } else if (o.getAssigneeUserId() == null && o.getAssigneeRole() != null) {
            o.setAssigneeUserId(u.getUserId());
        }
        o.setUpdatedAt(now);
        ticketMapper.updateById(o);

        // 回复完成 = 被派单人办理完成：清除派单待办；过程中不给住户推待办
        todoNotifyService.doneByBiz(KIND_REPAIR, o.getId(), "REPAIR_NEW");
        todoNotifyService.doneByBiz(KIND_REPAIR, o.getId(), "REPAIR_ASSIGNED");
        return o;
    }

    /** 派到岗位后：该岗位任一在职人员可回复；派到人则仅本人或经理/客服/平台可回复 */
    private void assertCanReplyAssigned(ServiceTicket o, AuthUser u) {
        Long cid = u.getCommunityId();
        List<String> myRoles = StaffGuard.activeRoles(u.getUserId(), cid);
        boolean office = myRoles.contains(StaffRoles.PROPERTY_MANAGER) || myRoles.contains(StaffRoles.CUSTOMER_SERVICE);
        boolean platform = u.isPlatformAdmin() || "PLATFORM".equals(u.getIdentityType());
        if (office || platform) {
            return;
        }
        if (o.getAssigneeUserId() != null) {
            if (!o.getAssigneeUserId().equals(u.getUserId())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "工单已指派给他人");
            }
            return;
        }
        if (o.getAssigneeRole() != null) {
            if (!myRoleSet(u.getUserId(), cid).contains(o.getAssigneeRole())) {
                throw BizException.of(ErrorCodes.FORBIDDEN,
                        "工单派给「" + StaffRoles.label(o.getAssigneeRole()) + "」岗，您没有该岗位身份");
            }
        }
    }

    @Transactional
    public ServiceTicket complete(Long id) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = u.getCommunityId();
        ServiceTicket o = requireCommunity(id, cid, KIND_REPAIR);
        autoCompleteIfNeeded(o);
        if (!"IN_PROGRESS".equals(o.getStatus()) && !"ASSIGNED".equals(o.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "当前状态不可完工");
        }
        // 本小区在职物业人员均可「处理完成」；完成后向住户推送评价待办（过程中不推）。
        LocalDateTime now = LocalDateTime.now();
        if (o.getAssigneeUserId() == null) {
            o.setAssigneeUserId(u.getUserId());
        }
        o.setStatus("DONE_WAIT_RATE");
        o.setCompletedAt(now);
        o.setUpdatedAt(now);
        ticketMapper.updateById(o);
        todoNotifyService.doneAllByBiz(KIND_REPAIR, o.getId());
        todoNotifyService.createTodo(cid, o.getApplicantUserId(), "REPAIR_RATE",
                "请评价报修", "完工待评价", KIND_REPAIR, o.getId());
        todoNotifyService.skipSubscribe(cid, o.getApplicantUserId(), "REPAIR_RATE",
                KIND_REPAIR, o.getId(), "一阶段未接微信下发");
        return o;
    }

    @Transactional
    public ServiceTicket rate(Long id, Integer rating, String comment,
                              Integer resolved, Integer responseScore,
                              Integer handlingScore, Integer satisfactionScore) {
        ServiceTicket o = requireMine(id, KIND_REPAIR);
        return doRate(o, KIND_REPAIR, "REPAIR_RATE", rating, comment,
                resolved, responseScore, handlingScore, satisfactionScore);
    }

    /**
     * 投诉 / 咨询建议 / 表扬：处理完成后由住户评价。
     * 「表扬」不参与满意度评价（不应进入待评价态）。
     */
    @Transactional
    public ServiceTicket rateComplaint(Long id, Integer rating, String comment,
                                       Integer resolved, Integer responseScore,
                                       Integer handlingScore, Integer satisfactionScore) {
        ServiceTicket o = requireMine(id, KIND_COMPLAINT);
        if ("表扬".equals(o.getCategory())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "表扬不参与满意度评价");
        }
        return doRate(o, KIND_COMPLAINT, "COMPLAINT_RATE", rating, comment,
                resolved, responseScore, handlingScore, satisfactionScore);
    }

    private ServiceTicket doRate(ServiceTicket o, String kind, String todoType,
                                 Integer rating, String comment, Integer resolved,
                                 Integer responseScore, Integer handlingScore, Integer satisfactionScore) {
        if (!"DONE_WAIT_RATE".equals(o.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅待评价可评分");
        }
        if (rating == null || rating < 1 || rating > 5) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "rating 须为 1～5");
        }
        LocalDateTime now = LocalDateTime.now();
        o.setRating(rating);
        o.setRatingComment(comment);
        // 是否解决（0/1，缺省按已解决）
        o.setRatingResolved(resolved == null ? 1 : (resolved == 0 ? 0 : 1));
        // 响应及时 / 处理及时 / 处理结果：1~5，缺省取总评分
        o.setRatingResponse(validScore(responseScore, rating));
        o.setRatingHandling(validScore(handlingScore, rating));
        o.setRatingSatisfaction(validScore(satisfactionScore, rating));
        o.setStatus("COMPLETED");
        o.setAutoCompleted(0);
        o.setCompletedAt(now);
        o.setUpdatedAt(now);
        ticketMapper.updateById(o);
        todoNotifyService.doneByBiz(kind, o.getId(), todoType);
        return o;
    }

    /** 评分维度合法性：空则回退总评分，非空须在 1~5 */
    private Integer validScore(Integer v, Integer fallback) {
        if (v == null) return fallback;
        if (v < 1 || v > 5) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "评分须为 1～5");
        }
        return v;
    }

    // ===================== 投诉处理（物业回复） =====================

    @Transactional
    public ServiceTicket handle(Long id, String replyContent, List<Long> attachmentIds) {
        AuthUser u = StaffGuard.requireStaff();
        ServiceTicket c = requireCommunity(id, u.getCommunityId(), KIND_COMPLAINT);
        if (replyContent == null || replyContent.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "replyContent 必填");
        }
        if ("CLOSED".equals(c.getStatus()) || "COMPLETED".equals(c.getStatus())
                || "DONE_WAIT_RATE".equals(c.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "已关闭/已完成不可处理");
        }
        assertCanReplyAssigned(c, u);
        LocalDateTime now = LocalDateTime.now();
        if ("PENDING".equals(c.getStatus()) || "ASSIGNED".equals(c.getStatus())) {
            c.setStatus("PROCESSING");
        }
        c.setHandlerUserId(u.getUserId());
        c.setRepliedAt(now);
        c.setStatus("REPLIED");
        if (c.getAssigneeUserId() == null && c.getAssigneeRole() != null) {
            c.setAssigneeUserId(u.getUserId());
        }
        c.setUpdatedAt(now);
        ticketMapper.updateById(c);

        ServiceTicket replyC = insertReply(c.getId(), c.getCommunityId(), u.getUserId(), replyContent.trim(), now);
        attachmentService.bindToBiz(attachmentIds, "COMPLAINT_REPLY", replyC.getId(), c.getCommunityId(), u.getUserId());

        // 回复完成 = 办理完成：清除新单/派单待办；住户待办统一留到「处理完成」时推送
        todoNotifyService.doneByBiz(KIND_COMPLAINT, c.getId(), "COMPLAINT_NEW");
        todoNotifyService.doneByBiz(KIND_COMPLAINT, c.getId(), "COMPLAINT_ASSIGNED");
        return c;
    }

    /**
     * 投诉 / 咨询建议 / 表扬 的「处理完成」：本小区物业人员均可点击。
     * 表扬不参与满意度评价，完成后直接 COMPLETED；其余进入待评价并首次向住户推送待办。
     */
    @Transactional
    public ServiceTicket completeComplaint(Long id) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = u.getCommunityId();
        ServiceTicket c = requireCommunity(id, cid, KIND_COMPLAINT);
        if ("CLOSED".equals(c.getStatus()) || "COMPLETED".equals(c.getStatus()) || "DONE_WAIT_RATE".equals(c.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "当前状态不可处理完成");
        }
        LocalDateTime now = LocalDateTime.now();
        if (c.getHandlerUserId() == null) {
            c.setHandlerUserId(u.getUserId());
        }
        boolean praise = "表扬".equals(c.getCategory());
        c.setStatus(praise ? "COMPLETED" : "DONE_WAIT_RATE");
        c.setCompletedAt(now);
        c.setUpdatedAt(now);
        ticketMapper.updateById(c);

        todoNotifyService.doneAllByBiz(KIND_COMPLAINT, c.getId());
        // 表扬不参与满意度评价，不推评价待办；其余类型仅在处理完成时推送
        if (!praise) {
            todoNotifyService.createTodo(cid, c.getApplicantUserId(), "COMPLAINT_RATE",
                    "请评价本次服务", "处理完成待评价", KIND_COMPLAINT, c.getId());
            todoNotifyService.skipSubscribe(cid, c.getApplicantUserId(), "COMPLAINT_RATE",
                    KIND_COMPLAINT, c.getId(), "一阶段未接微信下发");
        }
        return c;
    }

    // ============ 物业经理 / 客服：处理完成前修改·删除工位留言与图片 ============

    /** 处理完成前才允许改删留言；处理完成后留言即固化，居民可看到完整流程 */
    private void assertBeforeComplete(ServiceTicket o) {
        if (java.util.Set.of("DONE_WAIT_RATE", "COMPLETED", "CLOSED").contains(o.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "处理完成后不可再修改留言");
        }
    }

    private void assertOfficeOrPlatform(AuthUser u, Long cid) {
        List<String> myRoles = StaffGuard.activeRoles(u.getUserId(), cid);
        boolean office = myRoles.contains(StaffRoles.PROPERTY_MANAGER) || myRoles.contains(StaffRoles.CUSTOMER_SERVICE);
        boolean platform = u.isPlatformAdmin() || "PLATFORM".equals(u.getIdentityType());
        if (!office && !platform) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "仅物业经理或客服可修改留言");
        }
    }

    private ServiceTicket requireReply(Long ticketId, Long replyId) {
        ServiceTicket r = ticketMapper.selectOne(new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getId, replyId)
                .eq(ServiceTicket::getParentId, ticketId)
                .eq(ServiceTicket::getNodeType, NODE_REPLY));
        if (r == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "留言不存在");
        }
        return r;
    }

    /** 修改被派单岗位的留言内容 */
    @Transactional
    public ServiceTicket updateReply(Long ticketId, Long replyId, String content) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = u.getCommunityId();
        ServiceTicket o = requireCommunity(ticketId, cid, null);
        assertBeforeComplete(o);
        assertOfficeOrPlatform(u, cid);
        requireReply(ticketId, replyId);
        if (content == null || content.isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "content 必填");
        }
        LocalDateTime now = LocalDateTime.now();
        ticketMapper.update(null, new LambdaUpdateWrapper<ServiceTicket>()
                .eq(ServiceTicket::getId, replyId)
                .set(ServiceTicket::getContent, content.trim())
                .set(ServiceTicket::getUpdatedAt, now));
        o.setUpdatedAt(now);
        ticketMapper.updateById(o);
        return o;
    }

    /** 删除被派单岗位的留言，连同该留言下的所有图片 */
    @Transactional
    public ServiceTicket deleteReply(Long ticketId, Long replyId) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = u.getCommunityId();
        ServiceTicket o = requireCommunity(ticketId, cid, null);
        assertBeforeComplete(o);
        assertOfficeOrPlatform(u, cid);
        requireReply(ticketId, replyId);

        // 先删该留言下的图片（按 业务类型 + 留言id 绑定）
        String bizType = o.getKind() + "_REPLY";
        for (Attachment a : attachmentService.listByBiz(bizType, replyId)) {
            attachmentService.delete(a.getId());
        }
        ticketMapper.deleteById(replyId);

        // 若已无留言，清掉首复时间（置 null 必须显式 wrapper，updateById 会跳过 null 字段）
        long remain = ticketMapper.selectCount(new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_REPLY)
                .eq(ServiceTicket::getParentId, ticketId));
        if (remain == 0) {
            ticketMapper.update(null, new LambdaUpdateWrapper<ServiceTicket>()
                    .eq(ServiceTicket::getId, ticketId)
                    .set(ServiceTicket::getFirstReplyAt, null)
                    .set(ServiceTicket::getUpdatedAt, LocalDateTime.now()));
        }
        return o;
    }

    /** 删除某条留言下的单张图片 */
    @Transactional
    public ServiceTicket deleteReplyAttachment(Long ticketId, Long replyId, Long attachmentId) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = u.getCommunityId();
        ServiceTicket o = requireCommunity(ticketId, cid, null);
        assertBeforeComplete(o);
        assertOfficeOrPlatform(u, cid);
        requireReply(ticketId, replyId);
        String bizType = o.getKind() + "_REPLY";
        boolean owned = attachmentService.listByBiz(bizType, replyId).stream()
                .anyMatch(a -> a.getId().equals(attachmentId));
        if (!owned) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "图片不属于该留言");
        }
        attachmentService.delete(attachmentId);
        return o;
    }

    // ===================== 关闭 =====================

    @Transactional
    public ServiceTicket close(Long id) {
        Long cid = StaffGuard.communityId();
        ServiceTicket o = requireCommunity(id, cid, null);
        o.setStatus("CLOSED");
        o.setUpdatedAt(LocalDateTime.now());
        ticketMapper.updateById(o);
        return o;
    }

    // ===================== 物业端列表 =====================

    public Map<String, Object> staffRepairs(String status, int page, int pageSize) {
        Long cid = StaffGuard.communityId();
        escalateOverdue(cid);
        LambdaQueryWrapper<ServiceTicket> q = new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_TICKET)
                .eq(ServiceTicket::getKind, KIND_REPAIR)
                .eq(ServiceTicket::getCommunityId, cid)
                .orderByDesc(ServiceTicket::getId);
        if (status != null && !status.isBlank()) {
            q.eq(ServiceTicket::getStatus, status);
        }
        Page<ServiceTicket> p = ticketMapper.selectPage(new Page<>(page, pageSize), q);
        for (ServiceTicket o : p.getRecords()) {
            autoCompleteIfNeeded(o);
        }
        decorateRoom(p.getRecords());
        return pageOf(p, page, pageSize);
    }

    public Map<String, Object> staffComplaints(String status, int page, int pageSize) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<ServiceTicket> q = new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_TICKET)
                .eq(ServiceTicket::getKind, KIND_COMPLAINT)
                .eq(ServiceTicket::getCommunityId, cid)
                .orderByDesc(ServiceTicket::getId);
        if (status != null && !status.isBlank()) {
            q.eq(ServiceTicket::getStatus, status);
        }
        Page<ServiceTicket> p = ticketMapper.selectPage(new Page<>(page, pageSize), q);
        decorateRoom(p.getRecords());
        return pageOf(p, page, pageSize);
    }

    public Map<String, Object> staffRepairMine(int page, int pageSize) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = u.getCommunityId();
        if (cid == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换到物业身份");
        }
        escalateOverdue(cid);
        Set<String> myRoles = myRoleSet(u.getUserId(), cid);
        boolean office = myRoles.stream().anyMatch(StaffRoles::canLoginWeb)
                || u.isPlatformAdmin()
                || "PLATFORM".equals(u.getIdentityType());
        LambdaQueryWrapper<ServiceTicket> q = new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_TICKET)
                .eq(ServiceTicket::getCommunityId, cid)
                .in(ServiceTicket::getStatus, List.of("ASSIGNED", "IN_PROGRESS", "PROCESSING", "REPLIED"))
                .and(w -> {
                    // 指派到本人
                    w.eq(ServiceTicket::getAssigneeUserId, u.getUserId());
                    // 派到本人所属岗位、尚未接单
                    if (!myRoles.isEmpty()) {
                        w.or(x -> x.isNull(ServiceTicket::getAssigneeUserId)
                                .isNotNull(ServiceTicket::getAssigneeRole)
                                .in(ServiceTicket::getAssigneeRole, myRoles));
                    }
                    // 客服/经理：可见任意岗位「已派岗待接单」，便于跟进/转派（与办理权限一致）
                    if (office) {
                        w.or(x -> x.isNull(ServiceTicket::getAssigneeUserId)
                                .isNotNull(ServiceTicket::getAssigneeRole));
                    }
                })
                .orderByDesc(ServiceTicket::getId);
        Page<ServiceTicket> p = ticketMapper.selectPage(new Page<>(page, pageSize), q);
        for (ServiceTicket o : p.getRecords()) {
            autoCompleteIfNeeded(o);
        }
        return pageOf(p, page, pageSize);
    }

    /**
     * 我的已完成工单：已完成 / 已关闭 / 待评价，且指派给本人或本人所属岗位。
     */
    public Map<String, Object> staffRepairDone(int page, int pageSize) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = u.getCommunityId();
        if (cid == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换到物业身份");
        }
        Set<String> myRoles = myRoleSet(u.getUserId(), cid);
        LambdaQueryWrapper<ServiceTicket> q = new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_TICKET)
                .eq(ServiceTicket::getCommunityId, cid)
                .in(ServiceTicket::getStatus, List.of("DONE_WAIT_RATE", "COMPLETED", "CLOSED"))
                .and(w -> {
                    w.eq(ServiceTicket::getAssigneeUserId, u.getUserId())
                            .or().eq(ServiceTicket::getHandlerUserId, u.getUserId());
                    if (!myRoles.isEmpty()) {
                        w.or(x -> x.isNull(ServiceTicket::getAssigneeUserId)
                                .isNotNull(ServiceTicket::getAssigneeRole)
                                .in(ServiceTicket::getAssigneeRole, myRoles));
                    }
                })
                .orderByDesc(ServiceTicket::getCreatedAt);
        Page<ServiceTicket> p = ticketMapper.selectPage(new Page<>(page, pageSize), q);
        return pageOf(p, page, pageSize);
    }

    public Map<String, Object> staffRepairPool(int page, int pageSize) {
        Long cid = StaffGuard.communityId();
        escalateOverdue(cid);
        // 待派单池：报修 PENDING_ASSIGN + 投诉类 PENDING（尚未派岗）
        LambdaQueryWrapper<ServiceTicket> q = new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_TICKET)
                .eq(ServiceTicket::getCommunityId, cid)
                .and(w -> w.eq(ServiceTicket::getStatus, "PENDING_ASSIGN")
                        .or().eq(ServiceTicket::getStatus, "PENDING"))
                .orderByAsc(ServiceTicket::getId);
        Page<ServiceTicket> p = ticketMapper.selectPage(new Page<>(page, pageSize), q);
        return pageOf(p, page, pageSize);
    }

    public Map<String, Object> staffRepairGet(Long id) {
        ServiceTicket o = requireCommunity(id, StaffGuard.communityId(), null);
        autoCompleteIfNeeded(o);
        return detail(o, false);
    }

    public Map<String, Object> staffComplaintGet(Long id) {
        ServiceTicket c = requireCommunity(id, StaffGuard.communityId(), KIND_COMPLAINT);
        return detail(c, false);
    }

    // ===================== 业委会 / 平台 =====================

    /** 业委会：工单概况（兼容旧接口名；现返回报修+投诉全量，与 /committee/tickets 一致） */
    public Map<String, Object> committeeComplaints(int page, int pageSize) {
        return committeeTickets(null, page, pageSize);
    }

    /**
     * 业委会：本小区工单只读全量（报修 + 投诉类）；kind 为空则全部。
     */
    public Map<String, Object> committeeTickets(String kind, int page, int pageSize) {
        Long cid = CommitteeGuard.communityId();
        LambdaQueryWrapper<ServiceTicket> q = new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_TICKET)
                .eq(ServiceTicket::getCommunityId, cid)
                .orderByDesc(ServiceTicket::getId);
        if (KIND_REPAIR.equals(kind) || KIND_COMPLAINT.equals(kind)) {
            q.eq(ServiceTicket::getKind, kind);
        } else {
            q.in(ServiceTicket::getKind, KIND_REPAIR, KIND_COMPLAINT);
        }
        Page<ServiceTicket> p = ticketMapper.selectPage(new Page<>(page, pageSize), q);
        for (ServiceTicket o : p.getRecords()) {
            autoCompleteIfNeeded(o);
        }
        decorateRoom(p.getRecords());
        return pageOf(p, page, pageSize);
    }

    /** 业委会：工单详情只读（不脱敏，与物业详情同结构） */
    public Map<String, Object> committeeTicketGet(Long id) {
        Long cid = CommitteeGuard.communityId();
        ServiceTicket o = requireCommunity(id, cid, null);
        autoCompleteIfNeeded(o);
        return detail(o, false);
    }

    public Map<String, Object> platformRepairs(Long communityId, int page, int pageSize) {
        AuthUser u = AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台身份");
        }
        return platformList(KIND_REPAIR, communityId, page, pageSize);
    }

    public Map<String, Object> platformComplaints(Long communityId, int page, int pageSize) {
        AuthUser u = AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台身份");
        }
        return platformList(KIND_COMPLAINT, communityId, page, pageSize);
    }

    private Map<String, Object> platformList(String kind, Long communityId, int page, int pageSize) {
        LambdaQueryWrapper<ServiceTicket> q = new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_TICKET)
                .eq(ServiceTicket::getKind, kind)
                .orderByDesc(ServiceTicket::getId);
        if (communityId != null) {
            q.eq(ServiceTicket::getCommunityId, communityId);
        }
        Page<ServiceTicket> p = ticketMapper.selectPage(new Page<>(page, pageSize), q);
        return pageOf(p, page, pageSize);
    }

    // ===================== 内部工具 =====================

    private ServiceTicket insertReply(Long ticketId, Long cid, Long authorId, String content, LocalDateTime now) {
        ServiceTicket r = new ServiceTicket();
        r.setParentId(ticketId);
        r.setNodeType(NODE_REPLY);
        r.setCommunityId(cid);
        r.setAuthorUserId(authorId);
        r.setContent(content);
        r.setCreatedAt(now);
        ticketMapper.insert(r);
        return r;
    }

    private void insertDispatch(Long ticketId, Long cid, Long fromUserId, Long toUserId,
                               String toRole, String action, String remark, LocalDateTime now) {
        ServiceTicket d = new ServiceTicket();
        d.setParentId(ticketId);
        d.setNodeType(NODE_DISPATCH);
        d.setCommunityId(cid);
        d.setFromUserId(fromUserId);
        d.setToUserId(toUserId);
        d.setToRole(toRole);
        d.setAction(action);
        d.setRemark(remark == null || remark.isBlank() ? null : remark.trim());
        d.setCreatedAt(now);
        ticketMapper.insert(d);
    }

    private void autoCompleteIfNeeded(ServiceTicket o) {
        if (!KIND_REPAIR.equals(o.getKind())) {
            return;
        }
        if (!"DONE_WAIT_RATE".equals(o.getStatus()) || o.getCompletedAt() == null || o.getRating() != null) {
            return;
        }
        if (o.getCompletedAt().plusDays(7).isBefore(LocalDateTime.now())
                || o.getCompletedAt().plusDays(7).isEqual(LocalDateTime.now())) {
            o.setStatus("COMPLETED");
            o.setAutoCompleted(1);
            o.setUpdatedAt(LocalDateTime.now());
            ticketMapper.updateById(o);
            todoNotifyService.doneByBiz(KIND_REPAIR, o.getId(), "REPAIR_RATE");
        }
    }

    private void escalateOverdue(Long communityId) {
        LocalDateTime deadline = LocalDateTime.now().minusHours(24);
        List<ServiceTicket> list = ticketMapper.selectList(new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_TICKET)
                .eq(ServiceTicket::getKind, KIND_REPAIR)
                .eq(ServiceTicket::getCommunityId, communityId)
                .eq(ServiceTicket::getEscalatedToManager, 0)
                .isNotNull(ServiceTicket::getAssignedAt)
                .le(ServiceTicket::getAssignedAt, deadline)
                .isNull(ServiceTicket::getFirstReplyAt)
                .in(ServiceTicket::getStatus, List.of("ASSIGNED", "IN_PROGRESS")));
        for (ServiceTicket o : list) {
            o.setEscalatedToManager(1);
            o.setUpdatedAt(LocalDateTime.now());
            ticketMapper.updateById(o);
            List<StaffCommunity> managers = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                    .eq(StaffCommunity::getCommunityId, communityId)
                    .eq(StaffCommunity::getStaffRole, "PROPERTY_MANAGER")
                    .eq(StaffCommunity::getStatus, "ACTIVE"));
            for (StaffCommunity m : managers) {
                todoNotifyService.createTodo(communityId, m.getUserId(), "REPAIR_ESCALATE",
                        "报修超时未首复", "工单#" + o.getId(), KIND_REPAIR, o.getId());
            }
        }
    }

    private Map<String, Object> detail(ServiceTicket o, boolean residentView) {
        String kind = o.getKind();
        decorateRoom(List.of(o));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order", o);

        List<Map<String, Object>> orderAttachments = attachmentViews(kind, o.getId());
        m.put("attachments", orderAttachments);

        List<ServiceTicket> replies = ticketMapper.selectList(new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_REPLY)
                .eq(ServiceTicket::getParentId, o.getId())
                .orderByAsc(ServiceTicket::getId));
        List<Map<String, Object>> replyViews = new ArrayList<>();
        Map<Long, List<Map<String, Object>>> replyAttachments = new LinkedHashMap<>();
        for (ServiceTicket r : replies) {
            Map<String, Object> rv = new LinkedHashMap<>();
            rv.put("id", r.getId());
            rv.put("authorUserId", r.getAuthorUserId());
            rv.put("authorName", nameOf(r.getAuthorUserId()));
            rv.put("content", r.getContent());
            rv.put("createdAt", r.getCreatedAt());
            List<Map<String, Object>> ra = attachmentViews(kind + "_REPLY", r.getId());
            rv.put("attachments", ra);
            replyAttachments.put(r.getId(), ra);
            replyViews.add(rv);
        }
        m.put("replies", replyViews);

        List<ServiceTicket> dispatches = ticketMapper.selectList(new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, NODE_DISPATCH)
                .eq(ServiceTicket::getParentId, o.getId())
                .orderByAsc(ServiceTicket::getId));
        List<Map<String, Object>> dispatchViews = new ArrayList<>();
        for (ServiceTicket d : dispatches) {
            Map<String, Object> dv = new LinkedHashMap<>();
            dv.put("id", d.getId());
            dv.put("action", d.getAction());
            dv.put("actionLabel", actionLabel(d.getAction()));
            dv.put("fromUserId", d.getFromUserId());
            dv.put("fromUserName", nameOf(d.getFromUserId()));
            dv.put("toUserId", d.getToUserId());
            dv.put("toUserName", nameOf(d.getToUserId()));
            dv.put("toRole", d.getToRole());
            dv.put("toRoleLabel", d.getToRole() != null ? StaffRoles.label(d.getToRole()) + "岗" : null);
            dv.put("remark", d.getRemark());
            dv.put("createdAt", d.getCreatedAt());
            dispatchViews.add(dv);
        }
        m.put("dispatches", dispatchViews);

        m.put("timeline", buildTimeline(o, dispatches, replies, orderAttachments, replyAttachments, residentView));
        // 住户待办仍仅在「处理完成」时推送；处理过程中住户可查看完整流转（不再屏蔽）。
        return m;
    }

    private List<Map<String, Object>> attachmentViews(String bizType, Long bizId) {
        if (bizType == null || bizId == null) return new ArrayList<>();
        List<Attachment> list = attachmentService.listByBiz(bizType, bizId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Attachment a : list) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", a.getId());
            v.put("fileName", a.getFileName());
            v.put("contentType", a.getContentType());
            out.add(v);
        }
        return out;
    }

    private String nameOf(Long uid) {
        if (uid == null) return null;
        SysUser u = sysUserMapper.selectById(uid);
        if (u == null) return null;
        return u.getRealName() != null && !u.getRealName().isBlank() ? u.getRealName() : u.getMobile();
    }

    private String actionLabel(String action) {
        if ("ASSIGN".equals(action)) return "派单";
        if ("TRANSFER".equals(action)) return "转派";
        if ("CLAIM".equals(action)) return "接单";
        return action;
    }

    private List<Map<String, Object>> buildTimeline(ServiceTicket o, List<ServiceTicket> dispatches, List<ServiceTicket> replies,
                                                    List<Map<String, Object>> orderAttachments,
                                                    Map<Long, List<Map<String, Object>>> replyAttachments,
                                                    boolean residentView) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        String applicantName = nameOf(o.getApplicantUserId());
        String selfOrName = residentView ? "我" : (applicantName != null ? applicantName : "业主");

        Map<String, Object> create = new LinkedHashMap<>();
        create.put("type", "CREATE");
        create.put("title", "提交" + (KIND_COMPLAINT.equals(o.getKind()) ? o.getCategory() : "报修"));
        create.put("opName", selfOrName);
        create.put("opUserId", o.getApplicantUserId());
        create.put("content", o.getContent());
        create.put("images", orderAttachments);
        create.put("time", o.getCreatedAt());
        nodes.add(create);

        for (ServiceTicket d : dispatches) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", "DISPATCH");
            n.put("action", d.getAction());
            n.put("title", actionLabel(d.getAction()));
            n.put("opName", "CLAIM".equals(d.getAction()) ? nameOf(d.getToUserId()) : nameOf(d.getFromUserId()));
            String target = "CLAIM".equals(d.getAction()) ? null
                    : (d.getToRole() != null
                        ? StaffRoles.label(d.getToRole()) + "岗"
                        : (nameOf(d.getToUserId()) != null ? nameOf(d.getToUserId()) : ""));
            n.put("target", target);
            n.put("content", d.getRemark());
            n.put("time", d.getCreatedAt());
            nodes.add(n);
        }

        for (ServiceTicket r : replies) {
            String content = r.getContent();
            if (content != null && (content.startsWith("转派说明：") || content.startsWith("派单说明："))) {
                continue;
            }
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", "REPLY");
            n.put("replyId", r.getId());
            n.put("title", KIND_COMPLAINT.equals(o.getKind()) ? "物业回复" : "处理留言");
            n.put("opName", nameOf(r.getAuthorUserId()));
            n.put("content", content);
            n.put("images", replyAttachments.getOrDefault(r.getId(), new ArrayList<>()));
            n.put("time", r.getCreatedAt());
            nodes.add(n);
        }

        boolean done = o.getCompletedAt() != null
                && !List.of("PENDING_ASSIGN", "ASSIGNED", "IN_PROGRESS", "PENDING", "PROCESSING").contains(o.getStatus());
        if (done || "DONE_WAIT_RATE".equals(o.getStatus()) || "COMPLETED".equals(o.getStatus()) || "CLOSED".equals(o.getStatus())) {
            if (o.getCompletedAt() != null) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("type", "COMPLETE");
                n.put("title", "处理完成");
                n.put("opName", nameOf(o.getAssigneeUserId() != null ? o.getAssigneeUserId() : o.getHandlerUserId()));
                n.put("content", null);
                n.put("time", o.getCompletedAt());
                nodes.add(n);
            }
        }

        if (o.getRating() != null) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", "RATE");
            n.put("title", "业主评价");
            n.put("opName", selfOrName);
            n.put("opUserId", o.getApplicantUserId());
            n.put("rating", o.getRating());
            n.put("ratingResolved", o.getRatingResolved());
            n.put("ratingResponse", o.getRatingResponse());
            n.put("ratingHandling", o.getRatingHandling());
            n.put("ratingSatisfaction", o.getRatingSatisfaction());
            n.put("content", o.getRatingComment());
            n.put("time", o.getUpdatedAt() != null ? o.getUpdatedAt() : o.getCompletedAt());
            n.put("sortBias", 1);
            nodes.add(n);
        }

        nodes.sort((a, b) -> {
            LocalDateTime t1 = (LocalDateTime) a.get("time");
            LocalDateTime t2 = (LocalDateTime) b.get("time");
            int cmp;
            if (t1 == null && t2 == null) cmp = 0;
            else if (t1 == null) cmp = -1;
            else if (t2 == null) cmp = 1;
            else cmp = t1.compareTo(t2);
            if (cmp != 0) return cmp;
            int b1 = a.get("sortBias") instanceof Number ? ((Number) a.get("sortBias")).intValue() : 0;
            int b2 = b.get("sortBias") instanceof Number ? ((Number) b.get("sortBias")).intValue() : 0;
            return Integer.compare(b1, b2);
        });
        return nodes;
    }

    private ServiceTicket requireMine(Long id, String kind) {
        ServiceTicket o = ticketMapper.selectById(id);
        if (o == null || !NODE_TICKET.equals(o.getNodeType())
                || !AuthContext.require().getUserId().equals(o.getApplicantUserId())
                || (kind != null && !kind.equals(o.getKind()))) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "记录不存在");
        }
        return o;
    }

    private Set<String> myRoleSet(Long userId, Long communityId) {
        return staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                        .eq(StaffCommunity::getCommunityId, communityId)
                        .eq(StaffCommunity::getUserId, userId)
                        .eq(StaffCommunity::getStatus, "ACTIVE"))
                .stream()
                .map(s -> StaffRoles.normalize(s.getStaffRole()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private ServiceTicket requireCommunity(Long id, Long communityId, String kind) {
        ServiceTicket o = ticketMapper.selectById(id);
        if (o == null || !NODE_TICKET.equals(o.getNodeType())
                || !o.getCommunityId().equals(communityId)
                || (kind != null && !kind.equals(o.getKind()))) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "记录不存在");
        }
        return o;
    }

    private void notifyAllStaff(Long cid, Long bizId, String todoType, String title, String content, String bizType) {
        List<StaffCommunity> staffs = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, cid)
                .eq(StaffCommunity::getStatus, "ACTIVE"));
        // 同一个人可能身兼多岗（一人多行），必须按人去重，否则同一件事会推多条重复待办
        Set<Long> userIds = new LinkedHashSet<>();
        for (StaffCommunity s : staffs) {
            if (s.getUserId() != null) {
                userIds.add(s.getUserId());
            }
        }
        for (Long uid : userIds) {
            todoNotifyService.createTodo(cid, uid, todoType, title, content, bizType, bizId);
            todoNotifyService.skipSubscribe(cid, uid, todoType, bizType, bizId, "一阶段未接微信下发");
        }
    }

    /**
     * 为工单列表/详情批量组装可读房屋地址与处理人姓名。
     * 姓名从 sys_user 解析（含平台管理员等非小区团队成员），避免前端显示「员工#id」。
     */
    private void decorateRoom(List<ServiceTicket> records) {
        if (records == null || records.isEmpty()) return;

        Set<Long> userIds = new LinkedHashSet<>();
        for (ServiceTicket t : records) {
            if (t.getAssigneeUserId() != null) userIds.add(t.getAssigneeUserId());
            if (t.getHandlerUserId() != null) userIds.add(t.getHandlerUserId());
        }
        Map<Long, String> userNames = new LinkedHashMap<>();
        if (!userIds.isEmpty()) {
            for (SysUser u : sysUserMapper.selectByIds(userIds)) {
                String n = u.getRealName() != null && !u.getRealName().isBlank()
                        ? u.getRealName().trim()
                        : (u.getMobile() != null && !u.getMobile().isBlank()
                            ? u.getMobile().trim()
                            : (u.getUsername() != null ? u.getUsername() : null));
                if (n == null || n.isBlank()) n = "用户" + u.getId();
                userNames.put(u.getId(), n);
            }
        }
        for (ServiceTicket t : records) {
            if (t.getAssigneeUserId() != null) {
                t.setAssigneeName(userNames.getOrDefault(t.getAssigneeUserId(), "用户" + t.getAssigneeUserId()));
            }
            if (t.getHandlerUserId() != null) {
                t.setHandlerName(userNames.getOrDefault(t.getHandlerUserId(), "用户" + t.getHandlerUserId()));
            }
        }

        Set<Long> roomIds = records.stream()
                .map(ServiceTicket::getRoomId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (roomIds.isEmpty()) {
            for (ServiceTicket t : records) {
                if (t.getRoomLabel() == null) t.setRoomLabel("");
            }
            return;
        }
        Map<Long, Room> roomMap = roomMapper.selectByIds(roomIds).stream()
                .collect(Collectors.toMap(Room::getId, r -> r));
        Set<Long> bIds = new LinkedHashSet<>();
        Set<Long> uIds = new LinkedHashSet<>();
        Set<Long> fIds = new LinkedHashSet<>();
        for (Room r : roomMap.values()) {
            if (r.getBuildingId() != null) bIds.add(r.getBuildingId());
            if (r.getUnitId() != null) uIds.add(r.getUnitId());
            if (r.getFloorId() != null) fIds.add(r.getFloorId());
        }
        Map<Long, String> bName = bIds.isEmpty() ? Map.of()
                : buildingMapper.selectByIds(bIds).stream()
                .collect(Collectors.toMap(Building::getId, Building::getName));
        Map<Long, String> uName = uIds.isEmpty() ? Map.of()
                : unitMapper.selectByIds(uIds).stream()
                .collect(Collectors.toMap(Unit::getId, Unit::getName));
        Map<Long, String> fName = fIds.isEmpty() ? Map.of()
                : floorMapper.selectByIds(fIds).stream()
                .collect(Collectors.toMap(Floor::getId, fl -> {
                    String n = RoomPaths.floorLabel(fl);
                    return n == null ? "" : n;
                }));
        for (ServiceTicket t : records) {
            Room r = roomMap.get(t.getRoomId());
            if (r == null) {
                t.setRoomLabel("");
                continue;
            }
            String b = bName.getOrDefault(r.getBuildingId(), "");
            String u = uName.getOrDefault(r.getUnitId(), "");
            String f = fName.getOrDefault(r.getFloorId(), "");
            String no = r.getRoomNo() == null ? "" : r.getRoomNo();
            t.setRoomLabel(RoomPaths.format(b, u, f, no));
        }
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
