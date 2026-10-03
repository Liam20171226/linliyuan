package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.Community;
import com.property.mgmt.service.AuthService;
import com.property.mgmt.service.FixService;
import com.property.mgmt.service.PlatformService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PlatformController {

    private final PlatformService platformService;
    private final FixService fixService;
    private final AuthService authService;

    @PostMapping("/auth/platform/change-password")
    public ApiResponse<Map<String, Object>> changePassword(@RequestBody ChangePwdReq req) {
        return ApiResponse.ok(platformService.changePlatformPassword(req.getOldPassword(), req.getNewPassword()));
    }

    @PostMapping("/platform/communities")
    public ApiResponse<Map<String, Object>> createCommunity(@RequestBody CommunityReq req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "小区名称不能为空");
        }
        return ApiResponse.ok(platformService.createCommunity(
                req.getName(), req.getAddress(), req.getIntro(), req.getContactPhone(), req.getHasFormalCommittee(),
                req.getProvinceCode(), req.getProvinceName(), req.getCityCode(), req.getCityName(),
                req.getDistrictCode(), req.getDistrictName(), req.getAddressDetail()));
    }

    @GetMapping("/platform/communities")
    public ApiResponse<Map<String, Object>> listCommunities(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String provinceName,
            @RequestParam(required = false) String cityName,
            @RequestParam(required = false) String districtName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(platformService.listCommunities(
                name, provinceName, cityName, districtName, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/platform/communities/{id}")
    public ApiResponse<Community> getCommunity(@PathVariable Long id) {
        return ApiResponse.ok(platformService.getCommunity(id));
    }

    @PutMapping("/platform/communities/{id}")
    public ApiResponse<Void> updateCommunity(@PathVariable Long id, @RequestBody CommunityReq req) {
        platformService.updateCommunity(id, req.getName(), req.getAddress(), req.getIntro(),
                req.getContactPhone(), req.getHasFormalCommittee(),
                req.getProvinceCode(), req.getProvinceName(), req.getCityCode(), req.getCityName(),
                req.getDistrictCode(), req.getDistrictName(), req.getAddressDetail());
        return ApiResponse.ok();
    }

    @DeleteMapping("/platform/communities/{id}")
    public ApiResponse<Map<String, Object>> deleteCommunity(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force) {
        return ApiResponse.ok(platformService.deleteCommunity(id, force));
    }

    @PostMapping("/platform/staff-users")
    public ApiResponse<Map<String, Object>> createStaffUser(@RequestBody StaffUserReq req) {
        return ApiResponse.ok(platformService.createStaffUser(req.getMobile(), req.getPassword(), req.getRealName()));
    }

    @PostMapping("/platform/communities/{communityId}/staff")
    public ApiResponse<Void> assignStaff(@PathVariable Long communityId, @RequestBody StaffAssignReq req) {
        platformService.assignStaff(communityId, req.getUserId(), req.getStaffRole());
        return ApiResponse.ok();
    }

    @GetMapping("/platform/communities/{communityId}/staff")
    public ApiResponse<List<Map<String, Object>>> listStaff(@PathVariable Long communityId) {
        return ApiResponse.ok(platformService.listStaffDetail(communityId));
    }

    @PutMapping("/platform/communities/{communityId}/staff/{userId}")
    public ApiResponse<Void> updateStaff(@PathVariable Long communityId, @PathVariable Long userId,
                                         @RequestBody StaffUpdateReq req) {
        platformService.updateStaff(communityId, userId, req.getStaffRole(), req.getStatus());
        return ApiResponse.ok();
    }

    @PostMapping("/platform/communities/{id}/enter")
    public ApiResponse<Map<String, Object>> enterCommunity(@PathVariable Long id) {
        return ApiResponse.ok(authService.platformEnterCommunity(id));
    }

    @PostMapping("/platform/context/exit-community")
    public ApiResponse<Map<String, Object>> exitCommunity() {
        return ApiResponse.ok(authService.platformExitCommunity());
    }

    @PostMapping("/platform/users/reset-password")
    public ApiResponse<Void> resetUserPassword(@RequestBody ResetPwdReq req) {
        platformService.resetUserPassword(req.getMobile(), req.getNewPassword());
        return ApiResponse.ok();
    }

    @GetMapping("/platform/users")
    public ApiResponse<Map<String, Object>> listUsers(
            @RequestParam(required = false) String mobile,
            @RequestParam(required = false) String realName,
            @RequestParam(required = false) Long communityId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "false") boolean managerOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(platformService.listUsers(
                mobile, realName, communityId, status, managerOnly, page, Math.min(pageSize, 100)));
    }

    @PutMapping("/platform/users/{id}")
    public ApiResponse<Void> updateUser(@PathVariable Long id, @RequestBody UserUpdateReq req) {
        platformService.updateUser(id, req.getRealName(), req.getMobile());
        return ApiResponse.ok();
    }

    @PostMapping("/platform/users/{id}/enable")
    public ApiResponse<Map<String, Object>> enableUser(@PathVariable Long id) {
        return ApiResponse.ok(platformService.enableUser(id));
    }

    @DeleteMapping("/platform/users/{id}")
    public ApiResponse<Map<String, Object>> deleteOrDisableUser(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean hard) {
        if (hard) {
            return ApiResponse.ok(platformService.purgeUser(id));
        }
        return ApiResponse.ok(platformService.disableUser(id));
    }

    @GetMapping("/platform/guests")
    public ApiResponse<Map<String, Object>> listGuests(
            @RequestParam(required = false) String mobile,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(platformService.listGuests(mobile, page, pageSize));
    }

    @PutMapping("/platform/guests/{id}")
    public ApiResponse<Map<String, Object>> updateGuest(@PathVariable Long id, @RequestBody GuestUpdateReq req) {
        return ApiResponse.ok(platformService.updateGuest(id, req.getRealName(), req.getMobile(), req.getPassword()));
    }

    @PostMapping("/platform/guests/batch-delete")
    public ApiResponse<Map<String, Object>> deleteGuests(@RequestBody GuestBatchDeleteReq req) {
        return ApiResponse.ok(platformService.deleteGuests(req.getIds()));
    }

    @PutMapping("/platform/users/{id}/staff-community")
    public ApiResponse<Map<String, Object>> setUserStaffCommunity(
            @PathVariable Long id, @RequestBody StaffCommunitySetReq req) {
        return ApiResponse.ok(platformService.setUserStaffCommunity(id, req.getCommunityId(), req.getStaffRole()));
    }

    /** 人员弹窗：批量同步多社区物业岗位（items 为空则清掉此人全部物业 ACTIVE；不改业委会） */
    @PutMapping("/platform/users/{id}/community-roles")
    public ApiResponse<Map<String, Object>> setUserCommunityRoles(
            @PathVariable Long id, @RequestBody CommunityRolesBatchReq req) {
        if (req.getItems() != null) {
            return ApiResponse.ok(platformService.setUserCommunityRolesBatch(id, req.getItems()));
        }
        // 兼容旧单小区 body
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("communityId", req.getCommunityId());
        one.put("staffRole", req.getStaffRole());
        one.put("committeeTitle", req.getCommitteeTitle());
        return ApiResponse.ok(platformService.setUserCommunityRolesBatch(id, List.of(one)));
    }

    @PostMapping("/platform/fixes/occupants")
    public ApiResponse<Map<String, Object>> fixOccupants(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(fixService.fixOccupant(true, body));
    }

    @PostMapping("/platform/fixes/vehicles")
    public ApiResponse<Map<String, Object>> fixVehicles(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(fixService.fixVehicle(true, body));
    }

    @PostMapping("/platform/fixes/parking-links")
    public ApiResponse<Map<String, Object>> fixParkingLinks(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(fixService.fixParkingLink(true, body));
    }

    @Data
    public static class CommunityReq {
        private String name;
        private String address;
        private String intro;
        private String contactPhone;
        private Integer hasFormalCommittee;
        private String provinceCode;
        private String provinceName;
        private String cityCode;
        private String cityName;
        private String districtCode;
        private String districtName;
        private String addressDetail;
    }

    @Data
    public static class StaffUserReq {
        @NotBlank
        private String mobile;
        @NotBlank
        private String password;
        private String realName;
    }

    @Data
    public static class StaffAssignReq {
        @NotNull
        private Long userId;
        @NotBlank
        private String staffRole;
    }

    @Data
    public static class StaffUpdateReq {
        private String staffRole;
        private String status;
    }

    @Data
    public static class ChangePwdReq {
        @NotBlank
        private String oldPassword;
        @NotBlank
        private String newPassword;
    }

    @Data
    public static class ResetPwdReq {
        @NotBlank
        private String mobile;
        @NotBlank
        private String newPassword;
    }

    @Data
    public static class UserUpdateReq {
        private String realName;
        private String mobile;
    }

    @Data
    public static class GuestUpdateReq {
        private String realName;
        private String mobile;
        private String password;
    }

    @Data
    public static class GuestBatchDeleteReq {
        private List<Long> ids;
    }

    @Data
    public static class StaffCommunitySetReq {
        @NotNull
        private Long communityId;
        private String staffRole;
    }

    @Data
    public static class CommunityRolesBatchReq {
        /** 优先：多社区任职列表；每项含 communityId + staffRole 和/或 committeeTitle */
        private List<Map<String, Object>> items;
        /** 兼容旧单小区 */
        private Long communityId;
        private String staffRole;
        private String committeeTitle;
    }
}
