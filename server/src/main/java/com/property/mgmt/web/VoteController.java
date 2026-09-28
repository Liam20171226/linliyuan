package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.domain.VoteBallot;
import com.property.mgmt.service.VoteService;
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
public class VoteController {

    private final VoteService voteService;

    @PostMapping("/committee/votes")
    public ApiResponse<Map<String, Object>> createCommittee(@RequestBody CreateReq req) {
        return ApiResponse.ok(voteService.create(
                null, req.getTitle(), req.getBackground(), req.getStartAt(), req.getEndAt(),
                req.getOptions(), "COMMITTEE"));
    }

    @PostMapping("/staff/votes")
    public ApiResponse<Map<String, Object>> createStaff(@RequestBody CreateReq req) {
        return ApiResponse.ok(voteService.create(
                null, req.getTitle(), req.getBackground(), req.getStartAt(), req.getEndAt(),
                req.getOptions(), "PROPERTY_MANAGER"));
    }

    @PostMapping("/platform/votes")
    public ApiResponse<Map<String, Object>> createPlatform(@RequestBody CreateReq req) {
        return ApiResponse.ok(voteService.create(
                req.getCommunityId(), req.getTitle(), req.getBackground(), req.getStartAt(), req.getEndAt(),
                req.getOptions(), "PLATFORM"));
    }

    @DeleteMapping("/votes/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        voteService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/resident/votes")
    public ApiResponse<Map<String, Object>> residentList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(voteService.residentList(page, Math.min(pageSize, 100)));
    }

    @GetMapping("/resident/votes/{id}")
    public ApiResponse<Map<String, Object>> residentGet(@PathVariable Long id) {
        return ApiResponse.ok(voteService.residentGet(id));
    }

    @PostMapping("/resident/votes/{id}/ballots")
    public ApiResponse<List<VoteBallot>> cast(@PathVariable Long id, @RequestBody BallotReq req) {
        return ApiResponse.ok(voteService.castBallots(id, req.getBallots()));
    }

    @GetMapping("/votes/{id}/stats")
    public ApiResponse<Map<String, Object>> stats(@PathVariable Long id) {
        return ApiResponse.ok(voteService.stats(id));
    }

    @GetMapping("/staff/votes")
    public ApiResponse<Map<String, Object>> staffList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(voteService.staffList(page, Math.min(pageSize, 100)));
    }

    /** 平台进入小区：业委会投票列表（物业经理/客服不可见） */
    @GetMapping("/staff/votes/committee")
    public ApiResponse<Map<String, Object>> staffCommitteeList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(voteService.staffCommitteeList(page, Math.min(pageSize, 100)));
    }

    @GetMapping("/committee/votes")
    public ApiResponse<Map<String, Object>> committeeList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(voteService.committeeList(page, Math.min(pageSize, 100)));
    }

    @GetMapping("/platform/votes")
    public ApiResponse<Map<String, Object>> platformList(
            @RequestParam(required = false) Long communityId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(voteService.platformList(communityId, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/share/votes/{id}")
    public ApiResponse<Map<String, Object>> share(@PathVariable Long id) {
        return ApiResponse.ok(voteService.shareGet(id));
    }

    @Data
    public static class CreateReq {
        private Long communityId;
        private String title;
        private String background;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startAt;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime endAt;
        private List<String> options;
    }

    @Data
    public static class BallotReq {
        private List<Map<String, Object>> ballots;
    }
}
