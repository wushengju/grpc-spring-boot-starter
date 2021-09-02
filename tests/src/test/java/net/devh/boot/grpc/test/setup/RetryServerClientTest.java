
package net.devh.boot.grpc.test.setup;

import static net.devh.boot.grpc.test.config.DynamicTestServiceConfig.errorWith;
import static net.devh.boot.grpc.test.config.DynamicTestServiceConfig.increment;
import static net.devh.boot.grpc.test.config.DynamicTestServiceConfig.respondWith;
import static net.devh.boot.grpc.test.util.GrpcAssertions.assertThrowsStatus;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.google.protobuf.Empty;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.test.config.BaseAutoConfiguration;
import net.devh.boot.grpc.test.config.DynamicTestServiceConfig;
import net.devh.boot.grpc.test.proto.SomeType;
import net.devh.boot.grpc.test.proto.TestServiceGrpc;
import net.devh.boot.grpc.test.proto.TestServiceGrpc.TestServiceBlockingStub;

@Slf4j
@SpringBootTest(properties = {
        "grpc.server.inProcessName=test",
        "grpc.server.port=-1",
        "grpc.client.GLOBAL.negotiationType=PLAINTEXT",
        "grpc.client.test.address=in-process:test",
        "grpc.client.GLOBAL.retry-enabled=true",
        "grpc.client.GLOBAL.method-config[0].name[0].service=" + TestServiceGrpc.SERVICE_NAME,
        "grpc.client.GLOBAL.method-config[0].name[0].method=", // all methods within that service
        "grpc.client.GLOBAL.method-config[0].retry-policy.max-attempts=2",
        "grpc.client.GLOBAL.method-config[0].retry-policy.initial-backoff=1200ms",
        "grpc.client.GLOBAL.method-config[0].retry-policy.max-backoff=1",
        "grpc.client.GLOBAL.method-config[0].retry-policy.backoff-multiplier=2",
        "grpc.client.GLOBAL.method-config[0].retry-policy.retryable-status-codes=UNKNOWN,UNAVAILABLE",
})
@SpringJUnitConfig(classes = {
        DynamicTestServiceConfig.class,
        BaseAutoConfiguration.class,
})
@DirtiesContext
class RetryServerClientTest {

    private static final Empty EMPTY = Empty.getDefaultInstance();

    @GrpcClient("test")
    TestServiceBlockingStub stub;

    @Autowired
    AtomicReference<BiConsumer<Empty, StreamObserver<SomeType>>> responseFunction;

    @Test
    void testRetryConfigSuccess() {
        final AtomicInteger counter = new AtomicInteger();

        this.responseFunction.set((request, observer) -> {
            log.info("Failing first request");
            this.responseFunction.set(increment(counter).andThen(respondWith("OK")));
            counter.incrementAndGet();
            errorWith(Status.UNAVAILABLE.withDescription("expected")).accept(request, observer);
        });

        final SomeType response = assertDoesNotThrow(() -> this.stub.normal(EMPTY));
        assertEquals("OK", response.getVersion());

        assertEquals(2, counter.get());
    }

    @Test
    void testRetryConfigFailedAttempts() {
        final AtomicInteger counter = new AtomicInteger();
        final Status expectedFailure = Status.UNAVAILABLE.withDescription("unexpected");

        this.responseFunction.set((request, observer) -> {
            log.info("Failing first request");
            this.responseFunction.set((request2, observer2) -> {
                log.info("Failing second request");
                this.responseFunction.set(increment(counter).andThen(respondWith("OK")));
                counter.incrementAndGet();
                errorWith(expectedFailure).accept(request2, observer2);
            });
            counter.incrementAndGet();
            errorWith(Status.UNAVAILABLE.withDescription("expected")).accept(request, observer);
        });

        assertThrowsStatus(expectedFailure, () -> this.stub.normal(EMPTY));

        assertEquals(2, counter.get());
    }

    @Test
    void testRetryConfigFailedStatus() {
        final AtomicInteger counter = new AtomicInteger();
        final Status expectedFailure = Status.UNAUTHENTICATED.withDescription("unexpected");

        this.responseFunction.set((request, observer) -> {
            log.info("Failing request");
            counter.incrementAndGet();
            errorWith(expectedFailure).accept(request, observer);
        });

        assertThrowsStatus(expectedFailure, () -> this.stub.normal(EMPTY));

        assertEquals(1, counter.get());
    }

}