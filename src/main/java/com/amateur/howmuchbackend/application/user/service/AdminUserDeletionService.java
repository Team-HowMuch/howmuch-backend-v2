package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.port.in.user.DeleteUserAsAdminUseCase;
import com.amateur.howmuchbackend.application.port.in.user.SearchAdminUserUseCase;
import com.amateur.howmuchbackend.application.port.in.user.command.SearchAdminUserCommand;
import com.amateur.howmuchbackend.application.port.in.user.result.AdminUserSearchResult;
import com.amateur.howmuchbackend.application.port.out.auth.AuthAuditPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class AdminUserDeletionService implements SearchAdminUserUseCase, DeleteUserAsAdminUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final IdentityPolicy identityPolicy;
    private final UserAccountDeletionExecutor deletionExecutor;
    private final AuthAuditPort authAuditPort;

    @Override
    @Transactional(readOnly = true)
    public AdminUserSearchResult search(SearchAdminUserCommand command) {
        requireAdmin();
        String phoneNumber = identityPolicy.normalizePhoneNumber(command.phoneNumber());
        User target = loadUserPort.findByPhoneNumber(phoneNumber)
                .filter(this::deletableTarget)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        return AdminUserSearchResult.from(target);
    }

    @Override
    @Transactional
    public void delete(Long userId) {
        User admin = requireAdmin();
        User target = loadUserPort.findByIdForUpdate(userId)
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        if (target.role() == UserRole.ADMIN) {
            throw ApplicationException.of(CommonStatusCode.FORBIDDEN);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        deletionExecutor.delete(target, now);
        authAuditPort.recordAdminUserDeletion(admin.id(), target.id(), now);
    }

    private User requireAdmin() {
        CurrentActor actor = currentActorPort.getCurrentActor();
        User user = loadUserPort.findById(actor.userId())
                .orElseThrow(() -> ApplicationException.of(CommonStatusCode.FORBIDDEN));
        if (!user.active() || user.role() != UserRole.ADMIN) {
            throw ApplicationException.of(CommonStatusCode.FORBIDDEN);
        }
        return user;
    }

    private boolean deletableTarget(User user) {
        return user.active() && user.role() != UserRole.ADMIN;
    }
}
