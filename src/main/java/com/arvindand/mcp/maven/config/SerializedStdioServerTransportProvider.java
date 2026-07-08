package com.arvindand.mcp.maven.config;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.util.List;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Serializes subscriptions to the Java MCP SDK's stdio response transport.
 *
 * <p>The SDK's stdio transport uses a Reactor unicast sink whose {@code tryEmitNext} operation
 * rejects concurrent emitters. Parallel tool calls can therefore lose a response with {@code Failed
 * to enqueue message}. The SDK issue is tracked upstream as modelcontextprotocol/java-sdk #686.
 * This provider keeps tool execution concurrent and serializes only the final response send.
 */
final class SerializedStdioServerTransportProvider implements McpServerTransportProvider {

  private final McpServerTransportProvider delegate;

  SerializedStdioServerTransportProvider(McpJsonMapper jsonMapper) {
    this(new StdioServerTransportProvider(jsonMapper));
  }

  SerializedStdioServerTransportProvider(McpServerTransportProvider delegate) {
    this.delegate = delegate;
  }

  @Override
  public void setSessionFactory(McpServerSession.Factory sessionFactory) {
    delegate.setSessionFactory(
        transport -> sessionFactory.create(new SerializedServerTransport(transport)));
  }

  @Override
  public Mono<Void> notifyClients(String method, Object params) {
    return delegate.notifyClients(method, params);
  }

  @Override
  public Mono<Void> notifyClient(String sessionId, String method, Object params) {
    return delegate.notifyClient(sessionId, method, params);
  }

  @Override
  public Mono<Void> closeGracefully() {
    return delegate.closeGracefully();
  }

  @Override
  public void close() {
    delegate.close();
  }

  @Override
  public List<String> protocolVersions() {
    return delegate.protocolVersions();
  }

  private static final class SerializedServerTransport implements McpServerTransport {

    private final McpServerTransport delegate;
    private final Scheduler sendScheduler = Schedulers.newSingle("mcp-stdio-send");

    private SerializedServerTransport(McpServerTransport delegate) {
      this.delegate = delegate;
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
      // sendMessage() is cold; subscribeOn ensures the delegate's tryEmitNext runs on one thread.
      return delegate.sendMessage(message).subscribeOn(sendScheduler);
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
      return delegate.unmarshalFrom(data, typeRef);
    }

    @Override
    public Mono<Void> closeGracefully() {
      return delegate.closeGracefully().doFinally(_ -> sendScheduler.dispose());
    }

    @Override
    public void close() {
      delegate.close();
      sendScheduler.dispose();
    }

    @Override
    public List<String> protocolVersions() {
      return delegate.protocolVersions();
    }
  }
}
