package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.AboutUs;
import com.property.mgmt.domain.Attachment;
import com.property.mgmt.mapper.AboutUsMapper;
import com.property.mgmt.mapper.AttachmentMapper;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AboutUsService {

    public static final long SINGLETON_ID = 1L;
    public static final String BIZ_TYPE = "ABOUT";

    private static final Pattern FILE_MARKER = Pattern.compile("\\{\\{FILE:(\\d+):([\\s\\S]*?)\\}\\}");

    private final AboutUsMapper aboutUsMapper;
    private final AttachmentMapper attachmentMapper;
    private final AttachmentService attachmentService;

    @Transactional
    public Map<String, Object> getPublic() {
        AboutUs row = ensureRow();
        repairOrphanAttachments(row);
        return toView(row);
    }

    @Transactional
    public Map<String, Object> update(String title, String content, List<Long> attachmentIds) {
        requirePlatform();
        AboutUs row = ensureRow();
        if (StringUtils.hasText(title)) {
            row.setTitle(title.trim());
        }
        String html = content == null ? "" : content;
        row.setContent(html);
        row.setUpdatedAt(LocalDateTime.now());
        aboutUsMapper.updateById(row);
        List<Long> ids = attachmentIds == null ? new ArrayList<>() : new ArrayList<>(attachmentIds);
        for (Long id : extractFileIds(html)) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        if (!ids.isEmpty()) {
            attachmentService.bindToBiz(ids, BIZ_TYPE, SINGLETON_ID, null, AuthContext.require().getUserId());
        }
        repairOrphanAttachments(row);
        return toView(row);
    }

    private AboutUs ensureRow() {
        AboutUs row = aboutUsMapper.selectById(SINGLETON_ID);
        if (row != null) {
            return row;
        }
        row = new AboutUs();
        row.setId(SINGLETON_ID);
        row.setTitle("关于我们");
        row.setContent("");
        row.setUpdatedAt(LocalDateTime.now());
        aboutUsMapper.insert(row);
        return row;
    }

    /** 把误存的 ABOUT/bizId=0 附件挂回本页，并把正文里的纯文件名补成 {{FILE:}} */
    private void repairOrphanAttachments(AboutUs row) {
        LocalDateTime now = LocalDateTime.now();
        List<Attachment> orphans = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getBizType, BIZ_TYPE)
                .and(w -> w.isNull(Attachment::getBizId).or().eq(Attachment::getBizId, 0L)));
        for (Attachment a : orphans) {
            a.setBizId(SINGLETON_ID);
            a.setUpdatedAt(now);
            attachmentMapper.updateById(a);
        }

        List<Attachment> files = attachmentService.listByBiz(BIZ_TYPE, SINGLETON_ID);
        String content = row.getContent() == null ? "" : row.getContent();
        String repaired = injectFileMarkers(content, files);
        if (!repaired.equals(content)) {
            row.setContent(repaired);
            row.setUpdatedAt(now);
            aboutUsMapper.updateById(row);
        }
    }

    private static String injectFileMarkers(String content, List<Attachment> files) {
        String html = content == null ? "" : content;
        // 同名非图附件只保留最新一条写入正文
        Map<String, Attachment> latestByName = new LinkedHashMap<>();
        for (Attachment a : files) {
            if (a == null || a.getId() == null || isImage(a)) {
                continue;
            }
            String name = StringUtils.hasText(a.getFileName()) ? a.getFileName().trim() : ("附件" + a.getId());
            latestByName.put(name, a);
        }
        for (Attachment a : latestByName.values()) {
            Long id = a.getId();
            String name = a.getFileName().trim();
            if (html.contains("attachment://" + id) || html.contains("{{FILE:" + id + ":")) {
                continue;
            }
            String marker = "{{FILE:" + id + ":" + name.replace("}}", "") + "}}";
            Pattern p = Pattern.compile("<p>\\s*" + Pattern.quote(name) + "\\s*</p>", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            if (m.find()) {
                html = m.replaceAll(Matcher.quoteReplacement("<p>" + marker + "</p>"));
                // 只留第一处标记，其余同名段落删掉
                int first = html.indexOf(marker);
                if (first >= 0) {
                    String head = html.substring(0, first + marker.length());
                    String tail = html.substring(first + marker.length()).replace(marker, "");
                    tail = Pattern.compile("<p>\\s*" + Pattern.quote(name) + "\\s*</p>", Pattern.CASE_INSENSITIVE)
                            .matcher(tail).replaceAll("");
                    html = head + tail;
                }
            } else if (html.contains(name) && !html.contains(marker)) {
                html = html.replaceFirst(Pattern.quote(name), Matcher.quoteReplacement(marker));
            } else {
                html = html + "<p>" + marker + "</p>";
            }
        }
        return html;
    }

    private static boolean isImage(Attachment a) {
        String ct = a.getContentType() == null ? "" : a.getContentType().toLowerCase(Locale.ROOT);
        if (ct.startsWith("image/")) {
            return true;
        }
        String name = a.getFileName() == null ? "" : a.getFileName().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp");
    }

    private static List<Long> extractFileIds(String html) {
        List<Long> ids = new ArrayList<>();
        if (!StringUtils.hasText(html)) {
            return ids;
        }
        Matcher m = FILE_MARKER.matcher(html);
        while (m.find()) {
            try {
                long id = Long.parseLong(m.group(1));
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher a = Pattern.compile("attachment://(\\d+)", Pattern.CASE_INSENSITIVE).matcher(html);
        while (a.find()) {
            try {
                long id = Long.parseLong(a.group(1));
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    private Map<String, Object> toView(AboutUs row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("title", row.getTitle());
        m.put("content", row.getContent() == null ? "" : row.getContent());
        m.put("updatedAt", row.getUpdatedAt());
        List<Map<String, Object>> attachments = new ArrayList<>();
        Map<String, Attachment> latestFiles = new LinkedHashMap<>();
        for (Attachment a : attachmentService.listByBiz(BIZ_TYPE, SINGLETON_ID)) {
            if (a == null || a.getId() == null || isImage(a)) {
                continue;
            }
            String name = StringUtils.hasText(a.getFileName()) ? a.getFileName().trim() : ("附件" + a.getId());
            latestFiles.put(name, a);
        }
        for (Attachment a : latestFiles.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("name", a.getFileName());
            item.put("fileName", a.getFileName());
            item.put("contentType", a.getContentType());
            item.put("url", "/api/v1/attachments/" + a.getId());
            attachments.add(item);
        }
        m.put("attachments", attachments);
        return m;
    }

    private void requirePlatform() {
        AuthUser u = AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台管理员权限");
        }
    }
}
