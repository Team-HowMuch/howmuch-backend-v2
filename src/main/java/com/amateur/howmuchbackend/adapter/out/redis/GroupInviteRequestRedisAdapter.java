package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.port.out.group.TrackGroupInviteRequestPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class GroupInviteRequestRedisAdapter implements TrackGroupInviteRequestPort {
    private static final RedisScript<Long> RECORD_REQUEST = RedisScript.of(
            new ClassPathResource("redis/group-invite-record-request.lua"), Long.class
    );
    private final StringRedisTemplate redis;

    @Override
    public long recordRequest(String clientAddress) {
        Long count;
        try {
            count = redis.execute(RECORD_REQUEST, List.of("group:invite:requests:" + clientAddress));
        } catch (DataAccessException exception) {
            throw ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE, exception);
        }
        if (count == null) {
            throw ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE);
        }
        return count;
    }
}
