package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

public enum SettlementItemAllocationFraction {
    ONE(1, 1, 12),
    HALF(1, 2, 6),
    THIRD(1, 3, 4),
    QUARTER(1, 4, 3);

    public static final int WHOLE_UNITS = 12;

    private final int numerator;
    private final int denominator;
    private final int units;

    SettlementItemAllocationFraction(int numerator, int denominator, int units) {
        this.numerator = numerator;
        this.denominator = denominator;
        this.units = units;
    }

    public long amountOf(long itemAmount) {
        if (itemAmount <= 0 || itemAmount % denominator != 0) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_ITEM_ALLOCATION_AMOUNT_NOT_EXACT
            );
        }
        try {
            return Math.multiplyExact(itemAmount / denominator, numerator);
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_ITEM_AMOUNT,
                    exception
            );
        }
    }

    public int numerator() {
        return numerator;
    }

    public int denominator() {
        return denominator;
    }

    public int units() {
        return units;
    }
}
