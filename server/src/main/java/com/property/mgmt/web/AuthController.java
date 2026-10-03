package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.service.AuthService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/platform/login")
    public ApiResponse<Map<String, Object>> platformLogin(@RequestBody LoginReq req) {
        return ApiResponse.ok(authService.platformLogin(req.getUsername(), req.getPassword()));
    }

    @PostMapping("/staff/login")
    public ApiResponse<Map<String, Object>> staffLogin(@RequestBody StaffLoginReq req) {
        return ApiResponse.ok(authService.staffLogin(req.getMobile(), req.getPassword()));
    }

    @PostMapping("/staff/change-password")
    public ApiResponse<Map<String, Object>> changeStaffPassword(@RequestBody ChangePwdReq req) {
        return ApiResponse.ok(authService.changeStaffPassword(req.getOldPassword(), req.getNewPassword()));
    }

    /** App（APK）手机号+密码登录：住户/业委会/物业（含一线岗） */
    @PostMapping("/app/login")
    public ApiResponse<Map<String, Object>> appLogin(@RequestBody StaffLoginReq req) {
        return ApiResponse.ok(authService.appLogin(req.getMobile(), req.getPassword()));
    }

    /** App 游客自助注册（手机号 + 密码） */
    @PostMapping("/app/register")
    public ApiResponse<Map<String, Object>> appRegister(@RequestBody StaffLoginReq req) {
        return ApiResponse.ok(authService.appRegister(req.getMobile(), req.getPassword()));
    }

    /** 全库查手机号是否已被占用（住户/物业/业委/游客） */
    @GetMapping("/app/mobile-taken")
    public ApiResponse<Map<String, Object>> mobileTaken(@RequestParam String mobile) {
        return ApiResponse.ok(authService.isMobileTaken(mobile));
    }

    @PostMapping("/app/change-password")
    public ApiResponse<Map<String, Object>> changeAppPassword(@RequestBody ChangePwdReq req) {
        return ApiResponse.ok(authService.changeAppPassword(req.getOldPassword(), req.getNewPassword()));
    }

    @PostMapping("/miniapp/code2session")
    public ApiResponse<Map<String, Object>> code2session(@RequestBody CodeReq req) {
        return ApiResponse.ok(authService.code2session(req.getCode()));
    }

    @PostMapping("/miniapp/phone-match")
    public ApiResponse<Map<String, Object>> phoneMatch(@RequestBody PhoneMatchReq req) {
        return ApiResponse.ok(authService.phoneMatch(req.getSessionToken(), req.getPhoneCode()));
    }

    /**
     * @deprecated 方案 2：小程序物业/业委会免密，仅需微信+手机号匹配；保留接口兼容旧客户端。
     */
    @Deprecated
    @PostMapping("/miniapp/staff/bind")
    public ApiResponse<Map<String, Object>> staffBind(@RequestBody StaffLoginReq req) {
        return ApiResponse.ok(authService.staffBind(AuthContext.require().getUserId(), req.getMobile(), req.getPassword()));
    }

    @GetMapping("/identities")
    public ApiResponse<Map<String, Object>> identities() {
        List<Map<String, Object>> list = authService.listIdentities(AuthContext.require().getUserId());
        Map<String, Object> profile = authService.currentUserBrief();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("identities", list);
        out.put("current", authService.currentContext());
        // user / profile 同义：避免部分端对 user 字段解析异常
        out.put("user", profile);
        out.put("profile", profile);
        return ApiResponse.ok(out);
    }

    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(@RequestBody ProfileReq req) {
        return ApiResponse.ok(authService.updateProfile(req.getRealName()));
    }

    @PostMapping("/context/switch")
    public ApiResponse<Map<String, Object>> switchContext(@RequestBody SwitchReq req) {
        return ApiResponse.ok(authService.switchContext(
                AuthContext.require().getUserId(),
                req.getIdentityType(),
                req.getCommunityId(),
                req.getRoomId()));
    }

    @Data
    public static class LoginReq {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }

    @Data
    public static class StaffLoginReq {
        @NotBlank
        private String mobile;
        @NotBlank
        private String password;
    }

    @Data
    public static class ChangePwdReq {
        @NotBlank
        private String oldPassword;
        @NotBlank
        private String newPassword;
    }

    @Data
    public static class CodeReq {
        @NotBlank
        private String code;
    }

    @Data
    public static class PhoneMatchReq {
        private String sessionToken;
        @NotBlank
        private String phoneCode;
    }

    @Data
    public static class SwitchReq {
        @NotBlank
        private String identityType;
        private Long communityId;
        private Long roomId;
    }

    @Data
    public static class ProfileReq {
        @NotBlank
        private String realName;
    }
}
