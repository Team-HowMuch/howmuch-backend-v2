package com.amateur.howmuchbackend.application.settlement.netting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.application.settlement.netting.service.NettingBankTransactionReader;
import com.amateur.howmuchbackend.application.settlement.service.SettlementBankTransactionFetchService;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class NettingBankTransactionReaderTest {
    final SettlementBankTransactionFetchService fetcher =
            mock(SettlementBankTransactionFetchService.class);
    final ExecutorService executor =
            Executors.newFixedThreadPool(NettingBankTransactionReader.MAX_PARALLEL_ACCOUNTS);
    final NettingBankTransactionReader reader = new NettingBankTransactionReader(fetcher, executor);

    @AfterEach
    void stop() throws Exception {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    NettingBankTransactionReader.Query query(long account) {
        var now = LocalDateTime.of(2026, 10, 19, 0, 0);
        return new NettingBankTransactionReader.Query(
                account,
                "0088",
                "test-" + account,
                now.minusDays(1),
                () -> new ExternalConnection(account, account, "test"));
    }

    @Test
    void independentAccountsOverlapAndNeverExceedTwo() {
        var firstPair = new CountDownLatch(2);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        when(fetcher.fetch(any(), anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                            try {
                                firstPair.countDown();
                                assertThat(firstPair.await(5, TimeUnit.SECONDS)).isTrue();
                                inv.<Runnable>getArgument(6).run();
                                return List.of();
                            } finally {
                                active.decrementAndGet();
                            }
                        });
        var result = reader.fetch(List.of(query(1), query(2), query(3), query(4), query(5)));
        assertThat(result.keySet()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(result.values()).allSatisfy(r -> assertThat(r.failure()).isNull());
        assertThat(peak).hasValue(2);
    }

    @Test
    void failureRemainsAnErrorAndPreventsLaterWindows() {
        var failure = ApplicationException.of(ExternalConnectionStatusCode.CODEF_RATE_LIMITED);
        when(fetcher.fetch(any(), anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            if (inv.<Long>getArgument(1) == 1L) throw failure;
                            return List.of();
                        });
        var result = reader.fetch(List.of(query(1), query(2), query(3)));
        assertThat(result.keySet()).containsExactly(1L, 2L);
        assertThatThrownBy(() -> result.get(1L).transactionsOrThrow()).isSameAs(failure);
        assertThat(result.get(2L).transactionsOrThrow()).isEmpty();
        verify(fetcher, never())
                .fetch(any(), eq(3L), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void rejectedSubmissionReturnsTheExistingTemporaryFailure() {
        executor.shutdownNow();
        assertThatThrownBy(() -> reader.fetch(List.of(query(1))))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        failure ->
                                assertThat(failure.getStatusCode())
                                        .isEqualTo(CommonStatusCode.TEMPORARILY_UNAVAILABLE));
        verifyNoInteractions(fetcher);
    }

    @Test
    void interruptedWaitCancelsOutstandingReadsAndKeepsInterruptFlag() throws Exception {
        var started = new CountDownLatch(2);
        var cancelled = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicReference<Boolean>();
        when(fetcher.fetch(any(), anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            started.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException expected) {
                                cancelled.countDown();
                                throw expected;
                            }
                            return List.of();
                        });
        Thread waiter =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        reader.fetch(List.of(query(1), query(2)));
                                    } catch (Throwable expected) {
                                        failure.set(expected);
                                        interrupted.set(Thread.currentThread().isInterrupted());
                                    }
                                });
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            waiter.interrupt();
            waiter.join(5000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(failure.get())
                    .isInstanceOfSatisfying(
                            ApplicationException.class,
                            error ->
                                    assertThat(error.getStatusCode())
                                            .isEqualTo(CommonStatusCode.TEMPORARILY_UNAVAILABLE));
            assertThat(interrupted.get()).isTrue();
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            waiter.interrupt();
            waiter.join(5000);
        }
    }
}
