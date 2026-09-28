package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.service.AboutUsService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AboutUsController {

    private final AboutUsService aboutUsService;

    /** 小程序「关于我们」只读 */
    @GetMapping("/about-us")
    public ApiResponse<Map<String, Object>> getPublic() {
        return ApiResponse.ok(aboutUsService.getPublic());
    }

    @GetMapping("/platform/about-us")
    public ApiResponse<Map<String, Object>> getPlatform() {
        return ApiResponse.ok(aboutUsService.getPublic());
    }

    @PutMapping("/platform/about-us")
    public ApiResponse<Map<String, Object>> update(@RequestBody AboutUsReq req) {
        return ApiResponse.ok(aboutUsService.update(req.getTitle(), req.getContent(), req.getAttachmentIds()));
    }

    @Data
    public static class AboutUsReq {
        private String title;
        private String content;
        private List<Long> attachmentIds;
    }
}
