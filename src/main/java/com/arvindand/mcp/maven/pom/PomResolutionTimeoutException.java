package com.arvindand.mcp.maven.pom;

/**
 * A POM resolution ran out of its time budget, or its thread was interrupted. Fetched parents and
 * BOMs stay cached, so a retry continues where this one stopped.
 *
 * @author Arvind Menon
 * @since 3.3.1
 */
public final class PomResolutionTimeoutException extends IllegalStateException {
  private static final long serialVersionUID = 1L;

  public PomResolutionTimeoutException() {
    super("POM resolution exceeded its time budget");
  }
}
