package com.arvindand.mcp.maven.pom;

import java.io.StringReader;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Checks untrusted POM documents before Maven parses their object graphs.
 *
 * @author Arvind Menon
 * @since 3.2.2
 */
public final class PomInputLimits {
  public static final int MAX_DOCUMENT_CHARS = 1_048_576;
  public static final int MAX_MODELS = 64;

  private PomInputLimits() {}

  /**
   * Rejects oversized bundles before parsing any member.
   *
   * @param documents sideloaded or primary POMs
   * @throws IllegalArgumentException when the bundle exceeds its budget
   */
  public static void checkBundle(List<String> documents) {
    if (documents.size() > MAX_MODELS
        || documents.stream().mapToLong(String::length).sum() > 4L * MAX_DOCUMENT_CHARS) {
      throw new IllegalArgumentException("POM bundle exceeds 64 models or 4 MiB of characters");
    }
  }

  /**
   * Rejects DTDs, excessive nesting and documents larger than one MiB of characters.
   *
   * @param xml untrusted XML content
   * @throws IllegalArgumentException when the document is invalid or exceeds a budget
   */
  public static void check(String xml) {
    if (xml == null || xml.length() > MAX_DOCUMENT_CHARS) {
      throw new IllegalArgumentException("POM exceeds one MiB of characters");
    }
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try {
      XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
      try {
        checkStructure(reader);
      } finally {
        reader.close();
      }
    } catch (XMLStreamException ex) {
      throw new IllegalArgumentException("Input is not valid POM XML", ex);
    }
  }

  private static void checkStructure(XMLStreamReader reader) throws XMLStreamException {
    int depth = 0;
    int elements = 0;
    while (reader.hasNext()) {
      switch (reader.next()) {
        case XMLStreamConstants.DTD ->
            throw new IllegalArgumentException("POM DTDs are unsupported");
        case XMLStreamConstants.START_ELEMENT -> {
          if (++depth > 64 || ++elements > 20_000) {
            throw new IllegalArgumentException("POM exceeds its XML depth or element budget");
          }
        }
        case XMLStreamConstants.END_ELEMENT -> depth--;
        default -> {}
      }
    }
  }
}
