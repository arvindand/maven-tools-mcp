package com.arvindand.mcp.maven.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

/**
 * Represents the structure of maven-metadata.xml files found in Maven repositories.
 *
 * <p>Maven repositories contain metadata files that enable discovery and resolution operations. The
 * maven-metadata.xml file lists version information, timestamps, and other artifact details.
 *
 * @param groupId the Maven group identifier
 * @param artifactId the Maven artifact identifier
 * @param version the artifact version (for version-specific metadata)
 * @param versioning versioning information including available versions and timestamps
 * @author Arvind Menon
 * @since 1.4.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "metadata")
public record MavenMetadata(
    @JacksonXmlProperty(localName = "groupId") String groupId,
    @JacksonXmlProperty(localName = "artifactId") String artifactId,
    @JacksonXmlProperty(localName = "version") String version,
    @JacksonXmlProperty(localName = "versioning") VersioningInfo versioning) {

  /**
   * Contains versioning information for the artifact.
   *
   * @param latest the latest deployed version (release or snapshot)
   * @param release the latest release version (non-snapshot)
   * @param versions list of all available versions
   * @param lastUpdated timestamp when metadata was last updated (format: yyyyMMddHHmmss)
   * @param snapshot snapshot-specific information
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record VersioningInfo(
      @JacksonXmlProperty(localName = "latest") String latest,
      @JacksonXmlProperty(localName = "release") String release,
      @JacksonXmlProperty(localName = "versions") VersionList versions,
      @JacksonXmlProperty(localName = "lastUpdated") String lastUpdated,
      @JacksonXmlProperty(localName = "snapshot") SnapshotInfo snapshot) {

    /**
     * Gets all available versions as a list of strings.
     *
     * @return list of version strings, or empty list if none available
     */
    public List<String> getVersionStrings() {
      return versions != null && versions.versionList() != null
          ? versions.versionList()
          : List.of();
    }

    /**
     * Checks if this versioning info contains any version information.
     *
     * @return true if versions are available
     */
    public boolean hasVersions() {
      return !getVersionStrings().isEmpty();
    }
  }

  /**
   * Wrapper for the list of versions to handle XML parsing. Uses JacksonXmlElementWrapper to
   * properly handle multiple version elements.
   *
   * @param versionList the list of version strings
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record VersionList(
      @JacksonXmlProperty(localName = "version") @JacksonXmlElementWrapper(useWrapping = false)
          List<String> versionList) {}

  /**
   * Contains snapshot-specific versioning information.
   *
   * @param timestamp snapshot timestamp
   * @param buildNumber snapshot build number
   * @param localCopy whether this is a local copy
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SnapshotInfo(
      @JacksonXmlProperty(localName = "timestamp") String timestamp,
      @JacksonXmlProperty(localName = "buildNumber") String buildNumber,
      @JacksonXmlProperty(localName = "localCopy") Boolean localCopy) {}

  /**
   * Checks if this metadata contains valid versioning information.
   *
   * @return true if versioning info is available and contains versions
   */
  public boolean hasValidVersioning() {
    return versioning != null && versioning.hasVersions();
  }

  /**
   * Gets the artifact coordinate string for this metadata.
   *
   * @return coordinate string in format "groupId:artifactId"
   */
  public String getCoordinate() {
    return groupId + ":" + artifactId;
  }
}
