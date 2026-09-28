package com.property.mgmt.config;

import com.property.mgmt.service.CommitteeService;
import com.property.mgmt.service.RoomOccupantBindService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时清理存量脏数据：非业主业委会、同房同人多条 ACTIVE 住户绑定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommitteeOwnerPurgeRunner implements ApplicationRunner {

    private final CommitteeService committeeService;
    private final RoomOccupantBindService roomOccupantBindService;

    @Override
    public void run(ApplicationArguments args) {
        int dups = roomOccupantBindService.dedupeActiveOccupants();
        if (dups > 0) {
            log.warn("Deactivated {} duplicate ACTIVE room_occupant row(s)", dups);
        }
        int n = committeeService.purgeNonOwnerCommittees();
        if (n > 0) {
            log.warn("Purged {} non-owner committee membership(s)", n);
        }
    }
}
