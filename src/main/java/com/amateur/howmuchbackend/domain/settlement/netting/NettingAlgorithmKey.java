package com.amateur.howmuchbackend.domain.settlement.netting;

/** A calculator version is immutable once a preview has been issued. */
public record NettingAlgorithmKey(String name, int version) {
    public NettingAlgorithmKey {
        if (name == null || !name.matches("[A-Z][A-Z0-9_]{0,63}") || version <= 0) {
            throw new IllegalArgumentException("Invalid netting algorithm key");
        }
    }

    public String identifier() {
        return name + "_V" + version;
    }
}
