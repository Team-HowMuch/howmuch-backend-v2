package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.settlement.service.SettlementBankTransactionFetchService;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Fetches independent accounts concurrently; callers alone commit the ordered results. */
@Component
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class NettingBankTransactionReader {
    public static final int MAX_PARALLEL_ACCOUNTS = 2;

    private final SettlementBankTransactionFetchService fetcher;
    private final ExecutorService executor;

    public NettingBankTransactionReader(
            SettlementBankTransactionFetchService fetcher,
            @Qualifier("nettingBankQueryExecutor") ExecutorService executor) {
        this.fetcher = fetcher;
        this.executor = executor;
    }

    public record Query(
            long accountId,
            String organization,
            String accountNumber,
            LocalDateTime from,
            Supplier<ExternalConnection> checkedConnection) {}

    public record Result(List<Transaction> transactions, RuntimeException failure) {
        public Result {
            transactions = List.copyOf(transactions);
        }

        public List<Transaction> transactionsOrThrow() {
            if (failure != null) throw failure;
            return transactions;
        }
    }

    public Map<Long, Result> fetch(List<Query> queries) {
        Map<Long, Result> results = new LinkedHashMap<>();
        for (int start = 0; start < queries.size(); start += MAX_PARALLEL_ACCOUNTS) {
            var window =
                    queries.subList(start, Math.min(start + MAX_PARALLEL_ACCOUNTS, queries.size()));
            var futures = new ArrayList<Future<Result>>();
            try {
                for (var query : window) futures.add(executor.submit(() -> fetchOne(query)));
                for (int i = 0; i < window.size(); i++) {
                    results.put(window.get(i).accountId(), futures.get(i).get());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE);
            } catch (RejectedExecutionException unavailable) {
                throw ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE);
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof Error error) throw error;
                throw ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE);
            } finally {
                for (var future : futures) if (!future.isDone()) future.cancel(true);
            }
            // Do not start later accounts once this window contains a failure. The caller
            // consumes completed results in its original order and preserves the real error.
            if (window.stream().anyMatch(query -> results.get(query.accountId()).failure() != null))
                break;
        }
        return results;
    }

    private Result fetchOne(Query query) {
        try {
            var transactions =
                    fetcher.fetch(
                            query.checkedConnection().get(),
                            query.accountId(),
                            query.organization(),
                            query.accountNumber(),
                            query.from(),
                            LocalDateTime.now(ZoneOffset.UTC),
                            () -> query.checkedConnection().get());
            return new Result(transactions, null);
        } catch (RuntimeException failure) {
            return new Result(List.of(), failure);
        }
    }
}
