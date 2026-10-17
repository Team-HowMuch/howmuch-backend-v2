package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import com.amateur.howmuchbackend.domain.account.Transaction;

import java.time.LocalDateTime;
import java.util.*;

public interface NettingDepositStorePort {
    enum Credit {
        APPLIED,
        ALREADY_CREDITED,
        CONFLICT
    }

    record Review(
            UUID id,
            UUID nettingId,
            long receiverId,
            long transactionId,
            long accountId,
            String reason,
            String status,
            UUID conflictingClaimId,
            String proof,
            Long resolvedBy,
            LocalDateTime createdAt,
            LocalDateTime resolvedAt) {}

    Credit credit(
            LoadGroupNettingPort.Transfer transfer, Transaction transaction, LocalDateTime now);

    boolean alreadyCredited(UUID nettingId, String organization, String externalKey);

    void openReview(
            UUID nettingId,
            long receiverId,
            String organization,
            Transaction transaction,
            String reason,
            LocalDateTime now);

    boolean isReviewed(UUID nettingId, long transactionId);

    List<Review> findReviews(UUID nettingId);

    Optional<Review> findReview(UUID reviewId);

    void resolveUnrelatedOrReturned(
            Review review, String decision, long adminId, String proof, LocalDateTime now);

    void applyReviewed(
            Review review,
            LoadGroupNettingPort.Transfer transfer,
            long adminId,
            String proof,
            LocalDateTime now);
}
