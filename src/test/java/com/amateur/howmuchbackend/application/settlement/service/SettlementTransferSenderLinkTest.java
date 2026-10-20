package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.adapter.out.transfer.MockSettlementTransferLinkAdapter;
import com.amateur.howmuchbackend.adapter.out.transfer.TossSettlementTransferLinkAdapter;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.out.settlement.*;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingTransferAvailabilityPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementTransferSenderLinkTest {
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final UUID PAYER_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 0, 0);
    @Mock private LoadSettlementPort settlements;
    @Mock private LoadSettlementSharePort shares;
    @Mock private LoadSettlementDepositSummaryPort deposits;
    @Mock private LoadSettlementReceivingAccountSnapshotPort snapshots;
    @Mock private LoadSettlementParticipantPort participants;
    @Mock private BankCatalog banks;
    @Mock private NettingTransferAvailabilityPort availability;

    private void arrangePayableSettlement() {
        when(settlements.findById(SETTLEMENT_ID)).thenReturn(Optional.of(new Settlement(
                SETTLEMENT_ID, 1L, "정산", 10_000, SettlementAllocationType.DUTCH_PAY,
                7L, SettlementStatus.AWAITING_DEPOSIT, NOW, NOW)));
        when(shares.findBySettlementIdAndPayerParticipantId(SETTLEMENT_ID, PAYER_ID))
                .thenReturn(Optional.of(new SettlementShare(UUID.randomUUID(), SETTLEMENT_ID, PAYER_ID, 6_000, NOW)));
        when(snapshots.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(
                new SettlementReceivingAccountSnapshot(SETTLEMENT_ID, 7L, "0020", "1002123456789", "받는 사람", NOW)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"howmuch-mock://send", "https://howmuch-mock-send.vercel.app/send"})
    void selectedParticipantName_reachesRealMockUrlAndChangesWithParticipant(String baseUrl) {
        arrangePayableSettlement();
        when(banks.findBankName("0020")).thenReturn(Optional.of("우리은행"));
        var reader = reader(new MockSettlementTransferLinkAdapter(banks, baseUrl));
        for (String name : List.of("김 송금&amount=999+가", "다른 참여자")) {
            when(participants.findBySettlementIdAndId(SETTLEMENT_ID, PAYER_ID))
                    .thenReturn(Optional.of(new SettlementParticipant(PAYER_ID, SETTLEMENT_ID,
                            SettlementParticipantSource.CONTACT, null, null, null, name, 0, NOW, NOW)));

            var result = reader.read(SETTLEMENT_ID, PAYER_ID);
            String url = result.transferLinks().getFirst().url();
            var query = Arrays.stream(URI.create(url).getRawQuery().split("&"))
                    .map(part -> part.split("=", 2))
                    .collect(Collectors.toMap(part -> part[0],
                            part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
            assertThat(url).startsWith(baseUrl + "?");
            assertThat(query).hasSize(6)
                    .containsEntry("senderName", name)
                    .containsEntry("receiver", "받는 사람")
                    .containsEntry("amount", "6000");
        }
    }

    @Test
    void missingParticipant_doesNotReturnAnIncompleteTransferLink() {
        arrangePayableSettlement();
        assertThatThrownBy(() -> reader(new MockSettlementTransferLinkAdapter(banks, "howmuch-mock://send"))
                .read(SETTLEMENT_ID, PAYER_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND));
    }

    @Test
    void realToss_ignoresOptionalSenderAndKeepsExistingUrl() {
        when(banks.findTossDeepLinkBankName("0020")).thenReturn(Optional.of("우리"));
        var toss = new TossSettlementTransferLinkAdapter(banks);
        assertThat(toss.create("0020", "1002123456789", 6_000, "받는 사람", "보내는 사람"))
                .isEqualTo(toss.create("0020", "1002123456789", 6_000, "받는 사람"));
    }

    private SettlementTransferInstructionReader reader(SettlementTransferLinkPort links) {
        return new SettlementTransferInstructionReader(
                settlements, shares, deposits, snapshots, participants, List.of(links), banks, availability);
    }
}
