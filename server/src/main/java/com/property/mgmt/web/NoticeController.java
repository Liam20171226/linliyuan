package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.domain.Notice;
import com.property.mgmt.service.NoticeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @PostMapping("/staff/notices")
    public ApiResponse<Notice> createStaff(@RequestBody NoticeReq req) {
        return ApiResponse.ok(noticeService.create(
                req.getTitle(), req.getContent(), req.getNoticeType(), req.getUrgency(),
                req.getVisibility(), req.getEffectiveAt(), req.getExpireAt(),
                req.getAttachmentIds(), req.getCoverAttachmentId(), req.getShowOnBanner(), req.getBannerOrder(), "STAFF"));
    }

    @PostMapping("/committee/notices")
    public ApiResponse<Notice> createCommittee(@RequestBody NoticeReq req) {
        return ApiResponse.ok(noticeService.create(
                req.getTitle(), req.getContent(), req.getNoticeType(), req.getUrgency(),
                req.getVisibility(), req.getEffectiveAt(), req.getExpireAt(),
                req.getAttachmentIds(), req.getCoverAttachmentId(), req.getShowOnBanner(), req.getBannerOrder(), "COMMITTEE"));
    }

    @PutMapping({"/staff/notices/{id}", "/committee/notices/{id}"})
    public ApiResponse<Notice> update(@PathVariable Long id, @RequestBody NoticeReq req) {
        return ApiResponse.ok(noticeService.update(
                id, req.getTitle(), req.getContent(), req.getNoticeType(), req.getUrgency(),
                req.getVisibility(), req.getEffectiveAt(), req.getExpireAt(),
                req.getAttachmentIds(), req.getCoverAttachmentId(), req.getShowOnBanner(), req.getBannerOrder()));
    }

    @DeleteMapping({"/staff/notices/{id}", "/committee/notices/{id}"})
    public ApiResponse<Void> deleteMine(@PathVariable Long id) {
        noticeService.softDelete(id, false);
        return ApiResponse.ok();
    }

    @DeleteMapping("/platform/notices/{id}")
    public ApiResponse<Void> deletePlatform(@PathVariable Long id) {
        noticeService.softDelete(id, true);
        return ApiResponse.ok();
    }

    @GetMapping("/notices")
    public ApiResponse<Map<String, Object>> listVisible(
            @RequestParam(required = false) Long communityId,
            @RequestParam(required = false) String creatorIdentity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(noticeService.listVisible(
                communityId, creatorIdentity, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/notices/home")
    public ApiResponse<Map<String, Object>> homeFeed(@RequestParam(required = false) Long communityId) {
        return ApiResponse.ok(noticeService.homeFeed(communityId));
    }

    @GetMapping("/notices/{id}")
    public ApiResponse<Notice> get(@PathVariable Long id) {
        return ApiResponse.ok(noticeService.getVisible(id));
    }

    @GetMapping("/staff/notices")
    public ApiResponse<Map<String, Object>> staffMine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(noticeService.listMine("STAFF", page, Math.min(pageSize, 100)));
    }

    @GetMapping("/committee/notices/mine")
    public ApiResponse<Map<String, Object>> committeeMine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(noticeService.listMine("COMMITTEE", page, Math.min(pageSize, 100)));
    }

    /** 业委会：本小区全部业委通知（含他人） */
    @GetMapping("/committee/notices")
    public ApiResponse<Map<String, Object>> committeeAll(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(noticeService.listCommitteeAll(page, Math.min(pageSize, 100)));
    }

    @GetMapping("/platform/notices")
    public ApiResponse<Map<String, Object>> platformList(
            @RequestParam(required = false) Long communityId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(noticeService.platformList(communityId, page, Math.min(pageSize, 100)));
    }

    @Data
    public static class NoticeReq {
        private String title;
        private String content;
        private String noticeType;
        private Integer urgency;
        private String visibility;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime effectiveAt;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime expireAt;
        private List<Long> attachmentIds;
        private Long coverAttachmentId;
        private Boolean showOnBanner;
        private Integer bannerOrder;
    }
}
