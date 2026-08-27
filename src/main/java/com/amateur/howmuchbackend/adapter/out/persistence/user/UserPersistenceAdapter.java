package com.amateur.howmuchbackend.adapter.out.persistence.user;

import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.MatchContactUsersPort;
import com.amateur.howmuchbackend.application.port.out.user.SaveUserPort;
import com.amateur.howmuchbackend.application.port.out.user.SearchUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class UserPersistenceAdapter implements LoadUserPort, SaveUserPort, SearchUserPort, MatchContactUsersPort {
    private final UserJpaRepository userJpaRepository;

    @Override
    public Optional<User> findById(Long id) {
        return userJpaRepository.findById(id).map(UserMapper::toDomain);
    }

    @Override
    public List<User> findAllByIds(Collection<Long> ids) {
        return userJpaRepository.findAllById(ids)
                .stream()
                .map(UserMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<User> findByIdForUpdate(Long id) {
        return userJpaRepository.findByIdForUpdate(id).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByPhoneNumber(String phoneNumber) {
        return userJpaRepository.findByPhoneNumber(phoneNumber).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByIdentityKey(String identityKey) {
        return userJpaRepository.findByIdentityKey(identityKey).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByPublicId(String publicId) {
        return userJpaRepository.findByPublicId(publicId).map(UserMapper::toDomain);
    }

    @Override
    public boolean existsByPublicId(String publicId) {
        return userJpaRepository.existsByPublicId(publicId);
    }

    @Override
    public List<User> searchActiveByPublicIdPrefix(
            String publicIdPrefix,
            Collection<Long> excludedUserIds,
            int limit
    ) {
        PageRequest pageRequest = PageRequest.of(0, limit);
        List<UserJpaEntity> users = excludedUserIds.isEmpty()
                ? userJpaRepository.findByActiveTrueAndPublicIdStartingWithOrderByPublicIdAsc(
                        publicIdPrefix,
                        pageRequest
                )
                : userJpaRepository.findByActiveTrueAndPublicIdStartingWithAndIdNotInOrderByPublicIdAsc(
                        publicIdPrefix,
                        excludedUserIds,
                        pageRequest
                );
        return users.stream()
                .map(UserMapper::toDomain)
                .toList();
    }

    @Override
    public List<User> findActiveByIdentityKeys(Collection<String> identityKeys) {
        if (identityKeys.isEmpty()) {
            return List.of();
        }
        return userJpaRepository.findAllByActiveTrueAndIdentityKeyIn(identityKeys)
                .stream()
                .map(UserMapper::toDomain)
                .toList();
    }

    @Override
    public User save(User user) {
        try {
            return UserMapper.toDomain(userJpaRepository.saveAndFlush(UserMapper.toEntity(user)));
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraintViolation(exception);
        }
    }

    @Override
    public User update(User user) {
        UserJpaEntity entity = userJpaRepository.findById(user.id())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + user.id()));
        try {
            entity.updateFrom(user);
            return UserMapper.toDomain(userJpaRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraintViolation(exception);
        }
    }

    private RuntimeException translateConstraintViolation(DataIntegrityViolationException exception) {
        if (hasConstraint(exception, "uk_users_public_id")) {
            return ApplicationException.of(UserStatusCode.PUBLIC_ID_ALREADY_EXISTS, exception);
        }
        if (hasConstraint(exception, "uk_users_phone_number")
                || hasConstraint(exception, "uk_users_identity_key")) {
            return ApplicationException.of(AuthStatusCode.PHONE_NUMBER_ALREADY_REGISTERED, exception);
        }
        return exception;
    }

    private boolean hasConstraint(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintName.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            if (current.getMessage() != null && current.getMessage().contains(constraintName)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
