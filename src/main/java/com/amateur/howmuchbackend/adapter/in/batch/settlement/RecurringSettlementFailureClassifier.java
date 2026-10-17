package com.amateur.howmuchbackend.adapter.in.batch.settlement;

import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.springframework.dao.TransientDataAccessException;

/** 일시적 DB 오류만 자동 재시도 대상으로 분류한다. 그 밖은 운영자 확인 대상이다. */
public final class RecurringSettlementFailureClassifier {
    private RecurringSettlementFailureClassifier() {
    }

    public static boolean transientDatabaseFailure(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof SQLTransientException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if (state.startsWith("08") || state.startsWith("40") || state.equals("55P03")) {
                    return true;
                }
            }
        }
        return false;
    }
}
