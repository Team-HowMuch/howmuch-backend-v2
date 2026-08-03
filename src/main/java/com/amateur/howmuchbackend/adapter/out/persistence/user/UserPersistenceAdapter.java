package com.amateur.howmuchbackend.adapter.out.persistence.user;

import com.amateur.howmuchbackend.application.port.out.user.UserRepositoryPort;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class UserPersistenceAdapter implements UserRepositoryPort {
    private final UserJpaRepository userJpaRepository;

    @Override
    public Optional<User> findById(Long id) {
        return userJpaRepository.findById(id).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByIdentityKey(String identityKey) {
        return userJpaRepository.findByIdentityKey(identityKey).map(UserMapper::toDomain);
    }

    @Override
    public User save(User user) {
        return UserMapper.toDomain(userJpaRepository.save(UserMapper.toEntity(user)));
    }

    @Override
    public User update(User user) {
        UserJpaEntity entity = userJpaRepository.findById(user.id())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + user.id()));
        entity.updateFrom(user);
        return UserMapper.toDomain(userJpaRepository.save(entity));
    }
}
