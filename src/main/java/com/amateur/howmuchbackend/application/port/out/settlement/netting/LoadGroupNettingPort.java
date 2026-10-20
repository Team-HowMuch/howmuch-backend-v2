package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import java.time.LocalDateTime;
import java.util.*;

public interface LoadGroupNettingPort {
    record Netting(
            UUID id,
            long groupId,
            long version,
            String status,
            LocalDateTime createdAt,
            LocalDateTime completedAt,
            LocalDateTime supersededAt,
            List<SourceAmount> sources,
            List<Transfer> transfers,
            Set<Long> partyIds,
            int openReviews) {
        public com.amateur.howmuchbackend.domain.settlement.netting.GroupSettlementNetting
                aggregate() {
            return new com.amateur.howmuchbackend.domain.settlement.netting.GroupSettlementNetting(
                    id,
                    groupId,
                    version,
                    com.amateur.howmuchbackend.domain.settlement.netting.GroupSettlementNetting
                            .Status.valueOf(status),
                    createdAt);
        }
    }

    record SourceAmount(
            UUID settlementId,
            String title,
            String status,
            String requesterName,
            LocalDateTime requestedAt,
            String allocationType,
            long originalAmount,
            long actualDepositAmount,
            long replacedAmount,
            String storeName,
            int participantCount,
            int payableParticipantCount,
            int completedParticipantCount,
            Set<Long> debtPartyIds) {}

    record Transfer(
            UUID id,
            UUID nettingId,
            long payerId,
            long receiverId,
            long amount,
            long confirmedAmount,
            String reference,
            long accountId,
            String organization,
            String maskedNumber,
            LocalDateTime activatedAt,
            LocalDateTime confirmedAt) {
        public long remainingAmount() {
            return Math.subtractExact(amount, confirmedAmount);
        }
    }

    record ReceivingAccount(
            long userId,
            long accountId,
            long connectionId,
            String organization,
            String accountNumber,
            LocalDateTime activatedAt) {
        @Override
        public String toString() {
            return "ReceivingAccount[redacted]";
        }
    }

    record OriginalPaymentCandidate(
            UUID shareId, String payerName, long remainingAmount, LocalDateTime createdAt) {}

    List<OriginalPaymentCandidate> originalPaymentCandidates(
            UUID nettingId, long receiverId, long accountId);

    Optional<Netting> find(long groupId, UUID nettingId);

    List<UUID> findAll(long groupId);

    Optional<Netting> findById(UUID nettingId);

    Optional<Netting> findActive(long groupId);

    List<ReceivingAccount> monitoredAccounts(UUID nettingId);

    Optional<Netting> findBySource(UUID settlementId);

    List<ReceivingAccount> monitoredAccounts(UUID nettingId, long userId);

    ReceivingAccount receivingAccount(UUID nettingId, long receiverId);
}
