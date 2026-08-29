package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementShareLinkStorePort;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementLinkStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("진행 중인 정산 공유 링크 조회")
class SettlementShareLinkServiceTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final UUID LINK_ID = UUID.fromString("96174f8e-f043-4d1f-86b8-7237de1558a5");
    private static final Long CREATOR_ID = 1L;
    private static final String RAW_TOKEN = "r".repeat(43);
    private static final String REISSUED_RAW_TOKEN = "s".repeat(43);
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final String REISSUED_TOKEN_HASH = "b".repeat(64);
    private static final String URL = "https://howmuch.example/settlements/" + RAW_TOKEN;
    private static final String REISSUED_URL = "https://howmuch.example/settlements/" + REISSUED_RAW_TOKEN;

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SettlementShareLinkStorePort shareLinkStorePort;
    @Mock private SettlementLinkTokenPort linkTokenPort;

    private SettlementShareLinkService service;

    @BeforeEach
    void setUp() {
        service = new SettlementShareLinkService(
                currentActorPort,
                loadSettlementPort,
                shareLinkStorePort,
                linkTokenPort
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement()));
    }

    @Test
    @DisplayName("암호화 저장된 기존 토큰으로 같은 공유 URL을 복원한다")
    void restoresExistingShareLink() {
        SettlementLink link = activeLink(TOKEN_HASH);
        when(shareLinkStorePort.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(
                new SettlementShareLinkStorePort.StoredSettlementShareLink(link, RAW_TOKEN)
        ));
        when(linkTokenPort.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(linkTokenPort.url(RAW_TOKEN)).thenReturn(URL);

        var result = service.get(SETTLEMENT_ID);

        assertThat(result.url()).isEqualTo(URL);
        assertThat(result.expiresAt()).isEqualTo(link.expiresAt());
        verify(shareLinkStorePort, never()).replace(any(), any());
    }

    @Test
    @DisplayName("기존 hash만 있는 링크는 새 토큰으로 재발급하고 저장한다")
    void reissuesLegacyHashOnlyLink() {
        SettlementLink link = activeLink(TOKEN_HASH);
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(30);
        when(shareLinkStorePort.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(
                new SettlementShareLinkStorePort.StoredSettlementShareLink(link, null)
        ));
        when(linkTokenPort.issue(any())).thenReturn(
                new SettlementLinkTokenPort.IssuedSettlementLinkToken(
                        REISSUED_TOKEN_HASH,
                        REISSUED_RAW_TOKEN,
                        REISSUED_URL,
                        expiresAt
                )
        );

        var result = service.get(SETTLEMENT_ID);

        assertThat(result.url()).isEqualTo(REISSUED_URL);
        ArgumentCaptor<SettlementLink> linkCaptor = ArgumentCaptor.forClass(SettlementLink.class);
        verify(shareLinkStorePort).replace(
                linkCaptor.capture(),
                eq(REISSUED_RAW_TOKEN)
        );
        assertThat(linkCaptor.getValue().tokenHash()).isEqualTo(REISSUED_TOKEN_HASH);
    }

    private Settlement settlement() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                10_000,
                SettlementStatus.AWAITING_DEPOSIT,
                now,
                now
        );
    }

    private SettlementLink activeLink(String tokenHash) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new SettlementLink(
                LINK_ID,
                SETTLEMENT_ID,
                tokenHash,
                SettlementLinkStatus.ACTIVE,
                now.plusDays(30),
                now.minusDays(1),
                now.minusDays(1)
        );
    }
}
