package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.service.MavenCentralService;
import java.io.IOException;
import java.io.StringReader;
import java.util.Optional;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link PomFetcher} that delegates raw XML retrieval to {@link MavenCentralService} and parses the
 * result with {@link MavenXpp3Reader}. Any fetch or parse failure becomes an empty {@link
 * Optional}; the resolver records it as a warning.
 *
 * @author Arvind Menon
 * @since 2.2.0
 */
@Component
public class MavenCentralPomFetcher implements PomFetcher {

  private static final Logger logger = LoggerFactory.getLogger(MavenCentralPomFetcher.class);
  private final MavenCentralService service;

  public MavenCentralPomFetcher(MavenCentralService service) {
    this.service = service;
  }

  @Override
  public Optional<Model> fetch(MavenCoordinate coordinate) {
    Optional<String> xml = service.fetchPomXml(coordinate);
    if (xml.isEmpty()) {
      return Optional.empty();
    }
    try {
      Model model = new MavenXpp3Reader().read(new StringReader(xml.get()));
      return Optional.of(model);
    } catch (XmlPullParserException | IOException ex) {
      logger.debug("POM parse failed for {}: {}", coordinate.toCoordinateString(), ex.getMessage());
      return Optional.empty();
    }
  }
}
