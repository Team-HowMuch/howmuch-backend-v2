package com.amateur.howmuchbackend.application.recap.policy;

import com.amateur.howmuchbackend.application.recap.model.RecapCompanion;
import com.amateur.howmuchbackend.application.recap.model.RecapSourceFact;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.amateur.howmuchbackend.application.recap.model.RecapCompanion.Kind.*;

@Component
public class RecapCompanionPolicy {
    private static final String WITHDRAWN = "탈퇴한 회원";
    private static final Comparator<RecapSourceFact> RECENT = Comparator
            .comparing(RecapSourceFact::completedAt)
            .thenComparing(fact -> fact.settlementId().toString())
            .thenComparing(fact -> fact.participantId().toString());

    public RecapCompanion select(Long ownerId, List<RecapSourceFact> facts) {
        Map<String, Candidate> candidates = new HashMap<>();
        for (RecapSourceFact fact : facts) {
            if (fact.participantId() == null || ownerId.equals(fact.linkedUserId())) {
                continue;
            }
            String key = fact.linkedUserId() == null
                    ? "GUEST:" + normalizeGuest(fact.participantName())
                    : "MEMBER:" + fact.linkedUserId();
            if (key.equals("GUEST:")) {
                throw new IllegalStateException("Blank guest name");
            }
            candidates.computeIfAbsent(key, Candidate::new).add(fact);
        }
        return candidates.values().stream().map(Candidate::result)
                .sorted(Comparator.comparingInt(RecapCompanion::completedSettlementCount).reversed()
                        .thenComparing(Comparator.comparingLong(RecapCompanion::totalOwnAmount).reversed())
                        .thenComparing(RecapCompanion::displayName)
                        .thenComparing(RecapCompanion::identityKey))
                .findFirst().orElse(null);
    }

    private static String normalizeGuest(String name) {
        StringBuilder value = new StringBuilder();
        name.codePoints().filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))
                .forEach(value::appendCodePoint);
        return value.toString().toLowerCase(Locale.ROOT);
    }

    public static String maskGuest(String name) {
        int[] points = name.codePoints().toArray();
        if (points.length <= 1) {
            return "*";
        }
        return new String(points, 0, 1) + "*".repeat(points.length - (points.length == 2 ? 1 : 2))
                + (points.length == 2 ? "" : new String(points, points.length - 1, 1));
    }

    private static final class Candidate {
        private final String key;
        private final Set<UUID> settlements = new HashSet<>();
        private long amount;
        private RecapSourceFact latest;

        private Candidate(String key) { this.key = key; }

        private void add(RecapSourceFact fact) {
            if (settlements.add(fact.settlementId())) {
                amount = Math.addExact(amount, fact.ownAmount() == null ? 0 : fact.ownAmount());
            }
            if (latest == null || RECENT.compare(fact, latest) > 0) {
                latest = fact;
            }
        }

        private RecapCompanion result() {
            if (latest.linkedUserId() == null) {
                return new RecapCompanion(GUEST, key, latest.participantName(),
                        maskGuest(latest.participantName()), null, settlements.size(), amount);
            }
            if (!latest.accountActive()) {
                return new RecapCompanion(WITHDRAWN_MEMBER, key, WITHDRAWN, WITHDRAWN,
                        null, settlements.size(), amount);
            }
            return new RecapCompanion(MEMBER, key, latest.accountName(), latest.publicId(),
                    latest.avatarFileName(), settlements.size(), amount);
        }
    }
}
