package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.service.OccupantQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OccupantController {

    private final OccupantQueryService occupantQueryService;

    @GetMapping("/staff/occupants")
    public ApiResponse<Map<String, Object>> staffOccupants(
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) Long unitId,
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) String roomNo,
            @RequestParam(required = false) String mobile,
            @RequestParam(required = false) String realName,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(occupantQueryService.staffList(
                buildingId, unitId, roomId, roomNo, mobile, realName, role, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/committee/occupants")
    public ApiResponse<Map<String, Object>> committeeOccupants(
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) Long unitId,
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) String roomNo,
            @RequestParam(required = false) String mobile,
            @RequestParam(required = false) String realName,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(occupantQueryService.committeeList(
                buildingId, unitId, roomId, roomNo, mobile, realName, role, page, Math.min(pageSize, 100)));
    }

    @GetMapping("/platform/occupants")
    public ApiResponse<Map<String, Object>> platformOccupants(
            @RequestParam(required = false) Long communityId,
            @RequestParam(required = false) String mobile,
            @RequestParam(required = false) String roomNo,
            @RequestParam(required = false) String realName,
            @RequestParam(required = false) String idCardNo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(occupantQueryService.platformList(
                communityId, mobile, roomNo, realName, idCardNo, page, Math.min(pageSize, 100)));
    }

    /** 平台批量解除住户绑定（置 INACTIVE）。body: { occupantIds: number[] } */
    @PostMapping("/platform/occupants/batch-deactivate")
    public ApiResponse<Map<String, Object>> platformBatchDeactivate(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(occupantQueryService.platformBatchDeactivate(body));
    }
}
