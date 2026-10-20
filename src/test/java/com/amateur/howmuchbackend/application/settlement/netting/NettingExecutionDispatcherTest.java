package com.amateur.howmuchbackend.application.settlement.netting;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettingExecutionFailurePolicy;
import com.amateur.howmuchbackend.application.settlement.netting.service.NettingExecutionDispatcher;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.*;
import java.util.*;

class NettingExecutionDispatcherTest {
    @ParameterizedTest
    @CsvSource({
        "CODEF_UPSTREAM_UNAVAILABLE, RETRY",
        "CODEF_RATE_LIMITED, RETRY",
        "BANK_CREDENTIAL_INVALID, FAILED",
        "CODEF_CONFIGURATION_INVALID, FAILED"
    })
    void launcherApplicationFailuresUseTheSameClassification(
            String code, NettingExecutionStorePort.Status expected) {
        var store = mock(NettingExecutionStorePort.class);
        var launcher = mock(NettingJobLauncherPort.class);
        UUID id = UUID.randomUUID(), token = UUID.randomUUID();
        var execution =
                new NettingExecutionStorePort.Execution(
                        id,
                        1,
                        null,
                        2,
                        NettingExecutionStorePort.Kind.CREATE,
                        NettingExecutionStorePort.Status.SYNCING,
                        token,
                        null,
                        LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        when(store.claimNext(any(), any())).thenReturn(Optional.of(execution));
        doThrow(ApplicationException.of(ExternalConnectionStatusCode.valueOf(code)))
                .when(launcher)
                .launch(id, token);
        new NettingExecutionDispatcher(store, launcher, new NettingExecutionFailurePolicy())
                .dispatch();
        verify(store).finish(eq(id), eq(token), eq(expected), isNull(), eq(code), any());
    }
}
