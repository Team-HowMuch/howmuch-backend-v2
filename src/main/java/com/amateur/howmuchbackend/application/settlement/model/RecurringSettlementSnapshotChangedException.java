package com.amateur.howmuchbackend.application.settlement.model;

/** A template changed while the worker acquired its participant/account locks. Retry with fresh facts. */
public class RecurringSettlementSnapshotChangedException extends RuntimeException {
    public RecurringSettlementSnapshotChangedException() {
        super("Recurring settlement snapshot changed while acquiring locks");
    }
}
