package io.github.pactproject.app.config;

import io.github.pactproject.api.PactState;
import io.github.pactproject.api.StateProvider;
import io.github.pactproject.app.ReconciliationQueue;
import io.github.pactproject.core.PactCore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationQueueTest
{
    @Test
    void reconciliationsAreExecutedSequentially()
    {
        var running = new AtomicInteger();
        var maxConcurrent = new AtomicInteger();

        StateProvider provider = () -> {
            int current = running.incrementAndGet();

            maxConcurrent.accumulateAndGet(
                    current,
                    Math::max
            );

            try {
                Thread.sleep(50);
                return PactState.empty();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            finally {
                running.decrementAndGet();
            }
        };

        PactCore core =
                new PactCore(List.of());

        try (var queue =
                     new ReconciliationQueue(
                             provider,
                             core
                     )) {

            var first = queue.submit();
            var second = queue.submit();
            var third = queue.submit();

            assertDoesNotThrow(first::join);
            assertDoesNotThrow(second::join);
            assertDoesNotThrow(third::join);

            assertEquals(
                    1,
                    maxConcurrent.get()
            );
        }
    }

    @Test
    void failureIsReturnedToCorrespondingFuture()
    {
        var expected =
                new RuntimeException("boom");

        StateProvider provider = () -> {
            throw expected;
        };

        PactCore core =
                new PactCore(List.of());

        try (var queue =
                     new ReconciliationQueue(
                             provider,
                             core
                     )) {

            var future = queue.submit();

            var exception = assertThrows(
                    CompletionException.class,
                    future::join
            );

            assertSame(
                    expected,
                    exception.getCause()
            );
        }
    }

    @Test
    void failureOfOneReconciliationDoesNotStopQueue()
    {
        var calls = new AtomicInteger();

        StateProvider provider = () -> {
            int call = calls.incrementAndGet();

            if (call == 1) {
                throw new RuntimeException("first failed");
            }

            return PactState.empty();
        };

        PactCore core =
                new PactCore(List.of());

        try (var queue =
                     new ReconciliationQueue(
                             provider,
                             core
                     )) {

            var first = queue.submit();
            var second = queue.submit();

            assertThrows(
                    CompletionException.class,
                    first::join
            );

            assertDoesNotThrow(
                    second::join
            );

            assertEquals(
                    2,
                    calls.get()
            );
        }
    }
}
