package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.domain.Attachment;
import com.property.mgmt.domain.AuthApplication;
import com.property.mgmt.domain.ParkingSpace;
import com.property.mgmt.domain.RoomChangeApplication;
import com.property.mgmt.service.AuthApplicationService;
import com.property.mgmt.service.ResidentDirectoryService;
import com.property.mgmt.service.ResidentRoomService;
import com.property.mgmt.service.RoomChangeService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/resident")
@RequiredArgsConstructor
public class ResidentController {

    private final AuthApplicationService authApplicationService;
    private final ResidentRoomService residentRoomService;
    private final RoomChangeService roomChangeService;
    private final ResidentDirectoryService residentDirectoryService;

    /** 切换小区：全部小区按城市分组 */
    @GetMapping("/communities/catalog")
    public ApiResponse<Map<String, Object>> communityCatalog() {
        return ApiResponse.ok(residentDirectoryService.catalogByCity());
    }

    /** 认证选小区：省市区 + 名称模糊 */
    @GetMapping("/communities")
    public ApiResponse<Map<String, Object>> searchCommunities(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String provinceName,
            @RequestParam(required = false) String cityName,
            @RequestParam(required = false) String districtName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(residentDirectoryService.searchCommunities(
                name, provinceName, cityName, districtName, page, pageSize));
    }

    /** 认证选房：小区空间树（仅 id/名称/房号） */
    @GetMapping("/communities/{id}/space-tree")
    public ApiResponse<Map<String, Object>> communitySpaceTree(@PathVariable Long id) {
        return ApiResponse.ok(residentDirectoryService.spaceTree(id));
    }

    @PostMapping("/auth-applications")
    public ApiResponse<AuthApplication> submit(@RequestBody AuthApplyReq req) {
        return ApiResponse.ok(authApplicationService.submit(
                req.getCommunityId(),
                req.getRoomId(),
                req.getApplicantName(),
                req.getApplicantIdCardNo(),
                req.getApplyRole(),
                req.getApplyMessage(),
                req.getAttachmentIds()));
    }

    @GetMapping("/auth-applications/mine")
    public ApiResponse<List<Map<String, Object>>> mine() {
        return ApiResponse.ok(authApplicationService.mineViews());
    }

    @GetMapping("/auth-applications/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        return ApiResponse.ok(authApplicationService.getMineView(id));
    }

    @GetMapping("/rooms")
    public ApiResponse<List<Map<String, Object>>> myRooms() {
        return ApiResponse.ok(residentRoomService.myRooms());
    }

    @GetMapping("/rooms/{roomId}")
    public ApiResponse<Map<String, Object>> roomDetail(@PathVariable Long roomId) {
        return ApiResponse.ok(residentRoomService.roomDetail(roomId));
    }

    @GetMapping("/rooms/{roomId}/archives")
    public ApiResponse<List<Attachment>> archives(@PathVariable Long roomId) {
        return ApiResponse.ok(residentRoomService.archives(roomId));
    }

    @GetMapping("/parking-spaces/unlinked")
    public ApiResponse<List<ParkingSpace>> unlinkedParking() {
        return ApiResponse.ok(residentRoomService.unlinkedParking());
    }

    /** 增加成员：输入手机号后匹配已有账号，回填姓名（仅返回是否存在与姓名） */
    @GetMapping("/users/by-mobile")
    public ApiResponse<Map<String, Object>> lookupByMobile(@RequestParam String mobile) {
        return ApiResponse.ok(residentRoomService.lookupUserByMobile(mobile));
    }

    @PostMapping("/room-change-applications")
    public ApiResponse<RoomChangeApplication> submitChange(@RequestBody RoomChangeReq req) {
        return ApiResponse.ok(roomChangeService.submit(
                req.getChangeType(), req.getPayload(), req.getApplyMessage(), req.getAttachmentIds()));
    }

    @GetMapping("/room-change-applications/mine")
    public ApiResponse<List<Map<String, Object>>> myChanges() {
        return ApiResponse.ok(roomChangeService.mineViews());
    }

    @Data
    public static class AuthApplyReq {
        @NotNull
        private Long communityId;
        @NotNull
        private Long roomId;
        @NotBlank
        private String applicantName;
        /** 已废弃：服务端固定用当前登录用户手机号，客户端勿再依赖此字段 */
        private String applicantMobile;
        private String applicantIdCardNo;
        @NotBlank
        private String applyRole;
        private String applyMessage;
        private List<Long> attachmentIds;
    }

    @Data
    public static class RoomChangeReq {
        @NotBlank
        private String changeType;
        private Map<String, Object> payload;
        private String applyMessage;
        private List<Long> attachmentIds;
    }
}
