/**
 * Effective POM resolution for the {@code analyze_pom_dependencies} family of tools.
 *
 * <p>Resolves the effective version of each declared dependency in a POM by walking the parent
 * chain, applying {@code <dependencyManagement>}, and resolving BOM imports ({@code
 * <scope>import</scope><type>pom</type>}). Parent and BOM fetches go through {@link
 * com.arvindand.mcp.maven.service.MavenCentralService} by default; callers can supply a bundle of
 * additional POM strings (sibling modules, unreleased parents) that take precedence over Maven
 * Central via {@link com.arvindand.mcp.maven.pom.InMemoryPomFetcher} + {@link
 * com.arvindand.mcp.maven.pom.CompositePomFetcher}.
 *
 * <p>Scoped to declared-dep resolution only: no transitive dependency walking and no scope
 * downgrade rules. The output is the set of {@code <dependencies>} in the input POM with effective
 * versions, classification ({@link com.arvindand.mcp.maven.pom.Source#EXPLICIT}, {@link
 * com.arvindand.mcp.maven.pom.Source#MANAGED}, {@link
 * com.arvindand.mcp.maven.pom.Source#EXPLICIT_OVERRIDE}), the managing BOM or parent coordinate
 * where applicable, and any losing {@link com.arvindand.mcp.maven.pom.ManagedAlternative candidate
 * versions} from competing BOMs. Direct non-import root {@code <dependencyManagement>} entries with
 * an unambiguous literal or root-owned property edit are also surfaced as {@link
 * com.arvindand.mcp.maven.pom.ManagedDeclaration} values for upgrade automation. Dependencies
 * declared directly under root build plugins and plugin management are surfaced as {@link
 * com.arvindand.mcp.maven.pom.PluginDependencyDeclaration} values with their owner plugin.
 *
 * <p>The resolution shape (parent walk → properties → BOM import → depMgmt merge) follows the
 * MIT-licensed <a href="https://github.com/maxxq-org/maxxq-maven">maxxq-org/maxxq-maven</a> by Guy
 * Chauliac.
 *
 * @author Arvind Menon
 * @since 3.0.0
 */
package com.arvindand.mcp.maven.pom;
