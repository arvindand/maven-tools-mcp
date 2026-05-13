/**
 * Effective POM resolution for the {@code analyze_pom_dependencies} family of tools.
 *
 * <p>Resolves the effective version of each declared dependency in a POM by walking the parent
 * chain, applying {@code <dependencyManagement>}, and resolving BOM imports ({@code
 * <scope>import</scope><type>pom</type>}). All parent and BOM fetches go through {@link
 * com.arvindand.mcp.maven.service.MavenCentralService} against Maven Central.
 *
 * <p>The resolution algorithm shape (parent walk → properties → BOM import → depMgmt merge) is
 * adapted from the MIT-licensed <a
 * href="https://github.com/maxxq-org/maxxq-maven">maxxq-org/maxxq-maven</a> by Guy Chauliac. No
 * source was copied; the implementation is written from scratch and scoped to declared-dep
 * resolution only (no transitive walking, no scope downgrade rules).
 */
package com.arvindand.mcp.maven.pom;
