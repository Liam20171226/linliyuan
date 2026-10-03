package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.domain.*;
import com.property.mgmt.service.AuthApplicationService;
import com.property.mgmt.service.CommitteeService;
import com.property.mgmt.service.FixService;
import com.property.mgmt.service.ImportService;
import com.property.mgmt.service.ParkingService;
import com.property.mgmt.service.RoomChangeService;
import com.property.mgmt.service.OccupantQueryService;
import com.property.mgmt.service.StaffNavBadgeService;
import com.property.mgmt.service.StaffSpaceService;
import com.property.mgmt.service.StaffTeamService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
public class StaffController {

    private final StaffSpaceService spaceService;
    private final ParkingService parkingService;
    private final CommitteeService committeeService;
    private final AuthApplicationService authApplicationService;
    private final RoomChangeService roomChangeService;
    private final ImportService importService;
    private final FixService fixService;
    private final StaffNavBadgeService staffNavBadgeService;
    private final StaffTeamService staffTeamService;
    private final OccupantQueryService occupantQueryService;

    // ----- community -----

    @GetMapping("/nav-badges")
    public ApiResponse<Map<String, Object>> navBadges() {
        return ApiResponse.ok(staffNavBadgeService.badges());
    }

    @GetMapping("/team")
    public ApiResponse<Map<String, Object>> team() {
        return ApiResponse.ok(staffTeamService.listTeam());
    }

    @GetMapping("/team/role-catalog")
    public ApiResponse<Map<String, Object>> teamRoleCatalog() {
        return ApiResponse.ok(staffTeamService.roleCatalog());
    }

    @PostMapping("/team/members")
    public ApiResponse<Map<String, Object>> addTeamMember(@RequestBody TeamAddReq req) {
        return ApiResponse.ok(staffTeamService.addMember(
                req.getMobile(), req.getRealName(), req.getTempPassword(), req.getLineRoles()));
    }

    @PutMapping("/team/members/{userId}/line-roles")
    public ApiResponse<Map<String, Object>> syncTeamLineRoles(
            @PathVariable Long userId, @RequestBody TeamLineRolesReq req) {
        return ApiResponse.ok(staffTeamService.syncLineRoles(
                userId, req.getLineRoles(), req.getWebPassword()));
    }

    /** 重置团队成员 Web 登录密码（物业经理 / 平台管理员） */
    @PostMapping("/team/members/{userId}/reset-password")
    public ApiResponse<Map<String, Object>> resetTeamMemberPassword(
            @PathVariable Long userId, @RequestBody TeamResetPwdReq req) {
        return ApiResponse.ok(staffTeamService.resetMemberPassword(userId, req.getNewPassword()));
    }

    @GetMapping("/community")
    public ApiResponse<Community> community() {
        return ApiResponse.ok(spaceService.getCommunity());
    }

    @PutMapping("/community")
    public ApiResponse<Void> updateCommunity(@RequestBody CommunityUpdateReq req) {
        spaceService.updateCommunity(req.getName(), req.getAddress(), req.getIntro(),
                req.getContactPhone(), req.getCoverAttachmentId(),
                req.getProvinceCode(), req.getProvinceName(), req.getCityCode(), req.getCityName(),
                req.getDistrictCode(), req.getDistrictName(), req.getAddressDetail());
        return ApiResponse.ok();
    }

    // ----- buildings -----

    @GetMapping("/buildings")
    public ApiResponse<List<Building>> buildings() {
        return ApiResponse.ok(spaceService.listBuildings());
    }

    @PostMapping("/buildings")
    public ApiResponse<Building> createBuilding(@RequestBody NameReq req) {
        return ApiResponse.ok(spaceService.createBuilding(req.getName()));
    }

    @PutMapping("/buildings/{id}")
    public ApiResponse<Void> updateBuilding(@PathVariable Long id, @RequestBody NameReq req) {
        spaceService.updateBuilding(id, req.getName());
        return ApiResponse.ok();
    }

    @DeleteMapping("/buildings/{id}")
    public ApiResponse<Void> deleteBuilding(@PathVariable Long id,
                                            @RequestParam(defaultValue = "false") boolean releaseOccupants) {
        spaceService.deleteBuilding(id, releaseOccupants);
        return ApiResponse.ok();
    }

    @GetMapping("/buildings/{id}/occupants-summary")
    public ApiResponse<Map<String, Object>> buildingOccupants(@PathVariable Long id) {
        return ApiResponse.ok(spaceService.occupantsUnderBuilding(id));
    }

    // ----- units -----

    @GetMapping("/buildings/{buildingId}/units")
    public ApiResponse<List<Unit>> units(@PathVariable Long buildingId) {
        return ApiResponse.ok(spaceService.listUnits(buildingId));
    }

    @PostMapping("/buildings/{buildingId}/units")
    public ApiResponse<Unit> createUnit(@PathVariable Long buildingId, @RequestBody NameReq req) {
        return ApiResponse.ok(spaceService.createUnit(buildingId, req.getName()));
    }

    @PutMapping("/units/{id}")
    public ApiResponse<Void> updateUnit(@PathVariable Long id, @RequestBody NameReq req) {
        spaceService.updateUnit(id, req.getName());
        return ApiResponse.ok();
    }

    @DeleteMapping("/units/{id}")
    public ApiResponse<Void> deleteUnit(@PathVariable Long id,
                                        @RequestParam(defaultValue = "false") boolean releaseOccupants) {
        spaceService.deleteUnit(id, releaseOccupants);
        return ApiResponse.ok();
    }

    @GetMapping("/units/{id}/occupants-summary")
    public ApiResponse<Map<String, Object>> unitOccupants(@PathVariable Long id) {
        return ApiResponse.ok(spaceService.occupantsUnderUnit(id));
    }

    // ----- floors -----

    @GetMapping("/units/{unitId}/floors")
    public ApiResponse<List<Floor>> floors(@PathVariable Long unitId) {
        return ApiResponse.ok(spaceService.listFloors(unitId));
    }

    @PostMapping("/units/{unitId}/floors")
    public ApiResponse<Floor> createFloor(@PathVariable Long unitId, @RequestBody FloorReq req) {
        return ApiResponse.ok(spaceService.createFloor(unitId, req.getName(), req.getFloorNo()));
    }

    @PutMapping("/floors/{id}")
    public ApiResponse<Void> updateFloor(@PathVariable Long id, @RequestBody FloorReq req) {
        spaceService.updateFloor(id, req.getName(), req.getFloorNo());
        return ApiResponse.ok();
    }

    @DeleteMapping("/floors/{id}")
    public ApiResponse<Void> deleteFloor(@PathVariable Long id,
                                         @RequestParam(defaultValue = "false") boolean releaseOccupants) {
        spaceService.deleteFloor(id, releaseOccupants);
        return ApiResponse.ok();
    }

    @GetMapping("/floors/{id}/occupants-summary")
    public ApiResponse<Map<String, Object>> floorOccupants(@PathVariable Long id) {
        return ApiResponse.ok(spaceService.occupantsUnderFloor(id));
    }

    // ----- rooms -----

    @GetMapping("/floors/{floorId}/rooms")
    public ApiResponse<List<Room>> rooms(@PathVariable Long floorId) {
        return ApiResponse.ok(spaceService.listRooms(floorId));
    }

    @PostMapping("/floors/{floorId}/rooms")
    public ApiResponse<Room> createRoom(@PathVariable Long floorId, @RequestBody RoomReq req) {
        return ApiResponse.ok(spaceService.createRoom(floorId, req.getRoomNo(), req.getAreaSqm(),
                req.getHouseTypeId(), req.getStatus()));
    }

    @PutMapping("/rooms/{id}")
    public ApiResponse<Room> updateRoom(@PathVariable Long id, @RequestBody RoomReq req) {
        return ApiResponse.ok(spaceService.updateRoom(id, req.getRoomNo(), req.getAreaSqm(),
                req.getHouseTypeId(), req.getStatus()));
    }

    @DeleteMapping("/rooms/{id}")
    public ApiResponse<Void> deleteRoom(@PathVariable Long id,
                                        @RequestParam(defaultValue = "false") boolean releaseOccupants) {
        spaceService.deleteRoom(id, releaseOccupants);
        return ApiResponse.ok();
    }

    @GetMapping("/rooms/{id}/occupants")
    public ApiResponse<Map<String, Object>> roomOccupants(@PathVariable Long id) {
        return ApiResponse.ok(spaceService.listActiveOccupants(id));
    }

    @GetMapping("/space-tree")
    public ApiResponse<Map<String, Object>> spaceTree() {
        return ApiResponse.ok(spaceService.spaceTree());
    }

    // ----- house types -----

    @GetMapping("/house-types")
    public ApiResponse<List<CommunityHouseType>> houseTypes() {
        return ApiResponse.ok(spaceService.listHouseTypes());
    }

    @PostMapping("/house-types")
    public ApiResponse<CommunityHouseType> createHouseType(@RequestBody HouseTypeReq req) {
        return ApiResponse.ok(spaceService.createHouseType(req.getName(), req.getPropertyFeeUnitPrice(), req.getSortNo()));
    }

    @PutMapping("/house-types/{id}")
    public ApiResponse<Void> updateHouseType(@PathVariable Long id, @RequestBody HouseTypeReq req) {
        spaceService.updateHouseType(id, req.getName(), req.getPropertyFeeUnitPrice(), req.getSortNo());
        return ApiResponse.ok();
    }

    @PutMapping("/house-types/{id}/status")
    public ApiResponse<Void> houseTypeStatus(@PathVariable Long id, @RequestBody StatusReq req) {
        spaceService.updateHouseTypeStatus(id, req.getStatus());
        return ApiResponse.ok();
    }

    // ----- parking -----

    @GetMapping("/parking-spaces")
    public ApiResponse<List<ParkingSpace>> parkingSpaces(
            @RequestParam(required = false) String linked,
            @RequestParam(required = false) String spaceNo) {
        return ApiResponse.ok(parkingService.list(linked, spaceNo));
    }

    @GetMapping("/vehicles")
    public ApiResponse<Map<String, Object>> vehicles(
            @RequestParam(required = false) String plateNo,
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) String bound,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(parkingService.listVehicles(
                plateNo, roomId, bound, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/rooms/{roomId}/parking-options")
    public ApiResponse<List<Map<String, Object>>> roomParkingOptions(
            @PathVariable Long roomId,
            @RequestParam(defaultValue = "true") boolean availableOnly,
            @RequestParam(required = false) Long keepSpaceId) {
        return ApiResponse.ok(parkingService.roomParkingOptions(roomId, availableOnly, keepSpaceId));
    }

    @PostMapping("/parking-spaces")
    public ApiResponse<ParkingSpace> createParking(@RequestBody ParkingReq req) {
        return ApiResponse.ok(parkingService.create(req.getSpaceNo(), req.getRemark()));
    }

    @PutMapping("/parking-spaces/{id}")
    public ApiResponse<Void> updateParking(@PathVariable Long id, @RequestBody ParkingReq req) {
        parkingService.update(id, req.getSpaceNo(), req.getRemark());
        return ApiResponse.ok();
    }

    @PostMapping("/parking-spaces/{id}/link")
    public ApiResponse<Void> linkParking(@PathVariable Long id, @RequestBody RoomIdReq req) {
        parkingService.link(id, req.getRoomId());
        return ApiResponse.ok();
    }

    @PostMapping("/parking-spaces/{id}/unlink")
    public ApiResponse<Void> unlinkParking(@PathVariable Long id) {
        parkingService.unlink(id);
        return ApiResponse.ok();
    }

    @PostMapping("/parking-spaces/{id}/relink")
    public ApiResponse<Void> relinkParking(@PathVariable Long id, @RequestBody RoomIdReq req) {
        parkingService.relink(id, req.getRoomId());
        return ApiResponse.ok();
    }

    @DeleteMapping("/parking-spaces/{id}")
    public ApiResponse<Void> deleteParking(@PathVariable Long id) {
        parkingService.delete(id);
        return ApiResponse.ok();
    }

    // ----- committee -----

    @GetMapping("/committee-members")
    public ApiResponse<List<CommitteeMember>> committeeMembers() {
        return ApiResponse.ok(committeeService.list());
    }

    @PostMapping("/committee-members")
    public ApiResponse<CommitteeMember> createCommittee(@RequestBody CommitteeReq req) {
        return ApiResponse.ok(committeeService.create(req.getUserId(), req.getMobile(), req.getRealName(), req.getTitle()));
    }

    /** 住户栏任命业委会：仅本小区业主；title 空或 null 表示解除 */
    @PutMapping("/users/{userId}/committee")
    public ApiResponse<Map<String, Object>> assignCommittee(
            @PathVariable Long userId, @RequestBody CommitteeAssignReq req) {
        return ApiResponse.ok(committeeService.assignForOwner(userId, req.getTitle()));
    }

    @PutMapping("/committee-members/{id}")
    public ApiResponse<Void> updateCommittee(@PathVariable Long id, @RequestBody CommitteeUpdateReq req) {
        committeeService.update(id, req.getTitle(), req.getStatus());
        return ApiResponse.ok();
    }

    // ----- auth applications -----

    @GetMapping("/auth-applications")
    public ApiResponse<List<Map<String, Object>>> authApps(@RequestParam(required = false) String status) {
        return ApiResponse.ok(authApplicationService.staffListViews(status));
    }

    @GetMapping("/auth-applications/{id}")
    public ApiResponse<Map<String, Object>> authApp(@PathVariable Long id) {
        return ApiResponse.ok(authApplicationService.staffGetView(id));
    }

    @PostMapping("/auth-applications/{id}/approve")
    public ApiResponse<AuthApplication> approveAuth(@PathVariable Long id) {
        return ApiResponse.ok(authApplicationService.approve(id));
    }

    @PostMapping("/auth-applications/{id}/reject")
    public ApiResponse<AuthApplication> rejectAuth(@PathVariable Long id, @RequestBody RejectReq req) {
        return ApiResponse.ok(authApplicationService.reject(id, req.getRejectReason()));
    }

    // ----- room change applications -----

    @GetMapping("/room-change-applications")
    public ApiResponse<List<Map<String, Object>>> roomChanges(@RequestParam(required = false) String status) {
        return ApiResponse.ok(roomChangeService.staffListViews(status));
    }

    @GetMapping("/room-change-applications/{id}")
    public ApiResponse<Map<String, Object>> roomChange(@PathVariable Long id) {
        return ApiResponse.ok(roomChangeService.staffGetView(id));
    }

    @PostMapping("/room-change-applications/{id}/approve")
    public ApiResponse<RoomChangeApplication> approveChange(@PathVariable Long id) {
        return ApiResponse.ok(roomChangeService.approve(id));
    }

    @PostMapping("/room-change-applications/{id}/reject")
    public ApiResponse<RoomChangeApplication> rejectChange(@PathVariable Long id, @RequestBody RejectReq req) {
        return ApiResponse.ok(roomChangeService.reject(id, req.getRejectReason()));
    }

    // ----- import -----

    @GetMapping("/import/template")
    public ResponseEntity<Resource> importTemplate() {
        Resource file = importService.template();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"resident-import-template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @PostMapping("/import/batches")
    public ApiResponse<ImportBatch> importBatch(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(importService.importExcel(file));
    }

    @GetMapping("/import/batches")
    public ApiResponse<List<ImportBatch>> importBatches() {
        return ApiResponse.ok(importService.list());
    }

    @GetMapping("/import/batches/{id}")
    public ApiResponse<ImportBatch> importBatchDetail(@PathVariable Long id) {
        return ApiResponse.ok(importService.get(id));
    }

    // ----- fixes -----

    @GetMapping("/users/by-mobile")
    public ApiResponse<Map<String, Object>> userByMobile(@RequestParam String mobile) {
        return ApiResponse.ok(fixService.lookupByMobile(mobile));
    }

    /**
     * 为本小区住户/业委会/物业人员设置 App 临时密码（与 Web 密码字段共用）。
     * body.newPassword 可空，空则使用系统默认临时密码。
     */
    @PostMapping("/users/{userId}/reset-app-password")
    public ApiResponse<Map<String, Object>> resetAppPassword(
            @PathVariable Long userId, @RequestBody(required = false) AppPwdReq req) {
        return ApiResponse.ok(occupantQueryService.resetAppPassword(
                userId, req == null ? null : req.getNewPassword()));
    }

    @PostMapping("/fixes/occupants")
    public ApiResponse<Map<String, Object>> fixOccupants(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(fixService.fixOccupant(false, body));
    }

    @PostMapping("/fixes/vehicles")
    public ApiResponse<Map<String, Object>> fixVehicles(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(fixService.fixVehicle(false, body));
    }

    @PostMapping("/fixes/parking-links")
    public ApiResponse<Map<String, Object>> fixParkingLinks(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(fixService.fixParkingLink(false, body));
    }

    @Data
    public static class NameReq {
        @NotBlank
        private String name;
    }

    @Data
    public static class FloorReq {
        @NotBlank
        private String name;
        private Integer floorNo;
    }

    @Data
    public static class RoomReq {
        private String roomNo;
        private BigDecimal areaSqm;
        private Long houseTypeId;
        private Integer status;
    }

    @Data
    public static class HouseTypeReq {
        private String name;
        private BigDecimal propertyFeeUnitPrice;
        private Integer sortNo;
    }

    @Data
    public static class StatusReq {
        @NotNull
        private Integer status;
    }

    @Data
    public static class ParkingReq {
        private String spaceNo;
        private String remark;
    }

    @Data
    public static class RoomIdReq {
        @NotNull
        private Long roomId;
    }

    @Data
    public static class CommunityUpdateReq {
        private String name;
        private String address;
        private String intro;
        private String contactPhone;
        private Long coverAttachmentId;
        private String provinceCode;
        private String provinceName;
        private String cityCode;
        private String cityName;
        private String districtCode;
        private String districtName;
        private String addressDetail;
    }

    @Data
    public static class CommitteeReq {
        private Long userId;
        private String mobile;
        private String realName;
        @NotBlank
        private String title;
    }

    @Data
    public static class CommitteeAssignReq {
        /** DIRECTOR / MEMBER / ACTIVIST；空或 null 表示解除业委会 */
        private String title;
    }

    @Data
    public static class CommitteeUpdateReq {
        private String title;
        private String status;
    }

    @Data
    public static class RejectReq {
        @NotBlank
        private String rejectReason;
    }

    @Data
    public static class TeamAddReq {
        private String mobile;
        private String realName;
        private String tempPassword;
        private List<String> lineRoles;
    }

    @Data
    public static class TeamLineRolesReq {
        private List<String> lineRoles;
        /** 兼岗为客服岗且该账号无密码时，用它初始化 Web 登录密码 */
        private String webPassword;
    }

    @Data
    public static class TeamResetPwdReq {
        private String newPassword;
    }

    @Data
    public static class AppPwdReq {
        /** 可空：空则使用系统默认临时密码 */
        private String newPassword;
    }
}
