package com.property.mgmt.security;

import com.property.mgmt.mapper.CommitteeMemberMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CommitteeGuardBootstrap {
    private final CommitteeMemberMapper committeeMemberMapper;

    @PostConstruct
    public void init() {
        CommitteeGuard.init(committeeMemberMapper);
    }
}
