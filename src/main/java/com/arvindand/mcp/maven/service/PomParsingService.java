package com.arvindand.mcp.maven.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Service for parsing POM XML content and extracting dependency information. Provides methods to
 * extract Maven dependencies from POM file content with proper error handling.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
@Service
public class PomParsingService {

  /**
   * Extracts dependencies from POM XML content.
   *
   * @param pomContent the XML content of a pom.xml file
   * @return list of dependency coordinates in groupId:artifactId:version format
   */
  public List<String> extractDependenciesFromPom(String pomContent) {
    List<String> dependencies = new ArrayList<>();
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // Disable external entities for security
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);

      DocumentBuilder builder = factory.newDocumentBuilder();
      Document doc =
          builder.parse(new ByteArrayInputStream(pomContent.getBytes(StandardCharsets.UTF_8)));

      NodeList dependencyNodes = doc.getElementsByTagName("dependency");

      for (int i = 0; i < dependencyNodes.getLength(); i++) {
        Element dependencyElement = (Element) dependencyNodes.item(i);

        String groupId = getElementText(dependencyElement, "groupId");
        String artifactId = getElementText(dependencyElement, "artifactId");
        String version = getElementText(dependencyElement, "version");

        if (groupId != null && artifactId != null) {
          String dependency = groupId + ":" + artifactId;
          // Only include version if it's not a property reference
          if (version != null && !version.startsWith("${")) {
            dependency += ":" + version;
          }
          dependencies.add(dependency);
        }
      }
    } catch (IOException | ParserConfigurationException | SAXException _) {
      // Fallback to regex-based extraction if XML parsing fails
      return extractDependenciesWithRegex(pomContent);
    }

    return dependencies;
  }

  /**
   * Gets the text content of a child element with the specified tag name.
   *
   * @param parent the parent element to search in
   * @param tagName the tag name to search for
   * @return the text content of the element, or null if not found
   */
  private String getElementText(Element parent, String tagName) {
    NodeList nodes = parent.getElementsByTagName(tagName);
    if (nodes.getLength() > 0) {
      return nodes.item(0).getTextContent().trim();
    }
    return null;
  }

  /**
   * Fallback method to extract dependencies using regex when XML parsing fails.
   *
   * @param pomContent the POM file content as string
   * @return list of extracted dependency coordinates
   */
  private List<String> extractDependenciesWithRegex(String pomContent) {
    List<String> dependencies = new ArrayList<>();

    java.util.regex.Pattern dependencyPattern =
        java.util.regex.Pattern.compile(
            "<dependency>.*?<groupId>(.*?)</groupId>.*?<artifactId>(.*?)</artifactId>.*?(?:<version>(.*?)</version>)?.*?</dependency>",
            java.util.regex.Pattern.DOTALL);

    java.util.regex.Matcher matcher = dependencyPattern.matcher(pomContent);
    while (matcher.find()) {
      String groupId = matcher.group(1);
      String artifactId = matcher.group(2);
      String version = matcher.group(3);

      String dependency = groupId + ":" + artifactId;
      if (version != null && !version.startsWith("${")) {
        dependency += ":" + version;
      }
      dependencies.add(dependency);
    }

    return dependencies;
  }
}
