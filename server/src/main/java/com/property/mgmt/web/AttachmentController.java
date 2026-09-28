package com.property.mgmt.web;

import com.property.mgmt.common.ApiResponse;
import com.property.mgmt.domain.Attachment;
import com.property.mgmt.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @PostMapping("/upload")
    public ApiResponse<Attachment> upload(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "bizType", required = false) String bizType,
                                          @RequestParam(value = "bizId", required = false) Long bizId,
                                          @RequestParam(value = "communityId", required = false) Long communityId) {
        return ApiResponse.ok(attachmentService.upload(file, bizType, bizId, communityId));
    }

    /** 按业务单查询已绑定附件（需登录）。返回轻量列表，图片预览由调用方拼接 ?token= 下载地址。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listByBiz(@RequestParam("bizType") String bizType,
                                                           @RequestParam("bizId") Long bizId) {
        List<Map<String, Object>> out = attachmentService.listByBiz(bizType, bizId).stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("fileName", a.getFileName());
            m.put("contentType", a.getContentType());
            m.put("sizeBytes", a.getSizeBytes());
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.ok(out);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Attachment meta = attachmentService.get(id);
        Resource file = attachmentService.loadFile(id);
        MediaType media = MediaType.APPLICATION_OCTET_STREAM;
        if (meta.getContentType() != null) {
            try {
                media = MediaType.parseMediaType(meta.getContentType());
            } catch (Exception ignored) {
            }
        }
        String raw = meta.getFileName() == null ? "file" : meta.getFileName();
        String ascii = raw.codePoints()
                .filter(cp -> cp >= 0x20 && cp <= 0x7E)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        if (ascii.isEmpty()) ascii = "file";
        String encoded;
        try {
            encoded = URLEncoder.encode(raw, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            encoded = ascii;
        }
        String cd = "inline; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd)
                .contentType(media)
                .body(file);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        attachmentService.delete(id);
        return ApiResponse.ok();
    }
}
