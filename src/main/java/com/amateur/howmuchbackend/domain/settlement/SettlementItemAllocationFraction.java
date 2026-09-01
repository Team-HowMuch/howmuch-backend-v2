package com.amateur.howmuchbackend.domain.settlement;

/** 품목 한 개에 대한 부담 비율. 실제 몫은 이 비율을 units번 가져간 것이다. */
public enum SettlementItemAllocationFraction {
    ONE(1, 12),
    HALF(2, 6),
    THIRD(3, 4),
    QUARTER(4, 3);

    /** 품목 한 개를 나누는 최소 단위 수. 품목의 총 단위는 여기에 수량을 곱한 값이다. */
    public static final int WHOLE_UNITS = 12;

    private final int denominator;
    private final int units;

    SettlementItemAllocationFraction(int denominator, int units) {
        this.denominator = denominator;
        this.units = units;
    }

    public int denominator() {
        return denominator;
    }

    public int units() {
        return units;
    }
}
