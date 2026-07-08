package com.arvindand.mcp.maven.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SerializedStdioServerTransportProviderTest {

  @Test
  void serializesConcurrentResponseSends() {
    RecordingTransport delegateTransport = new RecordingTransport();
    CapturingProvider delegateProvider = new CapturingProvider(delegateTransport);
    var provider = new SerializedStdioServerTransportProvider(delegateProvider);
    provider.setSessionFactory(
        transport -> {
          delegateProvider.exposedTransport = transport;
          return new McpServerSession(
              "test", Duration.ofSeconds(5), transport, _ -> Mono.empty(), Map.of(), Map.of());
        });

    McpSchema.JSONRPCMessage message = new McpSchema.JSONRPCNotification("test");
    Flux.range(0, 100)
        .flatMap(_ -> delegateProvider.exposedTransport.sendMessage(message), 100)
        .blockLast(Duration.ofSeconds(5));

    assertThat(delegateTransport.sent).hasSize(100);
    assertThat(delegateTransport.maxConcurrentSends).hasValue(1);
  }

  private static final class CapturingProvider implements McpServerTransportProvider {

    private final McpServerTransport transport;
    private McpServerTransport exposedTransport;

    private CapturingProvider(McpServerTransport transport) {
      this.transport = transport;
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
      sessionFactory.create(transport);
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
      return Mono.empty();
    }

    @Override
    public Mono<Void> closeGracefully() {
      return Mono.empty();
    }
  }

  private static final class RecordingTransport implements McpServerTransport {

    private final CopyOnWriteArrayList<McpSchema.JSONRPCMessage> sent =
        new CopyOnWriteArrayList<>();
    private final AtomicInteger concurrentSends = new AtomicInteger();
    private final AtomicInteger maxConcurrentSends = new AtomicInteger();

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
      return Mono.fromRunnable(
          () -> {
            int current = concurrentSends.incrementAndGet();
            maxConcurrentSends.accumulateAndGet(current, Math::max);
            try {
              LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
              sent.add(message);
            } finally {
              concurrentSends.decrementAndGet();
            }
          });
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Mono<Void> closeGracefully() {
      return Mono.empty();
    }
  }
}
