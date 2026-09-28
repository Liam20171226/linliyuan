package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.Notice;
import com.property.mgmt.domain.RoomOccupant;
import com.property.mgmt.mapper.NoticeMapper;
import com.property.mgmt.mapper.RoomOccupantMapper;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.CommitteeGuard;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class NoticeService {

    private static final Set<String> VIS = Set.of("PUBLIC_ALL", "RESIDENTS", "OWNERS");

    /** 公告列表统一排序：Banner 位（第 1~5 个）优先并按位置升序，其余按发布时间由近到远 */
    private static final String NOTICE_ORDER_SQL =
            "ORDER BY CASE WHEN show_on_banner = 1 AND banner_order IS NOT NULL THEN 0 ELSE 1 END, "
                    + "banner_order ASC, created_at DESC, id DESC";

    private final NoticeMapper noticeMapper;
    private final RoomOccupantMapper roomOccupantMapper;
    private final AttachmentService attachmentService;

    @Transactional
    public Notice create(String title, String content, String noticeType, Integer urgency,
                         String visibility, LocalDateTime effectiveAt, LocalDateTime expireAt,
                         List<Long> attachmentIds, Long coverAttachmentId, Boolean showOnBanner,
                         Integer bannerOrder, String creatorIdentity) {
        AuthUser u = AuthContext.require();
        Long communityId;
        if ("STAFF".equals(creatorIdentity)) {
            communityId = StaffGuard.communityId();
        } else if ("COMMITTEE".equals(creatorIdentity)) {
            communityId = CommitteeGuard.communityId();
        } else {
            throw BizException.of(ErrorCodes.FORBIDDEN, "无权发公告");
        }
        validate(title, content, noticeType, visibility);
        // 业委通知：不上首页 Banner、不设封面（仅物业公告使用）
        boolean committee = "COMMITTEE".equals(creatorIdentity);
        Integer order = null;
        boolean banner = false;
        Long coverId = null;
        if (!committee) {
            order = bannerOrder != null && bannerOrder > 0 ? normalizeBannerOrder(bannerOrder) : null;
            banner = order != null || Boolean.TRUE.equals(showOnBanner);
            coverId = coverAttachmentId != null && coverAttachmentId > 0 ? coverAttachmentId : null;
            if (banner && coverId == null) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "上首页 Banner 须先上传封面图");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        Notice n = new Notice();
        n.setCommunityId(communityId);
        n.setTitle(title.trim());
        n.setContent(content);
        n.setNoticeType(noticeType);
        n.setUrgency(urgency == null ? 0 : urgency);
        n.setVisibility(visibility);
        n.setEffectiveAt(effectiveAt);
        n.setExpireAt(expireAt);
        n.setCoverAttachmentId(coverId);
        n.setShowOnBanner(banner ? 1 : 0);
        n.setBannerOrder(order);
        n.setCreatedBy(u.getUserId());
        n.setCreatorIdentity(creatorIdentity);
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        noticeMapper.insert(n);
        // 同一 Banner 位置只保留最新一条：旧的自动下架
        if (!committee) {
            demoteConflictingBanners(communityId, order, n.getId());
        }
        attachmentService.bindToBiz(attachmentIds, "NOTICE", n.getId(), communityId, u.getUserId());
        return n;
    }

    @Transactional
    public Notice update(Long id, String title, String content, String noticeType, Integer urgency,
                         String visibility, LocalDateTime effectiveAt, LocalDateTime expireAt,
                         List<Long> attachmentIds, Long coverAttachmentId, Boolean showOnBanner,
                         Integer bannerOrder) {
        Notice n = requireOwned(id);
        if (title != null && !title.isBlank()) {
            n.setTitle(title.trim());
        }
        if (content != null) {
            n.setContent(content);
        }
        if (noticeType != null) {
            n.setNoticeType(noticeType);
        }
        if (urgency != null) {
            n.setUrgency(urgency);
        }
        if (visibility != null) {
            if (!VIS.contains(visibility)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "visibility 非法");
            }
            n.setVisibility(visibility);
        }
        if (effectiveAt != null) {
            n.setEffectiveAt(effectiveAt);
        }
        if (expireAt != null) {
            n.setExpireAt(expireAt);
        }
        boolean committeeNotice = "COMMITTEE".equals(n.getCreatorIdentity());
        if (committeeNotice) {
            // 业委通知不允许封面 / Banner
            n.setCoverAttachmentId(null);
            n.setShowOnBanner(0);
            n.setBannerOrder(null);
        } else {
            if (coverAttachmentId != null) {
                n.setCoverAttachmentId(coverAttachmentId > 0 ? coverAttachmentId : null);
            }
            if (showOnBanner != null) {
                n.setShowOnBanner(Boolean.TRUE.equals(showOnBanner) ? 1 : 0);
            }
            if (bannerOrder != null) {
                // 传 0 或负数表示取消 Banner；1~5 为顺序位
                Integer order = bannerOrder > 0 ? normalizeBannerOrder(bannerOrder) : null;
                n.setBannerOrder(order);
                n.setShowOnBanner(order != null ? 1 : 0);
            }
        }
        boolean wantBanner = !committeeNotice && n.getShowOnBanner() != null && n.getShowOnBanner() == 1;
        if (wantBanner && n.getCoverAttachmentId() == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "上首页 Banner 须先上传封面图");
        }
        n.setUpdatedAt(LocalDateTime.now());
        noticeMapper.updateById(n);
        // 取消 Banner / 业委通知：显式清 null 字段（updateById 默认不写 null）
        if (!wantBanner) {
            noticeMapper.update(null, new LambdaUpdateWrapper<Notice>()
                    .eq(Notice::getId, n.getId())
                    .set(Notice::getShowOnBanner, 0)
                    .set(Notice::getBannerOrder, null)
                    .set(committeeNotice, Notice::getCoverAttachmentId, null));
            n.setShowOnBanner(0);
            n.setBannerOrder(null);
            if (committeeNotice) {
                n.setCoverAttachmentId(null);
            }
        } else if (n.getBannerOrder() != null) {
            demoteConflictingBanners(n.getCommunityId(), n.getBannerOrder(), n.getId());
        }
        if (attachmentIds != null) {
            attachmentService.bindToBiz(attachmentIds, "NOTICE", n.getId(), n.getCommunityId(),
                    AuthContext.require().getUserId());
        }
        return n;
    }

    @Transactional
    public void softDelete(Long id, boolean platform) {
        Notice n = noticeMapper.selectById(id);
        if (n == null || n.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "公告不存在");
        }
        AuthUser u = AuthContext.require();
        if (platform) {
            if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台身份");
            }
        } else {
            if (!u.getUserId().equals(n.getCreatedBy())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "仅创建人可删");
            }
            if ("STAFF".equals(n.getCreatorIdentity())) {
                StaffGuard.requireStaff();
            } else if ("COMMITTEE".equals(n.getCreatorIdentity())) {
                CommitteeGuard.requireCommittee();
            }
        }
        n.setDeletedAt(LocalDateTime.now());
        n.setUpdatedAt(LocalDateTime.now());
        noticeMapper.updateById(n);
    }

    public Map<String, Object> listVisible(Long communityId, int page, int pageSize) {
        return listVisible(communityId, null, page, pageSize);
    }

    /**
     * @param creatorIdentity 可选：STAFF=物业公告 / COMMITTEE=业委通知；空则全部可见来源
     */
    public Map<String, Object> listVisible(Long communityId, String creatorIdentity, int page, int pageSize) {
        AuthUser u = AuthContext.require();
        Long cid = communityId != null ? communityId : u.getCommunityId();
        if (cid == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "communityId 必填");
        }
        LambdaQueryWrapper<Notice> q = baseVisibleQuery(cid, viewerFor(u, cid));
        if ("STAFF".equals(creatorIdentity) || "COMMITTEE".equals(creatorIdentity)) {
            q.eq(Notice::getCreatorIdentity, creatorIdentity);
        }
        q.last(NOTICE_ORDER_SQL);
        Page<Notice> p = noticeMapper.selectPage(new Page<>(page, pageSize), q);
        return pageOfNotices(p, page, pageSize);
    }

    /** 业委会管理：本小区全部业委通知（含他人发布，只读列表；改删仍走 requireOwned） */
    public Map<String, Object> listCommitteeAll(int page, int pageSize) {
        Long cid = CommitteeGuard.communityId();
        Page<Notice> p = noticeMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<Notice>()
                        .eq(Notice::getCommunityId, cid)
                        .eq(Notice::getCreatorIdentity, "COMMITTEE")
                        .isNull(Notice::getDeletedAt)
                        .last(NOTICE_ORDER_SQL));
        return pageOfNotices(p, page, pageSize);
    }

    /**
     * 同一小区同一 Banner 位置只允许一条公告：占用该位置后，同位置的其他公告（旧的）自动下架
     * —— 下架 = show_on_banner 置 0 且清空 banner_order，公告本身保留。
     */
    private void demoteConflictingBanners(Long communityId, Integer order, Long selfId) {
        if (communityId == null || order == null) {
            return;
        }
        LambdaQueryWrapper<Notice> q = new LambdaQueryWrapper<Notice>()
                .eq(Notice::getCommunityId, communityId)
                .eq(Notice::getShowOnBanner, 1)
                .eq(Notice::getBannerOrder, order)
                .isNull(Notice::getDeletedAt);
        if (selfId != null) {
            q.ne(Notice::getId, selfId);
        }
        List<Notice> conflicts = noticeMapper.selectList(q);
        for (Notice c : conflicts) {
            // 注意：不能用 updateById 置 null（MyBatis-Plus 默认跳过 null 字段，banner_order 清不掉）
            noticeMapper.update(null, new LambdaUpdateWrapper<Notice>()
                    .eq(Notice::getId, c.getId())
                    .set(Notice::getShowOnBanner, 0)
                    .set(Notice::getBannerOrder, null)
                    .set(Notice::getUpdatedAt, LocalDateTime.now()));
        }
    }

    /** Banner 顺序：1~5，其余非法 */
    private static Integer normalizeBannerOrder(Integer v) {
        if (v == null) return null;
        if (v < 1 || v > 5) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "Banner 顺序须为 1~5");
        }
        return v;
    }

    /** 首页：Banner（开关+封面，最多 5，按 banner_order 升序）+ 最新公告 3 条（按时间） */
    public Map<String, Object> homeFeed(Long communityId) {
        AuthUser u = AuthContext.require();
        Long cid = communityId != null ? communityId : u.getCommunityId();
        if (cid == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "communityId 必填");
        }
        AuthUser viewer = viewerFor(u, cid);
        List<Notice> raw = noticeMapper.selectList(baseVisibleQuery(cid, viewer)
                .eq(Notice::getShowOnBanner, 1)
                .isNotNull(Notice::getCoverAttachmentId)
                // 先按 banner_order 升序（未指定顺序的排在后面），再按时间倒序
                .last("ORDER BY CASE WHEN banner_order IS NULL THEN 1 ELSE 0 END, banner_order ASC, created_at DESC, id DESC LIMIT 10"));
        // 保险：同一位置若仍有多条（历史脏数据/并发），只保留最新的一条
        Set<Integer> usedOrder = new HashSet<>();
        List<Notice> banners = new ArrayList<>();
        for (Notice n : raw) {
            Integer o = n.getBannerOrder();
            if (o != null && !usedOrder.add(o)) {
                continue;
            }
            banners.add(n);
            if (banners.size() >= 5) {
                break;
            }
        }
        // 首页「物业公告」仅物业发布；业委通知走常用服务独立入口
        List<Notice> latest = noticeMapper.selectList(baseVisibleQuery(cid, viewer)
                .eq(Notice::getCreatorIdentity, "STAFF")
                .orderByDesc(Notice::getCreatedAt)
                .orderByDesc(Notice::getId)
                .last("LIMIT 3"));
        // Banner 也仅物业，避免与业委通知混淆
        List<Notice> staffBanners = banners.stream()
                .filter(n -> "STAFF".equals(n.getCreatorIdentity()) || n.getCreatorIdentity() == null)
                .limit(5)
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("banners", staffBanners.stream().map(NoticeService::toBrief).toList());
        data.put("latest", latest.stream().map(NoticeService::toBrief).toList());
        return data;
    }

    /** 跨小区浏览只给公开公告，避免把本小区住户/物业权限带到别的小区。 */
    private AuthUser viewerFor(AuthUser u, Long cid) {
        if (u == null) {
            return null;
        }
        if (u.isPlatformAdmin() || "PLATFORM".equals(u.getIdentityType())) {
            return u;
        }
        if (cid != null && cid.equals(u.getCommunityId())) {
            return u;
        }
        return AuthUser.builder()
                .userId(u.getUserId())
                .identityType("GUEST")
                .communityId(cid)
                .build();
    }

    private LambdaQueryWrapper<Notice> baseVisibleQuery(Long cid, AuthUser u) {
        LambdaQueryWrapper<Notice> q = new LambdaQueryWrapper<Notice>()
                .eq(Notice::getCommunityId, cid)
                .isNull(Notice::getDeletedAt);

        String identity = u.getIdentityType();
        if (identity == null || "GUEST".equals(identity)) {
            q.eq(Notice::getVisibility, "PUBLIC_ALL");
        } else if ("RESIDENT".equals(identity)) {
            boolean owner = isOwner(u);
            if (owner) {
                q.in(Notice::getVisibility, List.of("PUBLIC_ALL", "RESIDENTS", "OWNERS"));
            } else {
                q.in(Notice::getVisibility, List.of("PUBLIC_ALL", "RESIDENTS"));
            }
        } else if ("COMMITTEE".equals(identity) || "STAFF".equals(identity) || "PLATFORM".equals(identity)) {
            // all visibilities
        } else {
            q.eq(Notice::getVisibility, "PUBLIC_ALL");
        }

        LocalDateTime now = LocalDateTime.now();
        q.and(w -> w.isNull(Notice::getEffectiveAt).or().le(Notice::getEffectiveAt, now));
        q.and(w -> w.isNull(Notice::getExpireAt).or().ge(Notice::getExpireAt, now));
        return q;
    }

    public Notice getVisible(Long id) {
        Notice n = noticeMapper.selectById(id);
        if (n == null || n.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "公告不存在");
        }
        // reuse list visibility rules via lightweight check
        AuthUser u = AuthContext.require();
        String vis = n.getVisibility();
        String identity = u.getIdentityType();
        if ("PUBLIC_ALL".equals(vis)) {
            return n;
        }
        boolean platform = u.isPlatformAdmin() || "PLATFORM".equals(identity);
        boolean sameCommunity = n.getCommunityId() != null && n.getCommunityId().equals(u.getCommunityId());
        if (!platform && !sameCommunity) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "无权查看");
        }
        if ("GUEST".equals(identity) || identity == null) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "无权查看");
        }
        if ("RESIDENTS".equals(vis) && !"RESIDENT".equals(identity)
                && !"STAFF".equals(identity) && !"COMMITTEE".equals(identity)
                && !"PLATFORM".equals(identity)) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "无权查看");
        }
        if ("OWNERS".equals(vis)) {
            if ("STAFF".equals(identity) || "COMMITTEE".equals(identity) || "PLATFORM".equals(identity)) {
                return n;
            }
            if (!"RESIDENT".equals(identity) || !isOwner(u)) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "仅业主可见");
            }
        }
        return n;
    }

    public Map<String, Object> listMine(String creatorIdentity, int page, int pageSize) {
        AuthUser u = AuthContext.require();
        Long cid;
        if ("STAFF".equals(creatorIdentity)) {
            cid = StaffGuard.communityId();
        } else {
            cid = CommitteeGuard.communityId();
        }
        Page<Notice> p = noticeMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<Notice>()
                        .eq(Notice::getCommunityId, cid)
                        .eq(Notice::getCreatedBy, u.getUserId())
                        .eq(Notice::getCreatorIdentity, creatorIdentity)
                        .isNull(Notice::getDeletedAt)
                        .last(NOTICE_ORDER_SQL));
        return pageOfNotices(p, page, pageSize);
    }

    public Map<String, Object> platformList(Long communityId, int page, int pageSize) {
        AuthUser u = AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台身份");
        }
        LambdaQueryWrapper<Notice> q = new LambdaQueryWrapper<Notice>()
                .isNull(Notice::getDeletedAt)
                .last(NOTICE_ORDER_SQL);
        if (communityId != null) {
            q.eq(Notice::getCommunityId, communityId);
        }
        Page<Notice> p = noticeMapper.selectPage(new Page<>(page, pageSize), q);
        return pageOfNotices(p, page, pageSize);
    }

    private boolean isOwner(AuthUser u) {
        if (u.getCommunityId() == null) {
            return false;
        }
        long cnt = roomOccupantMapper.selectCount(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getUserId, u.getUserId())
                .eq(RoomOccupant::getCommunityId, u.getCommunityId())
                .eq(RoomOccupant::getResidentRole, "OWNER")
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        return cnt > 0;
    }

    private Notice requireOwned(Long id) {
        Notice n = noticeMapper.selectById(id);
        if (n == null || n.getDeletedAt() != null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "公告不存在");
        }
        AuthUser u = AuthContext.require();
        if (!u.getUserId().equals(n.getCreatedBy())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "仅创建人可改");
        }
        return n;
    }

    private static void validate(String title, String content, String noticeType, String visibility) {
        if (title == null || title.isBlank() || content == null || content.isBlank() || noticeType == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "title/content/noticeType 必填");
        }
        if (!VIS.contains(visibility)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "visibility 非法");
        }
    }

    private static Map<String, Object> pageOfNotices(Page<Notice> p, int page, int pageSize) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", p.getRecords().stream().map(NoticeService::toBrief).toList());
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    private static Map<String, Object> toBrief(Notice n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("communityId", n.getCommunityId());
        m.put("title", n.getTitle());
        m.put("content", n.getContent());
        m.put("noticeType", n.getNoticeType());
        m.put("urgency", n.getUrgency());
        m.put("visibility", n.getVisibility());
        m.put("effectiveAt", n.getEffectiveAt());
        m.put("expireAt", n.getExpireAt());
        m.put("coverAttachmentId", n.getCoverAttachmentId());
        m.put("coverUrl", n.getCoverAttachmentId() == null
                ? null
                : "/api/v1/attachments/" + n.getCoverAttachmentId());
        boolean onBanner = n.getShowOnBanner() != null && n.getShowOnBanner() == 1;
        m.put("showOnBanner", onBanner);
        // 未上 Banner 时不返回顺序，避免脏数据让人误以为还在轮播位上
        m.put("bannerOrder", onBanner ? n.getBannerOrder() : null);
        m.put("createdBy", n.getCreatedBy());
        m.put("creatorIdentity", n.getCreatorIdentity());
        m.put("createdAt", n.getCreatedAt());
        m.put("updatedAt", n.getUpdatedAt());
        return m;
    }

    private static Map<String, Object> pageOf(Page<?> p, int page, int pageSize) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", p.getRecords());
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }
}
