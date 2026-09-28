package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.port.in.user.DeleteMyAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.user.command.DeleteMyAccountCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class DeleteMyAccountService implements DeleteMyAccountUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final PinPolicy pinPolicy;
    private final PasswordEncoder passwordEncoder;
    private final UserAccountDeletionExecutor deletionExecutor;

    @Override
    @Transactional
    public void delete(DeleteMyAccountCommand command) {
        validatePin(command.pin());

        User user = loadUserPort.findByIdForUpdate(currentActorPort.getCurrentActor().userId())
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        if (!passwordEncoder.matches(command.pin(), user.pinHash())) {
            throw ApplicationException.of(UserStatusCode.USER_DELETION_PIN_MISMATCH);
        }

        deletionExecutor.delete(user, LocalDateTime.now(ZoneOffset.UTC));
    }

    private void validatePin(String pin) {
        if (!pinPolicy.isFormatValid(pin)) {
            throw ApplicationException.of(AuthStatusCode.INVALID_PIN_FORMAT);
        }
    }
}
