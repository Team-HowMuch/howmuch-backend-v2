package com.amateur.howmuchbackend.infra.codef;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.adapter.in.web.external.AdditionalAuthenticationController;
import com.amateur.howmuchbackend.adapter.out.codef.*;
import com.amateur.howmuchbackend.adapter.out.security.CertificateSecretProtectionAdapter;
import com.amateur.howmuchbackend.application.certificate.service.*;
import com.amateur.howmuchbackend.application.external.service.*;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.application.port.out.external.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.*;

class AdditionalAuthenticationWiringTest {
    private ApplicationContextRunner runner() {
        var r =
                new ApplicationContextRunner()
                        .withUserConfiguration(
                                com.amateur.howmuchbackend.infra.codef.config.AdditionalAuthenticationConfiguration.class,
                                AdditionalAuthenticationService.class,
                                AdditionalAuthenticationExecutionService.class,
                                AdditionalAuthenticationWorker.class,
                                AdditionalAuthenticationController.class,
                                CodefSmsContinuationAdapter.class,
                                CertificateApprovedRegistrationTransactions.class,
                                CertificateRegistrationTransactions.class,
                                CertificatePairingService.class,
                                CertificateRegistrationService.class,
                                CertificateSecretProtectionAdapter.class)
                        .withPropertyValues("howmuch.certificate.enabled=true")
                        .withBean(
                                CertificateProperties.class,
                                () ->
                                        new CertificateProperties(
                                                true,
                                                "",
                                                Base64.getEncoder().encodeToString(new byte[32]),
                                                Base64.getEncoder().encodeToString(new byte[32]),
                                                List.of(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                true))
                        .withBean(Clock.class, Clock::systemUTC)
                        .withBean(ObjectMapper.class, ObjectMapper::new);
        return mockBoundary(
                r,
                CertificateSessionStorePort.class,
                CertificateConsentStorePort.class,
                CertificateUploadStorePort.class,
                CertificateCommandOutboxPort.class,
                CertificateCapabilityPort.class,
                CertificatePairingStorePort.class,
                CertificateClaimStorePort.class,
                CertificateExecutionStorePort.class,
                CertificateRequestLimitPort.class,
                CertificateInstitutionCatalogPort.class,
                CertificateProviderAccountPort.class,
                ExternalConnectionStorePort.class,
                BankConnectionPort.class,
                AdditionalAuthenticationStorePort.class,
                CurrentActorPort.class,
                LoadUserPort.class,
                CodefClient.class,
                FinanceAuthenticationCompletionService.class,
                com.amateur.howmuchbackend.application.notification.service.NotificationRecorder
                        .class,
                com.amateur.howmuchbackend.application.account.service
                        .AccountCredentialProtectionService.class,
                com.amateur.howmuchbackend.application.certificate.policy
                        .CertificateRegistrationPolicy.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApplicationContextRunner mockBoundary(ApplicationContextRunner r, Class<?>... types) {
        for (Class t : types) r = r.withBean(t, () -> mock(t));
        return r;
    }

    @Test
    void enabledFeatureWiresExistingCryptoAndCertificateServicesWithoutCycles() {
        runner().withPropertyValues("howmuch.codef.additional-auth.enabled=true")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasBean("financeAuthenticationScheduler")
                                    .hasSingleBean(AdditionalAuthenticationController.class)
                                    .hasSingleBean(CodefAdditionalAuthenticationPort.class)
                                    .hasSingleBean(AdditionalAuthenticationProtectorPort.class);
                        });
    }

    @Test
    void disabledFeaturePreservesCertificateServicesAndDoesNotExposeSmsApi() {
        runner().withPropertyValues("howmuch.codef.additional-auth.enabled=false")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(CertificateRegistrationService.class)
                                    .doesNotHaveBean("financeAuthenticationScheduler")
                                    .doesNotHaveBean(AdditionalAuthenticationController.class)
                                    .doesNotHaveBean(AdditionalAuthenticationWorker.class);
                        });
    }
}
