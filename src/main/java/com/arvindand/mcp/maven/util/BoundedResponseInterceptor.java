package com.arvindand.mcp.maven.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Bounds downloaded documents before converters allocate their complete bodies.
 *
 * @author Arvind Menon
 * @since 3.2.2
 */
public final class BoundedResponseInterceptor implements ClientHttpRequestInterceptor {
  public static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    ClientHttpResponse response = execution.execute(request, body);
    if (response.getStatusCode().is3xxRedirection()) {
      response.close();
      throw new IOException(
          "Repository/API redirects are not supported; configure the final endpoint");
    }
    if (response.getHeaders().getContentLength() > MAX_RESPONSE_BYTES) {
      response.close();
      throw new IOException("Repository/API response exceeds byte limit");
    }
    return new ClientHttpResponse() {
      private InputStream bounded;

      @Override
      public HttpStatusCode getStatusCode() throws IOException {
        return response.getStatusCode();
      }

      @Override
      public String getStatusText() throws IOException {
        return response.getStatusText();
      }

      @Override
      public HttpHeaders getHeaders() {
        return response.getHeaders();
      }

      @Override
      public void close() {
        response.close();
      }

      @Override
      public InputStream getBody() throws IOException {
        if (bounded == null) bounded = new LimitedStream(response.getBody());
        return bounded;
      }
    };
  }

  private static final class LimitedStream extends FilterInputStream {
    private int remaining = MAX_RESPONSE_BYTES;

    LimitedStream(InputStream source) {
      super(source);
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value >= 0) consume(1);
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int count = in.read(bytes, offset, Math.min(length, remaining + 1));
      if (count > 0) consume(count);
      return count;
    }

    @Override
    public long skip(long count) throws IOException {
      long skipped = in.skip(Math.min(count, remaining + 1L));
      consume((int) skipped);
      return skipped;
    }

    private void consume(int count) throws IOException {
      remaining -= count;
      if (remaining < 0) throw new IOException("Repository/API response exceeds byte limit");
    }
  }
}
