package com.arvindand.mcp.maven.util;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * Ensures chunked response bodies cannot bypass the byte limit.
 *
 * @author Arvind Menon
 * @since 3.2.2
 */
class BoundedResponseInterceptorTest {
  @Test
  void enforcesTheLimitWithoutContentLength() throws IOException {
    MockClientHttpResponse response =
        new MockClientHttpResponse(
            new byte[BoundedResponseInterceptor.MAX_RESPONSE_BYTES + 1], HttpStatus.OK);
    ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
    when(execution.execute(any(), any())).thenReturn(response);
    try (ClientHttpResponse bounded =
        new BoundedResponseInterceptor()
            .intercept(new MockClientHttpRequest(), new byte[0], execution)) {
      InputStream body = bounded.getBody();
      assertThatThrownBy(body::readAllBytes)
          .isInstanceOf(IOException.class)
          .hasMessageContaining("byte limit");
    }
  }
}
