package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import java.util.Optional;
import org.apache.maven.model.Model;

/**
 * Resolves a Maven coordinate to a parsed {@link Model}. Implementations are responsible for
 * fetching the POM XML (typically from Maven Central) and parsing it. Returns an empty {@link
 * Optional} for any coordinate that cannot be fetched or parsed — callers (the resolver) record
 * this as a warning rather than an error.
 *
 * @author Arvind Menon
 * @since 2.2.0
 */
public interface PomFetcher {

  Optional<Model> fetch(MavenCoordinate coordinate);
}
