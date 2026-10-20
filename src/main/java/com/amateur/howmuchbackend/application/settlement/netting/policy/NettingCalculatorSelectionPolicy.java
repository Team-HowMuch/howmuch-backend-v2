package com.amateur.howmuchbackend.application.settlement.netting.policy;

import com.amateur.howmuchbackend.domain.settlement.netting.NettingAlgorithmKey;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class NettingCalculatorSelectionPolicy {
    public static final int VERSION = 3;
    public static final int LEGACY_VERSION = 2;

    public record Candidate(NettingAlgorithmKey key, int priority) {}

    /**
     * Higher priority first; ties use name and descending version, never Bean registration order.
     */
    public Optional<NettingAlgorithmKey> select(List<Candidate> supported) {
        return supported.stream()
                .sorted(
                        Comparator.comparingInt(Candidate::priority)
                                .reversed()
                                .thenComparing(c -> c.key().name())
                                .thenComparing(
                                        Comparator.comparingInt((Candidate c) -> c.key().version())
                                                .reversed()))
                .map(Candidate::key)
                .findFirst();
    }
}
