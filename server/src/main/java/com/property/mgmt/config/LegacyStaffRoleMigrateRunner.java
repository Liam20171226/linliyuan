package com.property.mgmt.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.property.mgmt.common.StaffRoles;
import com.property.mgmt.domain.StaffCommunity;
import com.property.mgmt.mapper.StaffCommunityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史「物业管理员」({@link StaffRoles#LEGACY_ADMIN}) 一律迁为客服 ({@link StaffRoles#CUSTOMER_SERVICE})。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyStaffRoleMigrateRunner implements ApplicationRunner {

    private final StaffCommunityMapper staffCommunityMapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<StaffCommunity> legacy = staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getStaffRole, StaffRoles.LEGACY_ADMIN));
        if (legacy.isEmpty()) {
            return;
        }
        int migrated = 0;
        int mergedAway = 0;
        LocalDateTime now = LocalDateTime.now();
        for (StaffCommunity sc : legacy) {
            StaffCommunity existingCs = staffCommunityMapper.selectOne(new LambdaQueryWrapper<StaffCommunity>()
                    .eq(StaffCommunity::getCommunityId, sc.getCommunityId())
                    .eq(StaffCommunity::getUserId, sc.getUserId())
                    .eq(StaffCommunity::getStaffRole, StaffRoles.CUSTOMER_SERVICE));
            if (existingCs != null) {
                if ("ACTIVE".equals(sc.getStatus()) && !"ACTIVE".equals(existingCs.getStatus())) {
                    existingCs.setStatus("ACTIVE");
                    existingCs.setUpdatedAt(now);
                    staffCommunityMapper.updateById(existingCs);
                }
                staffCommunityMapper.deleteById(sc.getId());
                mergedAway++;
            } else {
                sc.setStaffRole(StaffRoles.CUSTOMER_SERVICE);
                sc.setUpdatedAt(now);
                staffCommunityMapper.updateById(sc);
                migrated++;
            }
        }
        log.warn("Migrated legacy PROPERTY_ADMIN→CUSTOMER_SERVICE: updated={}, mergedAway={}",
                migrated, mergedAway);
    }
}
