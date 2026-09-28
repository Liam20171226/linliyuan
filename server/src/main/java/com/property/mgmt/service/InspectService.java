package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.common.RoomPaths;
import com.property.mgmt.common.StaffRoles;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InspectService {

    public static final Set<String> CATEGORIES = Set.of("SAFETY", "CLEANING", "FACILITY", "LANDSCAPE");
    public static final Set<String> FREQ_UNITS = Set.of("DAY", "WEEK", "MONTH", "QUARTER", "YEAR");
    public static final Set<String> EXECUTOR_ROLES = Set.of(
            StaffRoles.SECURITY, StaffRoles.CLEANING, StaffRoles.FACILITY_MAINT, StaffRoles.LANDSCAPING);

    private final InspectSpotMapper spotMapper;
    private final InspectPlanMapper planMapper;
    private final InspectPlanSpotMapper planSpotMapper;
    private final InspectJobMapper jobMapper;
    private final InspectVisitMapper visitMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final FloorMapper floorMapper;
    private final SysUserMapper sysUserMapper;
    private final AttachmentService attachmentService;

    // ---------- spots ----------

    public List<Map<String, Object>> listSpots(Long floorId, Long buildingId, Long unitId) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<InspectSpot> q = new LambdaQueryWrapper<InspectSpot>()
                .eq(InspectSpot::getCommunityId, cid)
                .isNull(InspectSpot::getDeletedAt)
                .orderByAsc(InspectSpot::getSortNo)
                .orderByDesc(InspectSpot::getId);
        if (floorId != null) q.eq(InspectSpot::getFloorId, floorId);
        if (unitId != null) q.eq(InspectSpot::getUnitId, unitId);
        if (buildingId != null) q.eq(InspectSpot::getBuildingId, buildingId);
        return spotMapper.selectList(q).stream().map(this::spotRow).collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> createSpot(Map<String, Object> body) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = StaffGuard.communityId();
        Long buildingId = toLong(body.get("buildingId"));
        Long unitId = toLong(body.get("unitId"));
        Long floorId = toLong(body.get("floorId"));
        long[] loc = normalizeSpotLocation(cid, buildingId, unitId, floorId);
        buildingId = loc[0] == 0 ? null : loc[0];
        unitId = loc[1] == 0 ? null : loc[1];
        floorId = loc[2] == 0 ? null : loc[2];
        if (buildingId == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "所属楼栋必填");
        }
        String name = str(body.get("name"));
        if (!StringUtils.hasText(name)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "位置名称必填");
        }
        List<String> cats = normalizeCategories(body.get("categories"));
        if (cats.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请至少选择一个巡检类别");
        }
        LocalDateTime now = LocalDateTime.now();
        InspectSpot s = new InspectSpot();
        s.setCommunityId(cid);
        s.setBuildingId(buildingId);
        s.setUnitId(unitId);
        s.setFloorId(floorId);
        s.setName(name.trim());
        s.setCategories(String.join(",", cats));
        applyCategoryReqs(s, body);
        s.setQrToken(UUID.randomUUID().toString().replace("-", ""));
        s.setSortNo(toInt(body.get("sortNo"), 0));
        s.setCreatedBy(u.getUserId());
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        spotMapper.insert(s);
        return spotRow(s);
    }

    @Transactional
    public Map<String, Object> updateSpot(Long id, Map<String, Object> body) {
        StaffGuard.requireStaff();
        InspectSpot s = requireSpot(id);
        if (body.containsKey("name")) {
            String name = str(body.get("name"));
            if (!StringUtils.hasText(name)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "位置名称必填");
            }
            s.setName(name.trim());
        }
        if (body.containsKey("categories")) {
            List<String> cats = normalizeCategories(body.get("categories"));
            if (cats.isEmpty()) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "请至少选择一个巡检类别");
            }
            s.setCategories(String.join(",", cats));
        }
        applyCategoryReqs(s, body);
        if (body.containsKey("sortNo")) {
            s.setSortNo(toInt(body.get("sortNo"), 0));
        }
        if (body.containsKey("buildingId") || body.containsKey("unitId") || body.containsKey("floorId")) {
            Long buildingId = body.containsKey("buildingId") ? toLong(body.get("buildingId")) : s.getBuildingId();
            Long unitId = body.containsKey("unitId") ? toLong(body.get("unitId")) : s.getUnitId();
            Long floorId = body.containsKey("floorId") ? toLong(body.get("floorId")) : s.getFloorId();
            // 显式传 null 清空单元/楼层
            if (body.containsKey("unitId") && body.get("unitId") == null) unitId = null;
            if (body.containsKey("floorId") && body.get("floorId") == null) floorId = null;
            long[] loc = normalizeSpotLocation(s.getCommunityId(), buildingId, unitId, floorId);
            buildingId = loc[0] == 0 ? null : loc[0];
            unitId = loc[1] == 0 ? null : loc[1];
            floorId = loc[2] == 0 ? null : loc[2];
            if (buildingId == null) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "所属楼栋必填");
            }
            s.setBuildingId(buildingId);
            s.setUnitId(unitId);
            s.setFloorId(floorId);
            // MyBatis-Plus 默认跳过 null，需显式清空
            spotMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InspectSpot>()
                    .eq(InspectSpot::getId, s.getId())
                    .set(InspectSpot::getBuildingId, buildingId)
                    .set(InspectSpot::getUnitId, unitId)
                    .set(InspectSpot::getFloorId, floorId));
        }
        s.setUpdatedAt(LocalDateTime.now());
        spotMapper.updateById(s);
        return spotRow(spotMapper.selectById(id));
    }

    /** 校验并归一化楼栋/单元/楼层：楼栋必填；若指定楼层则回填单元与楼栋；若仅单元则回填楼栋。返回 [buildingId, unitId, floorId]，0 表示 null。 */
    private long[] normalizeSpotLocation(Long cid, Long buildingId, Long unitId, Long floorId) {
        if (floorId != null) {
            Floor floor = floorMapper.selectById(floorId);
            if (floor == null || floor.getDeletedAt() != null || !cid.equals(floor.getCommunityId())) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "楼层不存在");
            }
            if (unitId != null && !unitId.equals(floor.getUnitId())) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "楼层与单元不匹配");
            }
            if (buildingId != null && !buildingId.equals(floor.getBuildingId())) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "楼层与楼栋不匹配");
            }
            return new long[]{floor.getBuildingId(), floor.getUnitId(), floor.getId()};
        }
        if (unitId != null) {
            Unit unit = unitMapper.selectById(unitId);
            if (unit == null || unit.getDeletedAt() != null || !cid.equals(unit.getCommunityId())) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "单元不存在");
            }
            if (buildingId != null && !buildingId.equals(unit.getBuildingId())) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "单元与楼栋不匹配");
            }
            return new long[]{unit.getBuildingId(), unit.getId(), 0};
        }
        if (buildingId != null) {
            Building building = buildingMapper.selectById(buildingId);
            if (building == null || building.getDeletedAt() != null || !cid.equals(building.getCommunityId())) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "楼栋不存在");
            }
            return new long[]{building.getId(), 0, 0};
        }
        return new long[]{0, 0, 0};
    }

    @Transactional
    public void deleteSpot(Long id) {
        StaffGuard.requireStaff();
        InspectSpot s = requireSpot(id);
        s.setDeletedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        spotMapper.updateById(s);
    }

    public Map<String, Object> spotQrPayload(Long id) {
        StaffGuard.requireStaff();
        InspectSpot s = requireSpot(id);
        Map<String, Object> m = spotRow(s);
        m.put("qrContent", qrContent(s.getQrToken()));
        return m;
    }

    public List<Map<String, Object>> batchQrPayload(List<Long> ids) {
        StaffGuard.requireStaff();
        if (ids == null || ids.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请选择位置");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : ids) {
            InspectSpot s = requireSpot(id);
            Map<String, Object> m = spotRow(s);
            m.put("qrContent", qrContent(s.getQrToken()));
            out.add(m);
        }
        return out;
    }

    // ---------- plans ----------

    public List<Map<String, Object>> listPlans(String status) {
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<InspectPlan> q = new LambdaQueryWrapper<InspectPlan>()
                .eq(InspectPlan::getCommunityId, cid)
                .orderByDesc(InspectPlan::getId);
        if (StringUtils.hasText(status)) {
            q.eq(InspectPlan::getStatus, status.trim());
        }
        return planMapper.selectList(q).stream().map(this::planRow).collect(Collectors.toList());
    }

    public Map<String, Object> getPlan(Long id) {
        StaffGuard.requireStaff();
        InspectPlan p = requirePlan(id);
        Map<String, Object> m = planRow(p);
        m.put("spots", planSpots(p.getId()));
        m.put("jobs", listJobsOfPlan(p.getId()));
        return m;
    }

    @Transactional
    public Map<String, Object> createPlan(Map<String, Object> body) {
        AuthUser u = StaffGuard.requireStaff();
        StaffGuard.requirePlanner(u);
        Long cid = StaffGuard.communityId();
        InspectPlan p = buildPlanEntity(body, cid, u.getUserId(), true);
        planMapper.insert(p);
        savePlanSpots(p.getId(), body.get("spotIds"), body.get("ordered"));
        return getPlan(p.getId());
    }

    @Transactional
    public Map<String, Object> updatePlan(Long id, Map<String, Object> body) {
        AuthUser u = StaffGuard.requireStaff();
        StaffGuard.requirePlanner(u);
        InspectPlan p = requirePlan(id);
        if (!"DRAFT".equals(p.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "仅草稿可修改");
        }
        fillPlanFields(p, body, false);
        p.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(p);
        if (body.containsKey("spotIds") || body.containsKey("ordered")) {
            planSpotMapper.delete(new LambdaQueryWrapper<InspectPlanSpot>().eq(InspectPlanSpot::getPlanId, id));
            savePlanSpots(id, body.get("spotIds"), body.containsKey("ordered") ? body.get("ordered") : p.getOrdered());
        }
        return getPlan(id);
    }

    @Transactional
    public Map<String, Object> publishPlan(Long id) {
        AuthUser u = StaffGuard.requireStaff();
        StaffGuard.requirePlanner(u);
        InspectPlan p = requirePlan(id);
        if (!"DRAFT".equals(p.getStatus()) && !"PUBLISHED".equals(p.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "当前状态不可发布");
        }
        long spotCnt = planSpotMapper.selectCount(new LambdaQueryWrapper<InspectPlanSpot>()
                .eq(InspectPlanSpot::getPlanId, id));
        if (spotCnt <= 0) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请先选择巡检点位");
        }
        LocalDateTime now = LocalDateTime.now();
        p.setStatus("PUBLISHED");
        p.setUpdatedAt(now);
        planMapper.updateById(p);

        // 发布时生成第 1 个执行任务（周期内待接单）
        Long openJobs = jobMapper.selectCount(new LambdaQueryWrapper<InspectJob>()
                .eq(InspectJob::getPlanId, id)
                .in(InspectJob::getStatus, List.of("OPEN", "IN_PROGRESS")));
        if (openJobs == null || openJobs == 0) {
            InspectJob job = newJob(p, 1, now);
            jobMapper.insert(job);
        }
        return getPlan(id);
    }

    @Transactional
    public void cancelPlan(Long id) {
        AuthUser u = StaffGuard.requireStaff();
        StaffGuard.requirePlanner(u);
        InspectPlan p = requirePlan(id);
        p.setStatus("CANCELLED");
        p.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(p);
        List<InspectJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<InspectJob>()
                .eq(InspectJob::getPlanId, id)
                .in(InspectJob::getStatus, List.of("OPEN", "IN_PROGRESS")));
        for (InspectJob j : jobs) {
            j.setStatus("CANCELLED");
            j.setUpdatedAt(LocalDateTime.now());
            jobMapper.updateById(j);
        }
    }

    // ---------- jobs / execute (miniapp) ----------

    public Map<String, Object> myJobs(String status) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = StaffGuard.communityId();
        List<String> roles = StaffGuard.activeRoles(u.getUserId(), cid);
        boolean planner = StaffGuard.isPlatformScope(u)
                || roles.contains(StaffRoles.CUSTOMER_SERVICE)
                || roles.contains(StaffRoles.PROPERTY_MANAGER);
        boolean executor = StaffGuard.canExecuteInspect(u.getUserId(), cid);
        if (!planner && !executor) {
            return Map.of("list", List.of());
        }
        List<InspectJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<InspectJob>()
                .eq(InspectJob::getCommunityId, cid)
                .orderByDesc(InspectJob::getId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (InspectJob j : jobs) {
            InspectPlan p = planMapper.selectById(j.getPlanId());
            if (p == null || !"PUBLISHED".equals(p.getStatus())) continue;
            // 经理/客服/平台可看全部；一线岗仅看派给本岗位或本人已接的任务
            if (!planner
                    && !roleMatch(p.getExecutorRoles(), roles)
                    && !Objects.equals(j.getAssigneeUserId(), u.getUserId())) {
                continue;
            }
            if (StringUtils.hasText(status)) {
                String st = status.trim();
                if ("OPEN".equals(st) && !("OPEN".equals(j.getStatus()) || "IN_PROGRESS".equals(j.getStatus()))) {
                    continue;
                }
                if ("DONE".equals(st) && !"DONE".equals(j.getStatus())) continue;
                if (!"OPEN".equals(st) && !"DONE".equals(st) && !st.equals(j.getStatus())) continue;
            }
            list.add(jobRow(j, p));
        }
        return Map.of("list", list);
    }

    public Map<String, Object> getJob(Long id) {
        AuthUser u = StaffGuard.requireStaff();
        InspectJob j = requireJob(id);
        InspectPlan p = requirePlan(j.getPlanId());
        Map<String, Object> m = jobRow(j, p);
        List<Map<String, Object>> spots = planSpots(p.getId());
        List<InspectVisit> visits = visitMapper.selectList(new LambdaQueryWrapper<InspectVisit>()
                .eq(InspectVisit::getJobId, id));
        Map<Long, InspectVisit> bySpot = visits.stream()
                .collect(Collectors.toMap(InspectVisit::getSpotId, v -> v, (a, b) -> a));
        for (Map<String, Object> sp : spots) {
            Long sid = (Long) sp.get("id");
            InspectVisit v = bySpot.get(sid);
            sp.put("visited", v != null);
            if (v != null) {
                sp.put("visitId", v.getId());
                sp.put("note", v.getNote());
                sp.put("photoAttachmentId", v.getPhotoAttachmentId());
                sp.put("scannedAt", v.getScannedAt());
            }
        }
        m.put("spots", spots);
        m.put("visitedCount", visits.size());
        m.put("spotCount", spots.size());
        Long cid = StaffGuard.communityId();
        List<String> roles = StaffGuard.activeRoles(u.getUserId(), cid);
        boolean canTake = canTakeJob(u, p, roles);
        boolean openForClaim = ("OPEN".equals(j.getStatus()) || "IN_PROGRESS".equals(j.getStatus()))
                && (j.getAssigneeUserId() == null || Objects.equals(j.getAssigneeUserId(), u.getUserId()));
        m.put("canClaim", openForClaim && j.getAssigneeUserId() == null && canTake);
        m.put("mine", Objects.equals(j.getAssigneeUserId(), u.getUserId()));
        m.put("canTake", canTake);
        return m;
    }

    @Transactional
    public Map<String, Object> claimJob(Long id) {
        AuthUser u = StaffGuard.requireStaff();
        Long cid = StaffGuard.communityId();
        List<String> roles = StaffGuard.activeRoles(u.getUserId(), cid);
        InspectJob j = requireJob(id);
        InspectPlan p = requirePlan(j.getPlanId());
        if (!canTakeJob(u, p, roles)) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "该计划未派给您的岗位，无法接单");
        }
        if (!"OPEN".equals(j.getStatus()) && !"IN_PROGRESS".equals(j.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "任务不可接单");
        }
        if (j.getAssigneeUserId() != null && !j.getAssigneeUserId().equals(u.getUserId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "已被他人接单");
        }
        LocalDateTime now = LocalDateTime.now();
        j.setAssigneeUserId(u.getUserId());
        j.setClaimedAt(now);
        j.setStatus("IN_PROGRESS");
        j.setUpdatedAt(now);
        jobMapper.updateById(j);
        return getJob(id);
    }

    /** 一线执行岗匹配计划岗位，或经理/客服/平台可代接。 */
    private boolean canTakeJob(AuthUser u, InspectPlan p, List<String> roles) {
        if (StaffGuard.isPlatformScope(u)) return true;
        if (roles != null && (roles.contains(StaffRoles.PROPERTY_MANAGER)
                || roles.contains(StaffRoles.CUSTOMER_SERVICE))) {
            return true;
        }
        return roleMatch(p.getExecutorRoles(), roles);
    }

    @Transactional
    public Map<String, Object> scanVisit(Long jobId, Map<String, Object> body) {
        AuthUser u = StaffGuard.requireStaff();
        InspectJob j = requireJob(jobId);
        if (!Objects.equals(j.getAssigneeUserId(), u.getUserId())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先接单后再扫码");
        }
        if (!"IN_PROGRESS".equals(j.getStatus()) && !"OPEN".equals(j.getStatus())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "任务已结束");
        }
        String token = str(body.get("qrToken"));
        if (!StringUtils.hasText(token) && body.get("qrContent") != null) {
            token = parseToken(str(body.get("qrContent")));
        }
        if (!StringUtils.hasText(token)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "无效二维码");
        }
        InspectSpot spot = spotMapper.selectOne(new LambdaQueryWrapper<InspectSpot>()
                .eq(InspectSpot::getQrToken, token.trim())
                .isNull(InspectSpot::getDeletedAt));
        if (spot == null || !j.getCommunityId().equals(spot.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "巡检点不存在");
        }
        // 点位须在计划内
        InspectPlanSpot ps = planSpotMapper.selectOne(new LambdaQueryWrapper<InspectPlanSpot>()
                .eq(InspectPlanSpot::getPlanId, j.getPlanId())
                .eq(InspectPlanSpot::getSpotId, spot.getId()));
        if (ps == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "该点位不在本计划内");
        }
        InspectPlan plan = requirePlan(j.getPlanId());
        if (plan.getOrdered() != null && plan.getOrdered() == 1) {
            assertOrder(j, spot.getId());
        }
        Long photoId = toLong(body.get("photoAttachmentId"));
        if (photoId == null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请先拍照上传");
        }
        Attachment photo = attachmentService.get(photoId);
        if (photo == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "照片不存在");
        }
        InspectVisit existing = visitMapper.selectOne(new LambdaQueryWrapper<InspectVisit>()
                .eq(InspectVisit::getJobId, jobId)
                .eq(InspectVisit::getSpotId, spot.getId()));
        if (existing != null) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "该点位已巡检");
        }
        LocalDateTime now = LocalDateTime.now();
        attachmentService.bindToBiz(List.of(photoId), "INSPECT_PHOTO", jobId, j.getCommunityId(), u.getUserId());

        InspectVisit v = new InspectVisit();
        v.setCommunityId(j.getCommunityId());
        v.setJobId(jobId);
        v.setSpotId(spot.getId());
        v.setUserId(u.getUserId());
        v.setNote(str(body.get("note")));
        v.setPhotoAttachmentId(photoId);
        v.setScannedAt(now);
        v.setCreatedAt(now);
        visitMapper.insert(v);

        if ("OPEN".equals(j.getStatus())) {
            j.setStatus("IN_PROGRESS");
            j.setUpdatedAt(now);
            jobMapper.updateById(j);
        }

        // 全部点位完成 → 任务完成，并按频率生成下一轮（在窗口内）
        long need = planSpotMapper.selectCount(new LambdaQueryWrapper<InspectPlanSpot>()
                .eq(InspectPlanSpot::getPlanId, j.getPlanId()));
        long done = visitMapper.selectCount(new LambdaQueryWrapper<InspectVisit>()
                .eq(InspectVisit::getJobId, jobId));
        if (need > 0 && done >= need) {
            j.setStatus("DONE");
            j.setDoneAt(now);
            j.setUpdatedAt(now);
            jobMapper.updateById(j);
            maybeSpawnNextJob(plan, j, now);
        }
        return getJob(jobId);
    }

    // ---------- photo wall ----------

    public Map<String, Object> photoWall(Long planId, Long jobId, int page, int pageSize) {
        StaffGuard.requireStaff();
        Long cid = StaffGuard.communityId();
        LambdaQueryWrapper<InspectVisit> q = new LambdaQueryWrapper<InspectVisit>()
                .eq(InspectVisit::getCommunityId, cid)
                .isNotNull(InspectVisit::getPhotoAttachmentId)
                .orderByDesc(InspectVisit::getScannedAt);
        if (jobId != null) {
            q.eq(InspectVisit::getJobId, jobId);
        } else if (planId != null) {
            List<InspectJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<InspectJob>()
                    .eq(InspectJob::getPlanId, planId));
            if (jobs.isEmpty()) {
                return pageEmpty(page, pageSize);
            }
            q.in(InspectVisit::getJobId, jobs.stream().map(InspectJob::getId).toList());
        }
        List<InspectVisit> all = visitMapper.selectList(q);
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(all.size(), from + pageSize);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            InspectVisit v = all.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("jobId", v.getJobId());
            m.put("spotId", v.getSpotId());
            m.put("userId", v.getUserId());
            m.put("note", v.getNote());
            m.put("photoAttachmentId", v.getPhotoAttachmentId());
            m.put("scannedAt", v.getScannedAt());
            InspectSpot spot = spotMapper.selectById(v.getSpotId());
            if (spot != null) {
                m.put("spotName", spot.getName());
                m.put("address", spotAddress(spot));
            }
            SysUser user = sysUserMapper.selectById(v.getUserId());
            m.put("userName", user == null ? "" : Optional.ofNullable(user.getRealName()).orElse(user.getMobile()));
            InspectJob job = jobMapper.selectById(v.getJobId());
            if (job != null) {
                InspectPlan plan = planMapper.selectById(job.getPlanId());
                m.put("planId", job.getPlanId());
                m.put("planTitle", plan == null ? "" : plan.getTitle());
                m.put("jobSeq", job.getSeqNo());
            }
            rows.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", rows);
        data.put("total", all.size());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    // ---------- helpers ----------

    private void maybeSpawnNextJob(InspectPlan plan, InspectJob finished, LocalDateTime now) {
        if (plan.getEndAt() != null && now.isAfter(plan.getEndAt())) {
            return;
        }
        int nextSeq = (finished.getSeqNo() == null ? 1 : finished.getSeqNo()) + 1;
        int times = plan.getFreqTimes() == null ? 1 : Math.max(1, plan.getFreqTimes());
        // 简化：在结束时间前持续生成下一轮；频次作为提示字段保留
        // 日计划上限放宽（约一年），其它频率仍按周估算
        int cap = "DAY".equals(plan.getFreqUnit()) ? Math.max(times * 400, 400) : Math.max(times * 52, 52);
        if (nextSeq > cap) {
            return; // 安全上限
        }
        Long open = jobMapper.selectCount(new LambdaQueryWrapper<InspectJob>()
                .eq(InspectJob::getPlanId, plan.getId())
                .in(InspectJob::getStatus, List.of("OPEN", "IN_PROGRESS")));
        if (open != null && open > 0) return;
        InspectJob nj = newJob(plan, nextSeq, now);
        jobMapper.insert(nj);
    }

    private InspectJob newJob(InspectPlan p, int seq, LocalDateTime now) {
        InspectJob j = new InspectJob();
        j.setCommunityId(p.getCommunityId());
        j.setPlanId(p.getId());
        j.setSeqNo(seq);
        j.setStatus("OPEN");
        j.setCreatedAt(now);
        j.setUpdatedAt(now);
        return j;
    }

    private void assertOrder(InspectJob j, Long spotId) {
        List<InspectPlanSpot> ordered = planSpotMapper.selectList(new LambdaQueryWrapper<InspectPlanSpot>()
                .eq(InspectPlanSpot::getPlanId, j.getPlanId())
                .orderByAsc(InspectPlanSpot::getSortNo)
                .orderByAsc(InspectPlanSpot::getId));
        Set<Long> visited = visitMapper.selectList(new LambdaQueryWrapper<InspectVisit>()
                        .eq(InspectVisit::getJobId, j.getId()))
                .stream().map(InspectVisit::getSpotId).collect(Collectors.toSet());
        for (InspectPlanSpot ps : ordered) {
            if (visited.contains(ps.getSpotId())) continue;
            if (!ps.getSpotId().equals(spotId)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "请按计划顺序巡检");
            }
            break;
        }
    }

    private InspectPlan buildPlanEntity(Map<String, Object> body, Long cid, Long userId, boolean creating) {
        InspectPlan p = new InspectPlan();
        p.setCommunityId(cid);
        p.setCreatedBy(userId);
        p.setStatus("DRAFT");
        LocalDateTime now = LocalDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        fillPlanFields(p, body, creating);
        return p;
    }

    private void fillPlanFields(InspectPlan p, Map<String, Object> body, boolean requireAll) {
        String title = str(body.get("title"));
        if (requireAll || body.containsKey("title")) {
            if (!StringUtils.hasText(title)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "计划标题必填");
            }
            p.setTitle(title.trim());
        }
        if (requireAll || body.containsKey("startAt")) {
            LocalDateTime start = toTime(body.get("startAt"));
            if (start == null) throw BizException.of(ErrorCodes.BAD_PARAM, "开始时间必填");
            p.setStartAt(start);
        }
        if (requireAll || body.containsKey("endAt")) {
            LocalDateTime end = toTime(body.get("endAt"));
            if (end == null) throw BizException.of(ErrorCodes.BAD_PARAM, "结束时间必填");
            p.setEndAt(end);
        }
        if (p.getStartAt() != null && p.getEndAt() != null && !p.getEndAt().isAfter(p.getStartAt())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "结束时间须晚于开始时间");
        }
        if (requireAll || body.containsKey("freqUnit")) {
            String fu = str(body.get("freqUnit"));
            if (!FREQ_UNITS.contains(fu)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "频率单位须为 DAY/WEEK/MONTH/QUARTER/YEAR");
            }
            p.setFreqUnit(fu);
        }
        if (requireAll || body.containsKey("freqTimes")) {
            int times = toInt(body.get("freqTimes"), 1);
            if (times < 1) times = 1;
            p.setFreqTimes(times);
        }
        if (requireAll || body.containsKey("ordered")) {
            p.setOrdered(truthy(body.get("ordered")) ? 1 : 0);
        }
        if (body.containsKey("requirementNote")) {
            p.setRequirementNote(str(body.get("requirementNote")));
        }
        if (requireAll || body.containsKey("executorRoles")) {
            List<String> roles = normalizeExecutorRoles(body.get("executorRoles"));
            if (roles.isEmpty()) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "请选择执行岗位");
            }
            p.setExecutorRoles(String.join(",", roles));
        }
    }

    private void savePlanSpots(Long planId, Object spotIdsRaw, Object orderedFlag) {
        List<Long> spotIds = toLongList(spotIdsRaw);
        if (spotIds.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "请选择巡检点位");
        }
        Long cid = StaffGuard.communityId();
        int i = 0;
        for (Long sid : spotIds) {
            InspectSpot s = requireSpot(sid);
            if (!cid.equals(s.getCommunityId())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "点位不属于本小区");
            }
            InspectPlanSpot ps = new InspectPlanSpot();
            ps.setPlanId(planId);
            ps.setSpotId(sid);
            ps.setSortNo(i++);
            planSpotMapper.insert(ps);
        }
    }

    private List<Map<String, Object>> planSpots(Long planId) {
        List<InspectPlanSpot> links = planSpotMapper.selectList(new LambdaQueryWrapper<InspectPlanSpot>()
                .eq(InspectPlanSpot::getPlanId, planId)
                .orderByAsc(InspectPlanSpot::getSortNo)
                .orderByAsc(InspectPlanSpot::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (InspectPlanSpot link : links) {
            InspectSpot s = spotMapper.selectById(link.getSpotId());
            if (s == null || s.getDeletedAt() != null) continue;
            Map<String, Object> row = spotRow(s);
            row.put("sortNo", link.getSortNo());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> listJobsOfPlan(Long planId) {
        return jobMapper.selectList(new LambdaQueryWrapper<InspectJob>()
                        .eq(InspectJob::getPlanId, planId)
                        .orderByDesc(InspectJob::getSeqNo))
                .stream().map(j -> {
                    InspectPlan p = planMapper.selectById(planId);
                    return jobRow(j, p);
                }).collect(Collectors.toList());
    }

    private Map<String, Object> spotRow(InspectSpot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("communityId", s.getCommunityId());
        m.put("buildingId", s.getBuildingId());
        m.put("unitId", s.getUnitId());
        m.put("floorId", s.getFloorId());
        m.put("name", s.getName());
        m.put("categories", splitCsv(s.getCategories()));
        m.put("categoryLabels", splitCsv(s.getCategories()).stream().map(this::catLabel).toList());
        m.put("reqSafety", s.getReqSafety());
        m.put("reqCleaning", s.getReqCleaning());
        m.put("reqFacility", s.getReqFacility());
        m.put("reqLandscape", s.getReqLandscape());
        m.put("qrToken", s.getQrToken());
        m.put("qrContent", qrContent(s.getQrToken()));
        m.put("sortNo", s.getSortNo());
        m.put("address", spotAddress(s));
        m.put("createdAt", s.getCreatedAt());
        return m;
    }

    private Map<String, Object> planRow(InspectPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("title", p.getTitle());
        m.put("startAt", p.getStartAt());
        m.put("endAt", p.getEndAt());
        m.put("freqUnit", p.getFreqUnit());
        m.put("freqUnitLabel", freqLabel(p.getFreqUnit()));
        m.put("freqTimes", p.getFreqTimes());
        m.put("ordered", p.getOrdered() != null && p.getOrdered() == 1);
        m.put("requirementNote", p.getRequirementNote());
        m.put("executorRoles", splitCsv(p.getExecutorRoles()));
        m.put("executorRoleLabels", splitCsv(p.getExecutorRoles()).stream().map(StaffRoles::label).toList());
        m.put("status", p.getStatus());
        m.put("statusLabel", planStatusLabel(p.getStatus()));
        m.put("createdBy", p.getCreatedBy());
        m.put("createdAt", p.getCreatedAt());
        long spots = planSpotMapper.selectCount(new LambdaQueryWrapper<InspectPlanSpot>()
                .eq(InspectPlanSpot::getPlanId, p.getId()));
        m.put("spotCount", spots);
        return m;
    }

    private Map<String, Object> jobRow(InspectJob j, InspectPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("planId", j.getPlanId());
        m.put("planTitle", p == null ? "" : p.getTitle());
        m.put("seqNo", j.getSeqNo());
        m.put("status", j.getStatus());
        m.put("statusLabel", jobStatusLabel(j.getStatus()));
        m.put("assigneeUserId", j.getAssigneeUserId());
        if (j.getAssigneeUserId() != null) {
            SysUser user = sysUserMapper.selectById(j.getAssigneeUserId());
            m.put("assigneeName", user == null ? "" : Optional.ofNullable(user.getRealName()).orElse(user.getMobile()));
        }
        m.put("claimedAt", j.getClaimedAt());
        m.put("doneAt", j.getDoneAt());
        m.put("createdAt", j.getCreatedAt());
        if (p != null) {
            m.put("requirementNote", p.getRequirementNote());
            m.put("ordered", p.getOrdered() != null && p.getOrdered() == 1);
            m.put("executorRoles", splitCsv(p.getExecutorRoles()));
        }
        long visited = visitMapper.selectCount(new LambdaQueryWrapper<InspectVisit>()
                .eq(InspectVisit::getJobId, j.getId()));
        long need = p == null ? 0 : planSpotMapper.selectCount(new LambdaQueryWrapper<InspectPlanSpot>()
                .eq(InspectPlanSpot::getPlanId, p.getId()));
        m.put("visitedCount", visited);
        m.put("spotCount", need);
        return m;
    }

    private String spotAddress(InspectSpot s) {
        Building b = s.getBuildingId() == null ? null : buildingMapper.selectById(s.getBuildingId());
        Unit u = s.getUnitId() == null ? null : unitMapper.selectById(s.getUnitId());
        Floor f = s.getFloorId() == null ? null : floorMapper.selectById(s.getFloorId());
        String path = RoomPaths.format(
                b == null ? null : b.getName(),
                u == null ? null : u.getName(),
                RoomPaths.floorLabel(f),
                null);
        if (!StringUtils.hasText(path)) return s.getName();
        return path + " · " + s.getName();
    }

    private InspectSpot requireSpot(Long id) {
        Long cid = StaffGuard.communityId();
        InspectSpot s = spotMapper.selectById(id);
        if (s == null || s.getDeletedAt() != null || !cid.equals(s.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "巡检位置不存在");
        }
        return s;
    }

    private InspectPlan requirePlan(Long id) {
        Long cid = StaffGuard.communityId();
        InspectPlan p = planMapper.selectById(id);
        if (p == null || !cid.equals(p.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "巡检计划不存在");
        }
        return p;
    }

    private InspectJob requireJob(Long id) {
        Long cid = StaffGuard.communityId();
        InspectJob j = jobMapper.selectById(id);
        if (j == null || !cid.equals(j.getCommunityId())) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "巡检任务不存在");
        }
        return j;
    }

    private void applyCategoryReqs(InspectSpot s, Map<String, Object> body) {
        if (body.containsKey("reqSafety")) s.setReqSafety(str(body.get("reqSafety")));
        if (body.containsKey("reqCleaning")) s.setReqCleaning(str(body.get("reqCleaning")));
        if (body.containsKey("reqFacility")) s.setReqFacility(str(body.get("reqFacility")));
        if (body.containsKey("reqLandscape")) s.setReqLandscape(str(body.get("reqLandscape")));
    }

    private static String qrContent(String token) {
        return "PROPERTY_INSPECT:" + token;
    }

    private static String parseToken(String content) {
        if (content == null) return null;
        String c = content.trim();
        if (c.startsWith("PROPERTY_INSPECT:")) {
            return c.substring("PROPERTY_INSPECT:".length()).trim();
        }
        return c;
    }

    private List<String> normalizeCategories(Object raw) {
        List<String> list = toStrList(raw);
        List<String> out = new ArrayList<>();
        for (String c : list) {
            String u = c.trim().toUpperCase(Locale.ROOT);
            if (CATEGORIES.contains(u) && !out.contains(u)) out.add(u);
        }
        return out;
    }

    private List<String> normalizeExecutorRoles(Object raw) {
        List<String> list = toStrList(raw);
        List<String> out = new ArrayList<>();
        for (String c : list) {
            String n = StaffRoles.normalize(c);
            if (n != null && EXECUTOR_ROLES.contains(n) && !out.contains(n)) out.add(n);
        }
        return out;
    }

    private boolean roleMatch(String executorCsv, List<String> myRoles) {
        if (myRoles == null || myRoles.isEmpty()) return false;
        for (String r : splitCsv(executorCsv)) {
            String n = StaffRoles.normalize(r);
            if (n != null && myRoles.contains(n)) return true;
        }
        return false;
    }

    private String catLabel(String c) {
        return switch (c) {
            case "SAFETY" -> "安全类";
            case "CLEANING" -> "保洁类";
            case "FACILITY" -> "机电维修类";
            case "LANDSCAPE" -> "绿化类";
            default -> c;
        };
    }

    private String freqLabel(String u) {
        return switch (u == null ? "" : u) {
            case "DAY" -> "每日";
            case "WEEK" -> "每周";
            case "MONTH" -> "每月";
            case "QUARTER" -> "每季度";
            case "YEAR" -> "每年";
            default -> u;
        };
    }

    private String planStatusLabel(String s) {
        return switch (s == null ? "" : s) {
            case "DRAFT" -> "草稿";
            case "PUBLISHED" -> "已发布";
            case "CANCELLED" -> "已取消";
            default -> s;
        };
    }

    private String jobStatusLabel(String s) {
        return switch (s == null ? "" : s) {
            case "OPEN" -> "待接单";
            case "IN_PROGRESS" -> "进行中";
            case "DONE" -> "已完成";
            case "CANCELLED" -> "已取消";
            default -> s;
        };
    }

    private static List<String> splitCsv(String csv) {
        if (!StringUtils.hasText(csv)) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private static List<String> toStrList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && StringUtils.hasText(o.toString())) out.add(o.toString().trim());
            }
            return out;
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
    }

    private static List<Long> toLongList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<Long> out = new ArrayList<>();
            for (Object o : list) {
                Long v = toLong(o);
                if (v != null) out.add(v);
            }
            return out;
        }
        Long one = toLong(raw);
        return one == null ? List.of() : List.of(one);
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        String s = o.toString().trim().toLowerCase(Locale.ROOT);
        return "1".equals(s) || "true".equals(s) || "yes".equals(s);
    }

    private static LocalDateTime toTime(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime t) return t;
        String s = o.toString().trim().replace(' ', 'T');
        if (s.length() == 16) s = s + ":00";
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "时间格式无效");
        }
    }

    private static Map<String, Object> pageEmpty(int page, int pageSize) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", List.of());
        data.put("total", 0);
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }
}
