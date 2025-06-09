package com.arvindand.mcp.maven.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Comprehensive unit tests for VersionComparator.
 *
 * @author Arvind Menon
 * @since 0.1.0
 */
class VersionComparatorTest {

  private VersionComparator versionComparator;

  @BeforeEach
  @SuppressWarnings("unused")
  void setUp() {
    versionComparator = new VersionComparator();
  }

  /** Test data for version comparison. */
  @SuppressWarnings("unused")
  private static Stream<Arguments> versionComparisonTestData() {
    return Stream.of(
        // Basic numeric comparisons
        Arguments.of("1.0.0", "2.0.0", -1, "1.0.0 < 2.0.0"),
        Arguments.of("2.0.0", "1.0.0", 1, "2.0.0 > 1.0.0"),
        Arguments.of("1.0.0", "1.0.0", 0, "1.0.0 = 1.0.0"),

        // Minor version comparisons
        Arguments.of("1.0.0", "1.1.0", -1, "1.0.0 < 1.1.0"),
        Arguments.of("1.5.0", "1.2.0", 1, "1.5.0 > 1.2.0"),

        // Patch version comparisons
        Arguments.of("1.0.0", "1.0.1", -1, "1.0.0 < 1.0.1"),
        Arguments.of("1.0.5", "1.0.2", 1, "1.0.5 > 1.0.2"),

        // Different length versions - should be treated as equal
        Arguments.of("1.0", "1.0.0", 0, "1.0 = 1.0.0 (equivalent versions)"),
        Arguments.of("1.0.0", "1.0", 0, "1.0.0 = 1.0 (equivalent versions)"),
        Arguments.of("1", "1.0.0", 0, "1 = 1.0.0 (equivalent versions)"),
        Arguments.of("1.0", "1.0.1", -1, "1.0 < 1.0.1"),
        Arguments.of("1.1", "1.0.1", 1, "1.1 > 1.0.1"),

        // Large numbers
        Arguments.of("10.0.0", "9.9.9", 1, "10.0.0 > 9.9.9"),
        Arguments.of("1.10.0", "1.9.0", 1, "1.10.0 > 1.9.0"),
        Arguments.of("1.0.10", "1.0.9", 1, "1.0.10 > 1.0.9"),

        // Multi-digit versions
        Arguments.of("1.2.3", "1.2.10", -1, "1.2.3 < 1.2.10"),
        Arguments.of(
            "11.0.0",
            "2.0.0",
            1,
            "11.0.0 > 2.0.0"), // Qualifier comparisons (release > rc > milestone > beta > alpha)
        Arguments.of("1.0.0", "1.0.0-RC1", 1, "release > rc"),
        Arguments.of("1.0.0-RC1", "1.0.0-M1", 1, "rc > milestone"),
        Arguments.of("1.0.0-M1", "1.0.0-BETA1", 1, "milestone > beta"),
        Arguments.of("1.0.0-BETA1", "1.0.0-ALPHA1", 1, "beta > alpha"),

        // Same qualifier with different numbers
        Arguments.of("1.0.0-RC1", "1.0.0-RC2", -1, "RC1 < RC2"),
        Arguments.of("1.0.0-BETA2", "1.0.0-BETA1", 1, "BETA2 > BETA1"),
        Arguments.of("1.0.0-M2", "1.0.0-M1", 1, "M2 > M1"), // Different separators
        Arguments.of("1.0.0", "1-0-0", 0, "Different separators treated equally"),
        Arguments.of("1.0.0", "1_0_0", -1, "Underscore separators (Maven treats differently)"),
        Arguments.of(
            "1.0.0-RC1",
            "1.0.0.RC1",
            0,
            "Different qualifier separators (Maven treats as equivalent)"),

        // Case insensitive qualifiers
        Arguments.of("1.0.0-rc1", "1.0.0-RC1", 0, "Case insensitive RC"),
        Arguments.of("1.0.0-beta", "1.0.0-BETA", 0, "Case insensitive BETA"),
        Arguments.of("1.0.0-alpha", "1.0.0-ALPHA", 0, "Case insensitive ALPHA"),

        // Special qualifiers - Maven's official behavior
        Arguments.of("1.0.0.RELEASE", "1.0.0", 0, "RELEASE qualifier equivalent to plain"),
        Arguments.of("1.0.0.Final", "1.0.0", 0, "Final qualifier equivalent to plain"),
        Arguments.of("1.0.0.GA", "1.0.0", 0, "GA qualifier equivalent to plain"),

        // Complex real-world examples
        Arguments.of("3.2.0", "3.2.0-RC1", 1, "Spring Boot style"),
        Arguments.of("2.15.2", "2.16.0-SNAPSHOT", -1, "Jackson style with snapshot"),
        Arguments.of("6.1.4", "6.1.4.RELEASE", 0, "Spring Framework style"));
  }

  @ParameterizedTest(name = "{3}")
  @MethodSource("versionComparisonTestData")
  void testCompare(String version1, String version2, int expectedSign, String description) {
    // When
    int result = versionComparator.compare(version1, version2);

    // Then - Check the sign of the result
    if (expectedSign == 0) {
      assertThat(result).isZero();
    } else if (expectedSign < 0) {
      assertThat(result).isNegative();
    } else {
      assertThat(result).isPositive();
    }
  }

  @Test
  void testCompare_NullAndEmptyVersions() {
    // Null versions should be handled gracefully or throw appropriate exceptions
    // The exact behavior depends on implementation, but it should be consistent

    try {
      int result1 = versionComparator.compare(null, "1.0.0");
      int result2 = versionComparator.compare("1.0.0", null);
      int result3 = versionComparator.compare(null, null);

      // If no exception, results should be consistent
      assertThat(result1).isNotEqualTo(result2); // Should be opposites
      assertThat(result3).isZero(); // null equals null
    } catch (NullPointerException e) {
      // This is also acceptable behavior
      assertThat(e).isInstanceOf(NullPointerException.class);
    }
  }

  @Test
  void testCompare_SameVersionsAreEqual() {
    String[] versions = {"1.0.0", "2.5.1", "1.0.0-RC1", "1.0.0-BETA", "1.0.0-ALPHA", "1.0.0-M1"};

    for (String version : versions) {
      int result = versionComparator.compare(version, version);
      assertThat(result).isZero();
    }
  }

  @Test
  void testGetLatest_BasicVersions() {
    // Given
    String[] versions = {"1.0.0", "2.0.0", "1.5.0", "2.1.0", "1.0.1"};

    // When
    String latest = VersionComparator.getLatest(versions);

    // Then
    assertThat(latest).isEqualTo("2.1.0");
  }

  @Test
  void testGetLatest_WithQualifiers() {
    // Given
    String[] versions = {"1.0.0", "1.0.0-RC1", "1.0.0-BETA", "1.0.0-ALPHA", "1.0.0-M1"};

    // When
    String latest = VersionComparator.getLatest(versions);

    // Then - Release version should be latest
    assertThat(latest).isEqualTo("1.0.0");
  }

  @Test
  void testGetLatest_RealWorldExample() {
    // Given - Realistic Spring Boot versions
    String[] versions = {
      "3.1.0", "3.1.1", "3.1.2", "3.2.0-RC1", "3.2.0-SNAPSHOT", "3.2.0", "3.1.5", "3.0.12"
    };

    // When
    String latest = VersionComparator.getLatest(versions);

    // Then - Should be the highest stable release
    assertThat(latest).isEqualTo("3.2.0");
  }

  @Test
  void testGetLatest_EmptyArray() {
    // Given
    String[] versions = {};

    // When
    String latest = VersionComparator.getLatest(versions);

    // Then
    assertThat(latest).isNull();
  }

  @Test
  void testGetLatest_NullArray() {
    // Given
    String[] versions = null;

    // When
    String latest = VersionComparator.getLatest(versions);

    // Then
    assertThat(latest).isNull();
  }

  @Test
  void testGetLatest_SingleVersion() {
    // Given
    String[] versions = {"1.0.0"};

    // When
    String latest = VersionComparator.getLatest(versions);

    // Then
    assertThat(latest).isEqualTo("1.0.0");
  }

  @Test
  void testSorting_DescendingOrder() {
    // Given
    List<String> versions = Arrays.asList("1.0.0", "3.0.0", "2.0.0", "1.5.0", "2.1.0");

    // When - Sort in descending order
    List<String> sorted = versions.stream().sorted(versionComparator.reversed()).toList();

    // Then
    assertThat(sorted).containsExactly("3.0.0", "2.1.0", "2.0.0", "1.5.0", "1.0.0");
  }

  @Test
  void testSorting_AscendingOrder() {
    // Given
    List<String> versions = Arrays.asList("3.0.0", "1.0.0", "2.1.0", "1.5.0", "2.0.0");

    // When - Sort in ascending order
    List<String> sorted = versions.stream().sorted(versionComparator).toList();

    // Then
    assertThat(sorted).containsExactly("1.0.0", "1.5.0", "2.0.0", "2.1.0", "3.0.0");
  }

  @Test
  void testQualifierPriority() {
    // Given - Same base version with different qualifiers
    List<String> versions =
        Arrays.asList("1.0.0-M1", "1.0.0-ALPHA", "1.0.0-BETA", "1.0.0-RC1", "1.0.0");

    // When - Sort in ascending order
    List<String> sorted =
        versions.stream()
            .sorted(versionComparator)
            .toList(); // Then - Should be in Maven order: alpha < beta < milestone < rc < release
    assertThat(sorted)
        .containsExactly("1.0.0-ALPHA", "1.0.0-BETA", "1.0.0-M1", "1.0.0-RC1", "1.0.0");
  }

  @Test
  void testMixedVersionFormats() {
    // Given - Versions with different formats
    List<String> versions = Arrays.asList("1", "1.0", "1.0.0", "1.0.1", "1.1", "2");

    // When - Sort in ascending order
    List<String> sorted = versions.stream().sorted(versionComparator).toList();

    // Then - Should handle different formats correctly
    assertThat(sorted).containsExactly("1", "1.0", "1.0.0", "1.0.1", "1.1", "2");
  }

  @Test
  void testConsistencyWithEquals() {
    // Given - Versions that should be equal
    String[][] equalVersionPairs = {
      {"1.0.0", "1.0.0"},
      {"1.0", "1.0.0"}, // Assuming this should be equal
      {"1.0.0-RC1", "1.0.0-rc1"}
    };

    for (String[] pair : equalVersionPairs) {
      // When
      int result1 = versionComparator.compare(pair[0], pair[1]);
      int result2 = versionComparator.compare(pair[1], pair[0]);

      // Then - If equal, both comparisons should return 0
      if (result1 == 0) {
        assertThat(result2).isZero();
      } else {
        // If not equal, they should be opposites
        assertThat(result1 * result2).isNegative();
      }
    }
  }

  @Test
  void testTransitivity() {
    // Given - Three versions
    String version1 = "1.0.0";
    String version2 = "1.5.0";
    String version3 = "2.0.0";

    // When
    int compare12 = versionComparator.compare(version1, version2);
    int compare23 = versionComparator.compare(version2, version3);
    int compare13 = versionComparator.compare(version1, version3);

    // Then - If version1 < version2 and version2 < version3, then version1 < version3
    if (compare12 < 0 && compare23 < 0) {
      assertThat(compare13).isNegative();
    }
  }

  @Test
  void testLargeVersionNumbers() {
    // Given - Versions with large numbers
    String[] versions = {"999.999.999", "1000.0.0", "1000.1.0"};

    // When
    String latest = VersionComparator.getLatest(versions);

    // Then
    assertThat(latest).isEqualTo("1000.1.0");
  }

  @Test
  void testVersionsWithLeadingZeros() {
    // Given - Versions that might have leading zeros
    String version1 = "1.01.0";
    String version2 = "1.1.0";

    // When
    int result = versionComparator.compare(version1, version2);

    // Then - Should handle leading zeros correctly (01 should equal 1)
    assertThat(result).isZero();
  }

  @ParameterizedTest
  @MethodSource("updateTypeTestData")
  void testDetermineUpdateType(
      String current, String latest, String expectedUpdateType, String description) {
    // When
    String updateType = versionComparator.determineUpdateType(current, latest);

    // Then
    assertThat(updateType).as(description).isEqualTo(expectedUpdateType);
  }

  @SuppressWarnings("unused")
  private static Stream<Arguments> updateTypeTestData() {
    return Stream.of(
        // Major updates
        Arguments.of("1.0.0", "2.0.0", "major", "Major version update"),
        Arguments.of("1.5.3", "2.0.0", "major", "Major version update with minor/patch"),
        Arguments.of("1.0.0-alpha", "2.0.0", "major", "Major update from alpha"),

        // Minor updates
        Arguments.of("1.0.0", "1.1.0", "minor", "Minor version update"),
        Arguments.of("1.0.5", "1.2.0", "minor", "Minor update with patch difference"),
        Arguments.of("1.0.0-beta", "1.1.0", "minor", "Minor update from beta"),

        // Patch updates
        Arguments.of("1.0.0", "1.0.1", "patch", "Patch version update"),
        Arguments.of("1.2.3", "1.2.4", "patch", "Simple patch update"),
        Arguments.of("1.0.0-rc", "1.0.1", "patch", "Patch update from RC"),

        // No updates / equal versions
        Arguments.of("1.0.0", "1.0.0", "none", "Same version"),
        Arguments.of("1.0.0-alpha", "1.0.0-alpha", "none", "Same alpha version"),

        // Unknown/downgrade scenarios
        Arguments.of("2.0.0", "1.0.0", "unknown", "Downgrade scenario"),
        Arguments.of("1.1.0", "1.0.0", "unknown", "Minor downgrade"),
        Arguments.of(null, "1.0.0", "unknown", "Null current version"),
        Arguments.of("1.0.0", null, "unknown", "Null latest version"));
  }

  @ParameterizedTest
  @MethodSource("stableVersionTestData")
  void testIsStableVersion(String version, boolean expectedStable, String description) {
    // When
    boolean isStable = versionComparator.isStableVersion(version);

    // Then
    assertThat(isStable).as(description).isEqualTo(expectedStable);
  }

  @SuppressWarnings("unused")
  private static Stream<Arguments> stableVersionTestData() {
    return Stream.of(
        // Stable versions
        Arguments.of("1.0.0", true, "Plain numeric version is stable"),
        Arguments.of("2.1.5", true, "Multi-component numeric version is stable"),
        Arguments.of("1.0.0-final", true, "Final qualifier is stable"),
        Arguments.of("1.0.0-ga", true, "GA qualifier is stable"),
        Arguments.of("1.0.0-release", true, "Release qualifier is stable"),
        Arguments.of("1.0.0-sp1", true, "Service pack is stable"),

        // Pre-release versions
        Arguments.of("1.0.0-alpha", false, "Alpha version is not stable"),
        Arguments.of("1.0.0-beta", false, "Beta version is not stable"),
        Arguments.of("1.0.0-rc", false, "RC version is not stable"),
        Arguments.of("1.0.0-snapshot", false, "Snapshot version is not stable"),
        Arguments.of("1.0.0-milestone", false, "Milestone version is not stable"),

        // Edge cases
        Arguments.of(null, false, "Null version is not stable"));
  }

  @ParameterizedTest
  @MethodSource("versionTypeTestData")
  void testGetVersionType(String version, String expectedType, String description) {
    // When
    String versionType = versionComparator.getVersionTypeString(version);

    // Then
    assertThat(versionType).as(description).isEqualTo(expectedType);
  }

  @SuppressWarnings("unused")
  private static Stream<Arguments> versionTypeTestData() {
    return Stream.of(
        // Stable versions
        Arguments.of("1.0.0", "stable", "Plain numeric version is stable"),
        Arguments.of("1.0.0-final", "stable", "Final qualifier is stable"),
        Arguments.of("1.0.0-ga", "stable", "GA qualifier is stable"),
        Arguments.of("1.0.0-release", "stable", "Release qualifier is stable"),

        // Alpha versions
        Arguments.of("1.0.0-alpha", "alpha", "Alpha version"),
        Arguments.of("1.0.0-a1", "alpha", "Alpha with number"),
        Arguments.of("1.0.0-dev", "alpha", "Dev version treated as alpha"),
        Arguments.of("1.0.0-preview", "alpha", "Preview version treated as alpha"),

        // Beta versions
        Arguments.of("1.0.0-beta", "beta", "Beta version"),
        Arguments.of("1.0.0-b1", "beta", "Beta with number"),

        // RC versions
        Arguments.of("1.0.0-rc", "rc", "RC version"),
        Arguments.of("1.0.0-cr", "rc", "CR version treated as RC"),
        Arguments.of("1.0.0-candidate", "rc", "Candidate version treated as RC"),

        // Milestone versions
        Arguments.of("1.0.0-milestone", "milestone", "Milestone version"),
        Arguments.of("1.0.0-m1", "milestone", "Milestone with number"),

        // Edge cases
        Arguments.of(null, "unknown", "Null version returns unknown"),
        Arguments.of("1.0.0-custom", "stable", "Unknown qualifier defaults to stable"));
  }
}
