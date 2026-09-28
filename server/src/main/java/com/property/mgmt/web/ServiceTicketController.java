package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.domain.ServiceTicket;
import com.property.mgmt.service.ServiceTicketService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 报事报修统一接口。报修（公区报障/室内维修）与投诉/表扬/咨询建议共用 service_ticket 表，
 * 接口路径沿用原 /repairs、/complaints 命名，便于前端平滑迁移。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ServiceTicketController {

    private final ServiceTicketService service;

    // ============ 报修（REPAIR） ============

    @PostMapping("/resident/repairs")
    public ApiResponse<ServiceTicket> createRepair(@RequestBody CreateRepairReq req) {
        return ApiResponse.ok(service.createRepair(
                req.getRoomId(), req.getCategory(), req.getLocation(), req.getDescription(),
                req.getAttachmentIds()));
    }

    @GetMapping("/resident/repairs")
    public ApiResponse<Map<String, Object>> myRepairs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.residentRepairs(page, Math.min(pageSize, 100)));
    }

    @GetMapping("/resident/repairs/{id}")
    public ApiResponse<Map<String, Object>> getMyRepair(@PathVariable Long id) {
        return ApiResponse.ok(service.residentRepairGet(id));
    }

    @PostMapping("/staff/repairs/{id}/assign")
    public ApiResponse<ServiceTicket> assign(@PathVariable Long id, @RequestBody AssignReq req) {
        return ApiResponse.ok(service.assign(id, req.getAssigneeUserId(), req.getAssigneeRole(), req.getRemark()));
    }

    @PostMapping("/staff/repairs/{id}/claim")
    public ApiResponse<ServiceTicket> claim(@PathVariable Long id) {
        return ApiResponse.ok(service.claim(id));
    }

    @PostMapping("/staff/repairs/{id}/replies")
    public ApiResponse<ServiceTicket> reply(@PathVariable Long id, @RequestBody ReplyReq req) {
        return ApiResponse.ok(service.reply(id, req.getContent(), req.getAttachmentIds()));
    }

    @PostMapping("/staff/repairs/{id}/complete")
    public ApiResponse<ServiceTicket> complete(@PathVariable Long id) {
        return ApiResponse.ok(service.complete(id));
    }

    /** 物业经理 / 客服：处理完成前修改被派单岗位的留言 */
    @PutMapping("/staff/repairs/{id}/replies/{replyId}")
    public ApiResponse<ServiceTicket> updateRepairReply(@PathVariable Long id, @PathVariable Long replyId,
                                                        @RequestBody ReplyReq req) {
        return ApiResponse.ok(service.updateReply(id, replyId, req.getContent()));
    }

    /** 物业经理 / 客服：处理完成前删除被派单岗位的留言（含其图片） */
    @DeleteMapping("/staff/repairs/{id}/replies/{replyId}")
    public ApiResponse<ServiceTicket> deleteRepairReply(@PathVariable Long id, @PathVariable Long replyId) {
        return ApiResponse.ok(service.deleteReply(id, replyId));
    }

    /** 物业经理 / 客服：处理完成前删除某条留言下的单张图片 */
    @DeleteMapping("/staff/repairs/{id}/replies/{replyId}/attachments/{attachmentId}")
    public ApiResponse<ServiceTicket> deleteRepairReplyAttachment(@PathVariable Long id, @PathVariable Long replyId,
                                                                  @PathVariable Long attachmentId) {
        return ApiResponse.ok(service.deleteReplyAttachment(id, replyId, attachmentId));
    }

    @PostMapping("/resident/repairs/{id}/rate")
    public ApiResponse<ServiceTicket> rate(@PathVariable Long id, @RequestBody RateReq req) {
        return ApiResponse.ok(service.rate(id, req.getRating(), req.getComment(),
                req.getResolved(), req.getResponseScore(), req.getHandlingScore(), req.getSatisfactionScore()));
    }

    @PostMapping("/staff/repairs/{id}/close")
    public ApiResponse<ServiceTicket> closeRepair(@PathVariable Long id) {
        return ApiResponse.ok(service.close(id));
    }

    @GetMapping("/staff/repairs/mine")
    public ApiResponse<Map<String, Object>> staffRepairMine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.staffRepairMine(page, pageSize));
    }

    @GetMapping("/staff/repairs/done")
    public ApiResponse<Map<String, Object>> staffRepairDone(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.staffRepairDone(page, pageSize));
    }

    @GetMapping("/staff/repairs/pool")
    public ApiResponse<Map<String, Object>> staffRepairPool(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.staffRepairPool(page, pageSize));
    }

    @GetMapping("/staff/repairs")
    public ApiResponse<Map<String, Object>> staffRepairs(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.staffRepairs(status, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/staff/repairs/{id}")
    public ApiResponse<Map<String, Object>> staffRepairGet(@PathVariable Long id) {
        return ApiResponse.ok(service.staffRepairGet(id));
    }

    @GetMapping("/platform/repairs")
    public ApiResponse<Map<String, Object>> platformRepairs(
            @RequestParam(required = false) Long communityId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.platformRepairs(communityId, page, Math.min(pageSize, 100)));
    }

    // ============ 投诉/表扬/咨询（COMPLAINT） ============

    @PostMapping("/resident/complaints")
    public ApiResponse<ServiceTicket> createComplaint(@RequestBody CreateComplaintReq req) {
        return ApiResponse.ok(service.createComplaint(
                req.getRoomId(), req.getContactName(), req.getContactMobile(),
                req.getCategory(), req.getTargetName(), req.getContent(), req.getAttachmentIds()));
    }

    @GetMapping("/resident/complaints")
    public ApiResponse<Map<String, Object>> myComplaints(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.residentComplaints(page, Math.min(pageSize, 100)));
    }

    @GetMapping("/resident/complaints/{id}")
    public ApiResponse<Map<String, Object>> getMyComplaint(@PathVariable Long id) {
        return ApiResponse.ok(service.residentComplaintGet(id));
    }

    @PostMapping("/staff/complaints/{id}/handle")
    public ApiResponse<ServiceTicket> handle(@PathVariable Long id, @RequestBody HandleReq req) {
        return ApiResponse.ok(service.handle(id, req.getReplyContent(), req.getAttachmentIds()));
    }

    /** 投诉 / 咨询建议 / 表扬：派给岗位（与报修同一套派单规则） */
    @PostMapping("/staff/complaints/{id}/assign")
    public ApiResponse<ServiceTicket> assignComplaint(@PathVariable Long id, @RequestBody AssignReq req) {
        return ApiResponse.ok(service.assign(id, req.getAssigneeUserId(), req.getAssigneeRole(), req.getRemark()));
    }

    @PostMapping("/staff/complaints/{id}/claim")
    public ApiResponse<ServiceTicket> claimComplaint(@PathVariable Long id) {
        return ApiResponse.ok(service.claim(id));
    }

    @PostMapping("/staff/complaints/{id}/close")
    public ApiResponse<ServiceTicket> closeComplaint(@PathVariable Long id) {
        return ApiResponse.ok(service.close(id));
    }

    /** 投诉 / 咨询建议 / 表扬 的「处理完成」：本小区物业人员均可点击 */
    @PostMapping("/staff/complaints/{id}/complete")
    public ApiResponse<ServiceTicket> completeComplaint(@PathVariable Long id) {
        return ApiResponse.ok(service.completeComplaint(id));
    }

    /** 住户在完成处理后评价（维度与报修一致；表扬不参与满意度评价） */
    @PostMapping("/resident/complaints/{id}/rate")
    public ApiResponse<ServiceTicket> rateComplaint(@PathVariable Long id, @RequestBody RateReq req) {
        return ApiResponse.ok(service.rateComplaint(id, req.getRating(), req.getComment(),
                req.getResolved(), req.getResponseScore(), req.getHandlingScore(), req.getSatisfactionScore()));
    }

    /** 物业经理 / 客服：处理完成前修改留言 */
    @PutMapping("/staff/complaints/{id}/replies/{replyId}")
    public ApiResponse<ServiceTicket> updateComplaintReply(@PathVariable Long id, @PathVariable Long replyId,
                                                           @RequestBody HandleReq req) {
        return ApiResponse.ok(service.updateReply(id, replyId, req.getReplyContent()));
    }

    /** 物业经理 / 客服：处理完成前删除留言（含其图片） */
    @DeleteMapping("/staff/complaints/{id}/replies/{replyId}")
    public ApiResponse<ServiceTicket> deleteComplaintReply(@PathVariable Long id, @PathVariable Long replyId) {
        return ApiResponse.ok(service.deleteReply(id, replyId));
    }

    @DeleteMapping("/staff/complaints/{id}/replies/{replyId}/attachments/{attachmentId}")
    public ApiResponse<ServiceTicket> deleteComplaintReplyAttachment(@PathVariable Long id, @PathVariable Long replyId,
                                                                     @PathVariable Long attachmentId) {
        return ApiResponse.ok(service.deleteReplyAttachment(id, replyId, attachmentId));
    }

    @GetMapping("/staff/complaints")
    public ApiResponse<Map<String, Object>> staffComplaints(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.staffComplaints(status, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/staff/complaints/{id}")
    public ApiResponse<Map<String, Object>> staffComplaintGet(@PathVariable Long id) {
        return ApiResponse.ok(service.staffComplaintGet(id));
    }

    @GetMapping("/committee/complaints/overview")
    public ApiResponse<Map<String, Object>> committeeComplaints(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.committeeComplaints(page, Math.min(pageSize, 100)));
    }

    /** 业委会：本小区工单只读（报修+投诉）；可选 kind=REPAIR|COMPLAINT */
    @GetMapping("/committee/tickets")
    public ApiResponse<Map<String, Object>> committeeTickets(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.committeeTickets(kind, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/committee/tickets/{id}")
    public ApiResponse<Map<String, Object>> committeeTicketGet(@PathVariable Long id) {
        return ApiResponse.ok(service.committeeTicketGet(id));
    }

    @GetMapping("/platform/complaints")
    public ApiResponse<Map<String, Object>> platformComplaints(
            @RequestParam(required = false) Long communityId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.platformComplaints(communityId, page, Math.min(pageSize, 100)));
    }

    // ============ DTO ============

    @Data
    public static class CreateRepairReq {
        private Long roomId;
        private String category;
        private String location;
        private String description;
        private List<Long> attachmentIds;
    }

    @Data
    public static class AssignReq {
        private Long assigneeUserId;
        private String assigneeRole;
        private String remark;
    }

    @Data
    public static class ReplyReq {
        private String content;
        private List<Long> attachmentIds;
    }

    @Data
    public static class RateReq {
        /** 总体评分 1~5 */
        private Integer rating;
        private String comment;
        /** 是否处理完成：1=是 0=否 */
        private Integer resolved;
        /** 响应及时度 1~5 */
        private Integer responseScore;
        /** 处理及时情况 1~5 */
        private Integer handlingScore;
        /** 处理满意情况 1~5 */
        private Integer satisfactionScore;
    }

    @Data
    public static class CreateComplaintReq {
        private Long roomId;
        private String contactName;
        private String contactMobile;
        private String category;
        private String targetName;
        private String content;
        private List<Long> attachmentIds;
    }

    @Data
    public static class HandleReq {
        private String replyContent;
        private List<Long> attachmentIds;
    }
}
