package com.arvindand.mcp.maven.model.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for LicenseInfo.
 *
 * @author Arvind Menon
 * @since 2.0.0
 */
class LicenseInfoTest {

  @ParameterizedTest
  @MethodSource("permissiveLicenseTestData")
  void categorize_WithPermissiveLicense_ReturnsPermissive(String licenseName) {
    LicenseInfo.LicenseCategory result = LicenseInfo.categorize(licenseName);

    assertThat(result).isEqualTo(LicenseInfo.LicenseCategory.PERMISSIVE);
  }

  @SuppressWarnings("unused")
  private static Stream<String> permissiveLicenseTestData() {
    return Stream.of(
        "Apache License, Version 2.0",
        "The Apache Software License, Version 2.0",
        "MIT License",
        "The MIT License",
        "BSD License",
        "BSD 3-Clause License",
        "BSD-2-Clause",
        "ISC License",
        "The Unlicense",
        "CC0 1.0 Universal",
        "WTFPL",
        "zlib License",
        "Public Domain");
  }

  @ParameterizedTest
  @MethodSource("weakCopyleftLicenseTestData")
  void categorize_WithWeakCopyleftLicense_ReturnsWeakCopyleft(String licenseName) {
    LicenseInfo.LicenseCategory result = LicenseInfo.categorize(licenseName);

    assertThat(result).isEqualTo(LicenseInfo.LicenseCategory.WEAK_COPYLEFT);
  }

  @SuppressWarnings("unused")
  private static Stream<String> weakCopyleftLicenseTestData() {
    return Stream.of(
        "GNU Lesser General Public License",
        "LGPL 2.1",
        "LGPL-3.0",
        "GNU Library General Public License",
        "Mozilla Public License 2.0",
        "MPL 2.0",
        "Eclipse Public License 1.0",
        "EPL-2.0",
        "Common Development and Distribution License");
  }

  @ParameterizedTest
  @MethodSource("strongCopyleftLicenseTestData")
  void categorize_WithStrongCopyleftLicense_ReturnsStrongCopyleft(String licenseName) {
    LicenseInfo.LicenseCategory result = LicenseInfo.categorize(licenseName);

    assertThat(result).isEqualTo(LicenseInfo.LicenseCategory.STRONG_COPYLEFT);
  }

  @SuppressWarnings("unused")
  private static Stream<String> strongCopyleftLicenseTestData() {
    return Stream.of(
        "GNU General Public License v3.0",
        "GPL-3.0",
        "GPLv2",
        "GNU Affero General Public License",
        "AGPL-3.0");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  ", "Unknown License", "Proprietary", "Custom Terms"})
  void categorize_WithUnknownLicense_ReturnsUnknown(String licenseName) {
    LicenseInfo.LicenseCategory result = LicenseInfo.categorize(licenseName);

    assertThat(result).isEqualTo(LicenseInfo.LicenseCategory.UNKNOWN);
  }

  @Test
  void categorize_PrioritizesLgplOverGpl() {
    // LGPL should be detected as weak copyleft, not strong copyleft
    assertThat(LicenseInfo.categorize("GNU Lesser General Public License"))
        .isEqualTo(LicenseInfo.LicenseCategory.WEAK_COPYLEFT);
    assertThat(LicenseInfo.categorize("LGPL-3.0"))
        .isEqualTo(LicenseInfo.LicenseCategory.WEAK_COPYLEFT);
    assertThat(LicenseInfo.categorize("GNU Library General Public License"))
        .isEqualTo(LicenseInfo.LicenseCategory.WEAK_COPYLEFT);
  }

  @Test
  void fromPom_CreatesLicenseInfoWithCorrectCategory() {
    LicenseInfo result =
        LicenseInfo.fromPom(
            "Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0");

    assertThat(result.name()).isEqualTo("Apache License, Version 2.0");
    assertThat(result.url()).isEqualTo("https://www.apache.org/licenses/LICENSE-2.0");
    assertThat(result.category()).isEqualTo(LicenseInfo.LicenseCategory.PERMISSIVE);
  }

  @Test
  void fromPom_WithNullUrl_HandlesGracefully() {
    LicenseInfo result = LicenseInfo.fromPom("MIT License", null);

    assertThat(result.name()).isEqualTo("MIT License");
    assertThat(result.url()).isNull();
    assertThat(result.category()).isEqualTo(LicenseInfo.LicenseCategory.PERMISSIVE);
  }

  @Test
  void isPermissive_WithPermissiveLicense_ReturnsTrue() {
    LicenseInfo license = LicenseInfo.fromPom("Apache License 2.0", null);

    assertThat(license.isPermissive()).isTrue();
  }

  @Test
  void isPermissive_WithCopyleftLicense_ReturnsFalse() {
    LicenseInfo license = LicenseInfo.fromPom("GPL-3.0", null);

    assertThat(license.isPermissive()).isFalse();
  }

  @Test
  void isCopyleft_WithStrongCopyleft_ReturnsTrue() {
    LicenseInfo license = LicenseInfo.fromPom("GNU General Public License v3.0", null);

    assertThat(license.isCopyleft()).isTrue();
  }

  @Test
  void isCopyleft_WithWeakCopyleft_ReturnsTrue() {
    LicenseInfo license = LicenseInfo.fromPom("LGPL-3.0", null);

    assertThat(license.isCopyleft()).isTrue();
  }

  @Test
  void isCopyleft_WithPermissiveLicense_ReturnsFalse() {
    LicenseInfo license = LicenseInfo.fromPom("MIT License", null);

    assertThat(license.isCopyleft()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("caseInsensitiveTestData")
  void categorize_IsCaseInsensitive(String licenseName, LicenseInfo.LicenseCategory expected) {
    LicenseInfo.LicenseCategory result = LicenseInfo.categorize(licenseName);

    assertThat(result).isEqualTo(expected);
  }

  @SuppressWarnings("unused")
  private static Stream<Arguments> caseInsensitiveTestData() {
    return Stream.of(
        Arguments.of("APACHE LICENSE", LicenseInfo.LicenseCategory.PERMISSIVE),
        Arguments.of("mit license", LicenseInfo.LicenseCategory.PERMISSIVE),
        Arguments.of("Gpl-3.0", LicenseInfo.LicenseCategory.STRONG_COPYLEFT),
        Arguments.of("LGPL-2.1", LicenseInfo.LicenseCategory.WEAK_COPYLEFT));
  }
}
