package com.property.mgmt.security;

import com.property.mgmt.mapper.StaffCommunityMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StaffGuardBootstrap {

    private final StaffCommunityMapper staffCommunityMapper;

    @PostConstruct
    public void init() {
        StaffGuard.init(staffCommunityMapper);
    }
}
