package com.amateur.howmuchbackend.adapter.out.link;

import com.amateur.howmuchbackend.application.port.out.group.GroupInviteLinkPort;
import com.amateur.howmuchbackend.infra.group.config.GroupInviteProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class GroupInviteLinkAdapter implements GroupInviteLinkPort {
    private final GroupInviteProperties properties;

    @Override
    public String create(String inviteCode) {
        return properties.baseUrl()
                + "?code="
                + URLEncoder.encode(inviteCode, StandardCharsets.UTF_8);
    }
}
