package com.property.mgmt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.domain.AuthApplication;
import com.property.mgmt.domain.FinanceEntry;
import com.property.mgmt.domain.RoomChangeApplication;
import com.property.mgmt.domain.ServiceTicket;
import com.property.mgmt.mapper.AuthApplicationMapper;
import com.property.mgmt.mapper.FinanceEntryMapper;
import com.property.mgmt.mapper.RoomChangeApplicationMapper;
import com.property.mgmt.mapper.ServiceTicketMapper;
import com.property.mgmt.security.StaffGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StaffNavBadgeService {

    private final AuthApplicationMapper authApplicationMapper;
    private final RoomChangeApplicationMapper roomChangeApplicationMapper;
    private final ServiceTicketMapper serviceTicketMapper;
    private final FinanceEntryMapper financeEntryMapper;

    public Map<String, Object> badges() {
        Long cid = StaffGuard.communityId();

        long authPending = authApplicationMapper.selectCount(new LambdaQueryWrapper<AuthApplication>()
                .eq(AuthApplication::getCommunityId, cid)
                .eq(AuthApplication::getStatus, "PENDING"));
        long roomChangePending = roomChangeApplicationMapper.selectCount(new LambdaQueryWrapper<RoomChangeApplication>()
                .eq(RoomChangeApplication::getCommunityId, cid)
                .eq(RoomChangeApplication::getStatus, "PENDING"));

        long repairOpen = serviceTicketMapper.selectCount(new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, "TICKET")
                .eq(ServiceTicket::getKind, "REPAIR")
                .eq(ServiceTicket::getCommunityId, cid)
                .in(ServiceTicket::getStatus, Set.of("PENDING_ASSIGN", "ASSIGNED", "IN_PROGRESS")));
        long complaintOpen = serviceTicketMapper.selectCount(new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getNodeType, "TICKET")
                .eq(ServiceTicket::getKind, "COMPLAINT")
                .eq(ServiceTicket::getCommunityId, cid)
                .in(ServiceTicket::getStatus, Set.of("PENDING", "PROCESSING")));

        long financePending = financeEntryMapper.selectCount(new LambdaQueryWrapper<FinanceEntry>()
                .eq(FinanceEntry::getCommunityId, cid)
                .eq(FinanceEntry::getStatus, "PENDING"));

        long occupants = authPending + roomChangePending;
        long serviceDesk = repairOpen + complaintOpen;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authPending", authPending);
        out.put("roomChangePending", roomChangePending);
        out.put("occupants", occupants);
        out.put("repairOpen", repairOpen);
        out.put("complaintOpen", complaintOpen);
        out.put("serviceDesk", serviceDesk);
        out.put("financePending", financePending);
        out.put("finance", financePending);
        return out;
    }
}
