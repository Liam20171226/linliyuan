package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.property.mgmt.common.BizException;
import com.property.mgmt.common.ErrorCodes;
import com.property.mgmt.domain.*;
import com.property.mgmt.mapper.*;
import com.property.mgmt.security.AuthContext;
import com.property.mgmt.security.AuthUser;
import com.property.mgmt.security.CommitteeGuard;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VoteService {

    private final VoteMapper voteMapper;
    private final VoteOptionMapper voteOptionMapper;
    private final VoteBallotMapper voteBallotMapper;
    private final RoomOccupantMapper roomOccupantMapper;
    private final RoomOccupantBindService roomOccupantBindService;
    private final SubscribeNotifyLogMapper subscribeNotifyLogMapper;
    private final TodoNotifyService todoNotifyService;
    private final RoomMapper roomMapper;
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final FloorMapper floorMapper;
    private final SysUserMapper sysUserMapper;

    @Transactional
    public Map<String, Object> create(Long communityId, String title, String background,
                                      LocalDateTime startAt, LocalDateTime endAt,
                                      List<String> options, String creatorRole) {
        AuthUser u = AuthContext.require();
        if (title == null || title.isBlank() || background == null || background.isBlank()
                || startAt == null || endAt == null || options == null || options.size() < 2) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "标题/背景/起止时间/至少2个选项必填");
        }
        if (!endAt.isAfter(startAt)) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "结束时间须晚于开始时间");
        }
        Long cid = communityId;
        if ("COMMITTEE".equals(creatorRole)) {
            cid = CommitteeGuard.communityId();
        } else if ("PROPERTY_MANAGER".equals(creatorRole)) {
            AuthUser staff = StaffGuard.requireStaff();
            StaffGuard.requireManagerOrPlatform(staff);
            cid = staff.getCommunityId();
        } else if ("PLATFORM".equals(creatorRole)) {
            if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台身份");
            }
            if (cid == null) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "communityId 必填");
            }
        } else {
            throw BizException.of(ErrorCodes.BAD_PARAM, "creatorRole 非法");
        }

        LocalDateTime now = LocalDateTime.now();
        Vote vote = new Vote();
        vote.setCommunityId(cid);
        vote.setTitle(title.trim());
        vote.setBackground(background);
        vote.setStartAt(startAt);
        vote.setEndAt(endAt);
        vote.setStatus(deriveStatus(startAt, endAt, now));
        vote.setCreatedBy(u.getUserId());
        vote.setCreatorRole(creatorRole);
        vote.setCreatedAt(now);
        vote.setUpdatedAt(now);
        voteMapper.insert(vote);

        int sort = 0;
        List<VoteOption> optionEntities = new ArrayList<>();
        for (String text : options) {
            if (text == null || text.isBlank()) {
                continue;
            }
            VoteOption opt = new VoteOption();
            opt.setVoteId(vote.getId());
            opt.setOptionText(text.trim());
            opt.setSortNo(sort++);
            voteOptionMapper.insert(opt);
            optionEntities.add(opt);
        }
        if (optionEntities.size() < 2) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "至少 2 个有效选项");
        }

        notifyOwners(cid, vote, "VOTE_START", "业主投票开始", vote.getTitle());
        return detail(vote, optionEntities, List.of());
    }

    @Transactional
    public void delete(Long id) {
        AuthUser u = AuthContext.require();
        Vote vote = voteMapper.selectById(id);
        if (vote == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "投票不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean ended = !now.isBefore(vote.getEndAt());
        boolean platform = u.isPlatformAdmin() || "PLATFORM".equals(u.getIdentityType());
        if (ended) {
            if (!platform) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "截止后仅平台可删");
            }
        } else {
            if (!u.getUserId().equals(vote.getCreatedBy()) && !platform) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "截止前仅发起人可删");
            }
        }
        voteBallotMapper.delete(new LambdaQueryWrapper<VoteBallot>().eq(VoteBallot::getVoteId, id));
        voteOptionMapper.delete(new LambdaQueryWrapper<VoteOption>().eq(VoteOption::getVoteId, id));
        voteMapper.deleteById(id);
    }

    public Map<String, Object> listByCommunity(Long communityId, int page, int pageSize) {
        refreshNearDeadline(communityId);
        Page<Vote> p = voteMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<Vote>()
                        .eq(Vote::getCommunityId, communityId)
                        .orderByDesc(Vote::getId));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Vote v : p.getRecords()) {
            syncStatus(v);
            list.add(summary(v));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    public Map<String, Object> residentList(int page, int pageSize) {
        AuthUser u = requireOwner();
        Map<String, Object> data = listByCommunity(u.getCommunityId(), page, pageSize);
        annotateResidentPending(u, data);
        return data;
    }

    public Map<String, Object> residentGet(Long id) {
        AuthUser u = requireOwner();
        Vote vote = requireCommunityVote(id, u.getCommunityId());
        syncStatus(vote);
        List<VoteOption> options = optionsOf(id);
        List<RoomOccupant> myRooms = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getUserId, u.getUserId())
                .eq(RoomOccupant::getCommunityId, u.getCommunityId())
                .eq(RoomOccupant::getResidentRole, "OWNER")
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        List<Long> roomIds = myRooms.stream().map(RoomOccupant::getRoomId).toList();
        List<VoteBallot> ballots = roomIds.isEmpty() ? List.of() :
                voteBallotMapper.selectList(new LambdaQueryWrapper<VoteBallot>()
                        .eq(VoteBallot::getVoteId, id)
                        .in(VoteBallot::getRoomId, roomIds));
        Map<String, Object> data = detail(vote, options, ballots);
        data.put("myOwnerRooms", roomIds);
        return data;
    }

    public Map<String, Object> shareGet(Long id) {
        return residentGet(id);
    }

    @Transactional
    public List<VoteBallot> castBallots(Long voteId, List<Map<String, Object>> ballots) {
        AuthUser u = requireOwner();
        Vote vote = requireCommunityVote(voteId, u.getCommunityId());
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(vote.getStartAt())) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "投票尚未开始");
        }
        if (!now.isBefore(vote.getEndAt())) {
            throw BizException.of(ErrorCodes.VOTE_CLOSED, "投票已截止");
        }
        if (ballots == null || ballots.isEmpty()) {
            throw BizException.of(ErrorCodes.BAD_PARAM, "ballots 不能为空");
        }
        Set<Long> optionIds = optionsOf(voteId).stream().map(VoteOption::getId).collect(Collectors.toSet());
        List<VoteBallot> result = new ArrayList<>();
        for (Map<String, Object> b : ballots) {
            Long roomId = toLong(b.get("roomId"));
            Long optionId = toLong(b.get("optionId"));
            if (roomId == null || optionId == null) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "roomId/optionId 必填");
            }
            if (!optionIds.contains(optionId)) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "选项不属于本投票");
            }
            RoomOccupant owner = roomOccupantBindService.findActive(roomId, u.getUserId());
            if (owner == null || !"OWNER".equals(owner.getResidentRole())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "仅本人 ACTIVE 业主房可投票");
            }
            VoteBallot existing = voteBallotMapper.selectOne(new LambdaQueryWrapper<VoteBallot>()
                    .eq(VoteBallot::getVoteId, voteId)
                    .eq(VoteBallot::getRoomId, roomId));
            if (existing != null) {
                throw BizException.of(ErrorCodes.BAD_PARAM, "该房屋已投票，确认后不可修改");
            }
            VoteBallot nb = new VoteBallot();
            nb.setVoteId(voteId);
            nb.setRoomId(roomId);
            nb.setVoterUserId(u.getUserId());
            nb.setOptionId(optionId);
            nb.setCreatedAt(now);
            nb.setUpdatedAt(now);
            voteBallotMapper.insert(nb);
            result.add(nb);
        }
        if (ownerRoomsFullyVoted(u.getUserId(), u.getCommunityId(), voteId)) {
            todoNotifyService.doneByBiz("VOTE", voteId, "VOTE_START");
            todoNotifyService.doneByBiz("VOTE", voteId, "VOTE_NEAR_DEADLINE");
        }
        return result;
    }

    public Map<String, Object> stats(Long id) {
        Vote vote = voteMapper.selectById(id);
        if (vote == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "投票不存在");
        }
        AuthUser u = AuthContext.require();
        String identity = u.getIdentityType();
        boolean platform = u.isPlatformAdmin() || "PLATFORM".equals(identity);
        if ("RESIDENT".equals(identity)) {
            requireOwnerInCommunity(vote.getCommunityId());
        } else if ("STAFF".equals(identity)) {
            if (!vote.getCommunityId().equals(StaffGuard.communityId())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "非本小区");
            }
            // 物业经理/客服只能看物业投票结果，业委会投票仅平台可看
            if (!platform && "COMMITTEE".equals(vote.getCreatorRole())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "无权查看业委会投票结果");
            }
        } else if ("COMMITTEE".equals(identity)) {
            if (!vote.getCommunityId().equals(CommitteeGuard.communityId())) {
                throw BizException.of(ErrorCodes.FORBIDDEN, "非本小区");
            }
        } else if (!platform) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "无权查看");
        }

        List<VoteOption> options = optionsOf(id);
        List<VoteBallot> all = voteBallotMapper.selectList(new LambdaQueryWrapper<VoteBallot>()
                .eq(VoteBallot::getVoteId, id)
                .orderByAsc(VoteBallot::getId));
        Map<Long, Long> counts = all.stream()
                .collect(Collectors.groupingBy(VoteBallot::getOptionId, Collectors.counting()));
        Map<Long, String> optionText = options.stream()
                .collect(Collectors.toMap(VoteOption::getId, VoteOption::getOptionText, (a, b) -> a));
        List<Map<String, Object>> optionStats = new ArrayList<>();
        for (VoteOption opt : options) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("optionId", opt.getId());
            row.put("optionText", opt.getOptionText());
            row.put("votes", counts.getOrDefault(opt.getId(), 0L));
            optionStats.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("voteId", id);
        data.put("totalBallots", all.size());
        data.put("options", optionStats);
        // 各房业主选票明细仅管理端可见（物业/业委会/平台），住户端只看汇总
        if (!"RESIDENT".equals(identity)) {
            data.put("ballots", ballotDetails(all, optionText));
        } else {
            data.put("ballots", List.of());
        }
        return data;
    }

    /** 物业 Web：仅物业发起的投票（经理/客服可见） */
    public Map<String, Object> staffList(int page, int pageSize) {
        Long cid = StaffGuard.communityId();
        refreshNearDeadline(cid);
        Page<Vote> p = voteMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<Vote>()
                        .eq(Vote::getCommunityId, cid)
                        .in(Vote::getCreatorRole, List.of("PROPERTY_MANAGER", "STAFF"))
                        .orderByDesc(Vote::getId));
        return pageOfVotes(p, page, pageSize);
    }

    /**
     * 平台进入小区后：业委会发起的投票列表（物业经理/客服不可见）。
     */
    public Map<String, Object> staffCommitteeList(int page, int pageSize) {
        AuthUser u = AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "仅平台管理员可查看业委会投票");
        }
        Long cid = StaffGuard.communityId();
        refreshNearDeadline(cid);
        Page<Vote> p = voteMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<Vote>()
                        .eq(Vote::getCommunityId, cid)
                        .eq(Vote::getCreatorRole, "COMMITTEE")
                        .orderByDesc(Vote::getId));
        return pageOfVotes(p, page, pageSize);
    }

    /** 业委会管理列表：仅本小区业委会发起的投票（不含 Web 物业投票） */
    public Map<String, Object> committeeList(int page, int pageSize) {
        Long cid = CommitteeGuard.communityId();
        refreshNearDeadline(cid);
        Page<Vote> p = voteMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<Vote>()
                        .eq(Vote::getCommunityId, cid)
                        .eq(Vote::getCreatorRole, "COMMITTEE")
                        .orderByDesc(Vote::getId));
        return pageOfVotes(p, page, pageSize);
    }

    @SuppressWarnings("unchecked")
    private void annotateResidentPending(AuthUser u, Map<String, Object> data) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        if (list == null || list.isEmpty()) {
            data.put("pendingVoteCount", 0);
            return;
        }
        List<Long> roomIds = ownerRoomIds(u.getUserId(), u.getCommunityId());
        int pending = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> row : list) {
            Long voteId = row.get("id") instanceof Number n ? n.longValue() : null;
            boolean open = isVoteWindowOpen(row.get("startAt"), row.get("endAt"), now);
            boolean voted = voteId != null && ownerRoomsFullyVoted(u.getUserId(), u.getCommunityId(), voteId, roomIds);
            boolean pendingVote = open && !voted;
            row.put("voted", voted);
            row.put("pendingVote", pendingVote);
            if (pendingVote) {
                pending++;
            }
        }
        data.put("pendingVoteCount", pending);
    }

    private List<Long> ownerRoomIds(Long userId, Long communityId) {
        return roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                        .eq(RoomOccupant::getUserId, userId)
                        .eq(RoomOccupant::getCommunityId, communityId)
                        .eq(RoomOccupant::getResidentRole, "OWNER")
                        .eq(RoomOccupant::getStatus, "ACTIVE"))
                .stream().map(RoomOccupant::getRoomId).filter(Objects::nonNull).distinct().toList();
    }

    private boolean ownerRoomsFullyVoted(Long userId, Long communityId, Long voteId) {
        return ownerRoomsFullyVoted(userId, communityId, voteId, ownerRoomIds(userId, communityId));
    }

    private boolean ownerRoomsFullyVoted(Long userId, Long communityId, Long voteId, List<Long> roomIds) {
        if (voteId == null || roomIds == null || roomIds.isEmpty()) {
            return false;
        }
        long voted = voteBallotMapper.selectCount(new LambdaQueryWrapper<VoteBallot>()
                .eq(VoteBallot::getVoteId, voteId)
                .in(VoteBallot::getRoomId, roomIds));
        return voted >= roomIds.size();
    }

    private static boolean isVoteWindowOpen(Object startAt, Object endAt, LocalDateTime now) {
        LocalDateTime start = parseDateTime(startAt);
        LocalDateTime end = parseDateTime(endAt);
        if (start == null || end == null) {
            return false;
        }
        return !now.isBefore(start) && now.isBefore(end);
    }

    private static LocalDateTime parseDateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime t) {
            return t;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s.replace(" ", "T").substring(0, Math.min(19, s.length())));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private Map<String, Object> pageOfVotes(Page<Vote> p, int page, int pageSize) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Vote v : p.getRecords()) {
            syncStatus(v);
            list.add(summary(v));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    private List<Map<String, Object>> ballotDetails(List<VoteBallot> all, Map<Long, String> optionText) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        Set<Long> roomIds = all.stream().map(VoteBallot::getRoomId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> userIds = all.stream().map(VoteBallot::getVoterUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> roomLabels = roomLabelsOf(roomIds);
        Map<Long, String> userNames = userNamesOf(userIds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (VoteBallot b : all) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ballotId", b.getId());
            row.put("roomId", b.getRoomId());
            row.put("roomLabel", roomLabels.getOrDefault(b.getRoomId(), b.getRoomId() == null ? "—" : ("房屋" + b.getRoomId())));
            row.put("voterUserId", b.getVoterUserId());
            row.put("voterName", userNames.getOrDefault(b.getVoterUserId(),
                    b.getVoterUserId() == null ? "—" : ("用户" + b.getVoterUserId())));
            row.put("optionId", b.getOptionId());
            row.put("optionText", optionText.getOrDefault(b.getOptionId(), "—"));
            row.put("votedAt", b.getUpdatedAt() != null ? b.getUpdatedAt().toString()
                    : (b.getCreatedAt() != null ? b.getCreatedAt().toString() : null));
            out.add(row);
        }
        return out;
    }

    private Map<Long, String> userNamesOf(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        Map<Long, String> map = new LinkedHashMap<>();
        for (SysUser u : sysUserMapper.selectByIds(userIds)) {
            String n = u.getRealName() != null && !u.getRealName().isBlank()
                    ? u.getRealName().trim()
                    : (u.getMobile() != null && !u.getMobile().isBlank()
                        ? u.getMobile().trim()
                        : (u.getUsername() != null ? u.getUsername() : null));
            if (n == null || n.isBlank()) n = "用户" + u.getId();
            map.put(u.getId(), n);
        }
        return map;
    }

    private Map<Long, String> roomLabelsOf(Set<Long> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) return Map.of();
        Map<Long, Room> roomMap = roomMapper.selectByIds(roomIds).stream()
                .collect(Collectors.toMap(Room::getId, r -> r, (a, b) -> a));
        Set<Long> bIds = new LinkedHashSet<>();
        Set<Long> uIds = new LinkedHashSet<>();
        Set<Long> fIds = new LinkedHashSet<>();
        for (Room r : roomMap.values()) {
            if (r.getBuildingId() != null) bIds.add(r.getBuildingId());
            if (r.getUnitId() != null) uIds.add(r.getUnitId());
            if (r.getFloorId() != null) fIds.add(r.getFloorId());
        }
        Map<Long, String> bName = bIds.isEmpty() ? Map.of()
                : buildingMapper.selectByIds(bIds).stream()
                .collect(Collectors.toMap(Building::getId, Building::getName, (a, b) -> a));
        Map<Long, String> uName = uIds.isEmpty() ? Map.of()
                : unitMapper.selectByIds(uIds).stream()
                .collect(Collectors.toMap(Unit::getId, Unit::getName, (a, b) -> a));
        Map<Long, Floor> floors = fIds.isEmpty() ? Map.of()
                : floorMapper.selectByIds(fIds).stream()
                .collect(Collectors.toMap(Floor::getId, f -> f, (a, b) -> a));
        Map<Long, String> labels = new LinkedHashMap<>();
        for (Long rid : roomIds) {
            Room r = roomMap.get(rid);
            if (r == null) {
                labels.put(rid, "房屋" + rid);
                continue;
            }
            labels.put(rid, com.property.mgmt.common.RoomPaths.format(
                    bName.get(r.getBuildingId()),
                    uName.get(r.getUnitId()),
                    com.property.mgmt.common.RoomPaths.floorLabel(floors.get(r.getFloorId())),
                    r.getRoomNo()));
        }
        return labels;
    }

    public Map<String, Object> platformList(Long communityId, int page, int pageSize) {
        AuthUser u = AuthContext.require();
        if (!u.isPlatformAdmin() && !"PLATFORM".equals(u.getIdentityType())) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "需要平台身份");
        }
        LambdaQueryWrapper<Vote> q = new LambdaQueryWrapper<Vote>().orderByDesc(Vote::getId);
        if (communityId != null) {
            q.eq(Vote::getCommunityId, communityId);
        }
        Page<Vote> p = voteMapper.selectPage(new Page<>(page, pageSize), q);
        List<Map<String, Object>> list = p.getRecords().stream().map(v -> {
            syncStatus(v);
            return summary(v);
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", p.getTotal());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return data;
    }

    /** 临近截止 = end_at 前 3 小时；每人至多一次（subscribe + todo）。 */
    private void refreshNearDeadline(Long communityId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowEnd = now.plusHours(3);
        List<Vote> near = voteMapper.selectList(new LambdaQueryWrapper<Vote>()
                .eq(Vote::getCommunityId, communityId)
                .gt(Vote::getEndAt, now)
                .le(Vote::getEndAt, windowEnd));
        for (Vote vote : near) {
            List<RoomOccupant> owners = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                    .eq(RoomOccupant::getCommunityId, communityId)
                    .eq(RoomOccupant::getResidentRole, "OWNER")
                    .eq(RoomOccupant::getStatus, "ACTIVE"));
            for (RoomOccupant o : owners) {
                long sent = subscribeNotifyLogMapper.selectCount(new LambdaQueryWrapper<SubscribeNotifyLog>()
                        .eq(SubscribeNotifyLog::getUserId, o.getUserId())
                        .eq(SubscribeNotifyLog::getBizType, "VOTE")
                        .eq(SubscribeNotifyLog::getBizId, vote.getId())
                        .eq(SubscribeNotifyLog::getScene, "VOTE_NEAR_DEADLINE"));
                if (sent > 0) {
                    continue;
                }
                todoNotifyService.createTodo(communityId, o.getUserId(), "VOTE_NEAR_DEADLINE",
                        "投票即将截止", vote.getTitle() + " 将在 3 小时内截止", "VOTE", vote.getId());
                todoNotifyService.skipSubscribe(communityId, o.getUserId(), "VOTE_NEAR_DEADLINE",
                        "VOTE", vote.getId(), "一阶段未接微信下发");
            }
        }
    }

    private void notifyOwners(Long communityId, Vote vote, String scene, String title, String content) {
        List<RoomOccupant> owners = roomOccupantMapper.selectList(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getCommunityId, communityId)
                .eq(RoomOccupant::getResidentRole, "OWNER")
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        Set<Long> seen = new HashSet<>();
        for (RoomOccupant o : owners) {
            if (!seen.add(o.getUserId())) {
                continue;
            }
            todoNotifyService.createTodo(communityId, o.getUserId(), scene, title, content, "VOTE", vote.getId());
            todoNotifyService.skipSubscribe(communityId, o.getUserId(), scene, "VOTE", vote.getId(), "一阶段未接微信下发");
        }
    }

    private void syncStatus(Vote vote) {
        String s = deriveStatus(vote.getStartAt(), vote.getEndAt(), LocalDateTime.now());
        if (!s.equals(vote.getStatus())) {
            vote.setStatus(s);
            vote.setUpdatedAt(LocalDateTime.now());
            voteMapper.updateById(vote);
        }
    }

    private static String deriveStatus(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        if (now.isBefore(start)) {
            return "ONGOING"; // 未开始也展示为窗口内可管理；严格可加 NOT_STARTED，产品用 ONGOING/ENDED
        }
        if (!now.isBefore(end)) {
            return "ENDED";
        }
        return "ONGOING";
    }

    private AuthUser requireOwner() {
        AuthUser u = AuthContext.require();
        // 住户或业委会身份均可投（业委会须同时为本小区业主）
        String type = u.getIdentityType();
        if (u.getCommunityId() == null
                || (!"RESIDENT".equals(type) && !"COMMITTEE".equals(type))) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "请先切换为住户身份");
        }
        requireOwnerInCommunity(u.getCommunityId());
        return u;
    }

    private void requireOwnerInCommunity(Long communityId) {
        AuthUser u = AuthContext.require();
        long cnt = roomOccupantMapper.selectCount(new LambdaQueryWrapper<RoomOccupant>()
                .eq(RoomOccupant::getUserId, u.getUserId())
                .eq(RoomOccupant::getCommunityId, communityId)
                .eq(RoomOccupant::getResidentRole, "OWNER")
                .eq(RoomOccupant::getStatus, "ACTIVE"));
        if (cnt == 0) {
            throw BizException.of(ErrorCodes.FORBIDDEN, "非本小区业主不可见");
        }
    }

    private Vote requireCommunityVote(Long id, Long communityId) {
        Vote vote = voteMapper.selectById(id);
        if (vote == null || !vote.getCommunityId().equals(communityId)) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "投票不存在");
        }
        return vote;
    }

    private List<VoteOption> optionsOf(Long voteId) {
        return voteOptionMapper.selectList(new LambdaQueryWrapper<VoteOption>()
                .eq(VoteOption::getVoteId, voteId)
                .orderByAsc(VoteOption::getSortNo));
    }

    private Map<String, Object> summary(Vote v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("communityId", v.getCommunityId());
        m.put("title", v.getTitle());
        m.put("startAt", v.getStartAt() != null ? v.getStartAt().toString() : null);
        m.put("endAt", v.getEndAt() != null ? v.getEndAt().toString() : null);
        m.put("status", v.getStatus());
        m.put("creatorRole", v.getCreatorRole());
        m.put("createdBy", v.getCreatedBy());
        return m;
    }

    private Map<String, Object> detail(Vote vote, List<VoteOption> options, List<VoteBallot> ballots) {
        Map<String, Object> m = summary(vote);
        m.put("background", vote.getBackground());
        List<Map<String, Object>> opts = new ArrayList<>();
        for (VoteOption o : options) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("id", o.getId());
            om.put("optionText", o.getOptionText());
            om.put("sortNo", o.getSortNo());
            opts.add(om);
        }
        m.put("options", opts);
        m.put("myBallots", ballots);
        return m;
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        String s = o.toString().trim();
        return s.isEmpty() ? null : Long.parseLong(s);
    }
}
