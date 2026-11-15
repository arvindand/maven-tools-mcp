package com.arvindand.mcp.maven.model;

import com.arvindand.mcp.maven.model.security.SecurityAssessment;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Aggregate result of the vulnerability scan run by {@code recommend_pom_upgrades}.
 *
 * @param scanned number of distinct dependency versions scanned
 * @param vulnerable how many have known vulnerabilities
 * @param clean how many are confirmed clean
 * @param unknown how many could not be assessed (for example OSV unavailable); never counted as
 *     clean
 * @param worstSeverity highest severity across all vulnerable versions; null when none
 * @author Arvind Menon
 * @since 3.3.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PomSecuritySummary(
    int scanned,
    int vulnerable,
    int clean,
    int unknown,
    SecurityAssessment.Severity worstSeverity) {}
