package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.Attachment;
import com.property.mgmt.mapper.AttachmentMapper;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentService {

    private static final Set<String> ALLOWED = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "pdf", "doc", "docx", "xls", "xlsx", "zip");
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 公告正文可含多图/附件，其它业务仍限 3 个 */
    private static final int DEFAULT_BIZ_LIMIT = 3;
    private static final int NOTICE_BIZ_LIMIT = 50;

    private final AttachmentMapper attachmentMapper;

    @Value("${app.upload.dir:../uploads}")
    private String uploadDir;

    @Transactional
    public Attachment upload(MultipartFile file, String bizType, Long bizId, Long communityId) {
        AuthUser user = AuthContext.require();
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "文件不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "单文件不能超过 10MB");
        }
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extension(original);
        if (!ALLOWED.contains(ext)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅允许图片/PDF/Office/ZIP");
        }
        String type = (bizType == null || bizType.isBlank()) ? "TEMP" : bizType.trim().toUpperCase(Locale.ROOT);
        long bindId = bizId == null ? 0L : bizId;
        if (!"TEMP".equals(type) && bindId > 0) {
            int limit = richBizLimit(type);
            long cnt = attachmentMapper.selectCount(new LambdaQueryWrapper<Attachment>()
                    .eq(Attachment::getBizType, type)
                    .eq(Attachment::getBizId, bindId));
            if (cnt >= limit) {
                throw BizException.of(ErrorCodes.ATTACHMENT_LIMIT, "同一业务单最多 " + limit + " 个附件");
            }
        }

        String key = LocalDateTime.now().toLocalDate() + "/" + UUID.randomUUID() + "." + ext;
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir.resolve(key).getParent());
            file.transferTo(dir.resolve(key));
        } catch (IOException e) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "文件保存失败");
        }

        LocalDateTime now = LocalDateTime.now();
        Attachment a = new Attachment();
        a.setCommunityId(communityId);
        a.setBizType(type);
        a.setBizId(bindId);
        a.setFileName(original);
        a.setContentType(file.getContentType());
        a.setSizeBytes(file.getSize());
        a.setStorageKey(key);
        a.setUrl("/api/v1/attachments/" + "pending");
        a.setUploadedBy(user.getUserId());
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        attachmentMapper.insert(a);
        a.setUrl("/api/v1/attachments/" + a.getId());
        attachmentMapper.updateById(a);
        return a;
    }

    public Attachment get(Long id) {
        Attachment a = attachmentMapper.selectById(id);
        if (a == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "附件不存在");
        }
        return a;
    }

    public Resource loadFile(Long id) {
        Attachment a = get(id);
        Path path = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(a.getStorageKey());
        if (!Files.exists(path)) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "文件不存在");
        }
        return new FileSystemResource(path);
    }

    @Transactional
    public void delete(Long id) {
        AuthUser user = AuthContext.require();
        Attachment a = get(id);
        boolean staffOrPlatform = "STAFF".equals(user.getIdentityType())
                || "PLATFORM".equals(user.getIdentityType())
                || user.isPlatformAdmin();
        if (!staffOrPlatform && !user.getUserId().equals(a.getUploadedBy())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "无权删除");
        }
        if ("ROOM_ARCHIVE".equals(a.getBizType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "归档附件不可删除");
        }
        attachmentMapper.deleteById(id);
    }

    @Transactional
    public void bindToBiz(List<Long> attachmentIds, String bizType, Long bizId, Long communityId, Long uploaderId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        int limit = richBizLimit(bizType);
        if (attachmentIds.size() > limit) {
            throw BizException.of(ErrorCodes.ATTACHMENT_LIMIT, "同一业务单最多 " + limit + " 个附件");
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long id : attachmentIds) {
            Attachment a = get(id);
            if (!uploaderId.equals(a.getUploadedBy())
                    && !"TEMP".equals(a.getBizType())
                    && !(bizType.equals(a.getBizType()) && bizId.equals(a.getBizId()))) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "附件不属于当前用户");
            }
            a.setBizType(bizType);
            a.setBizId(bizId);
            if (communityId != null) {
                a.setCommunityId(communityId);
            }
            a.setUpdatedAt(now);
            attachmentMapper.updateById(a);
        }
    }

    @Transactional
    public void rebindRoomArchive(Long authAppId, Long roomId, Long communityId) {
        List<Attachment> list = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getBizType, "AUTH_APPLY")
                .eq(Attachment::getBizId, authAppId));
        LocalDateTime now = LocalDateTime.now();
        for (Attachment a : list) {
            a.setBizType("ROOM_ARCHIVE");
            a.setBizId(roomId);
            a.setCommunityId(communityId);
            a.setUpdatedAt(now);
            attachmentMapper.updateById(a);
        }
    }

    public List<Attachment> listByBiz(String bizType, Long bizId) {
        return attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getBizType, bizType)
                .eq(Attachment::getBizId, bizId)
                .orderByAsc(Attachment::getId));
    }

    private static int richBizLimit(String bizType) {
        if ("NOTICE".equals(bizType) || "ABOUT".equals(bizType) || "INSPECT_PHOTO".equals(bizType)) {
            return NOTICE_BIZ_LIMIT;
        }
        return DEFAULT_BIZ_LIMIT;
    }

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0) {
            return "";
        }
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
