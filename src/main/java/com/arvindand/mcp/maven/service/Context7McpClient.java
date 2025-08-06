package com.arvindand.mcp.maven.service;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Service for communicating with Context7 MCP server using Spring AI MCP client.
 *
 * <p>Uses Spring AI's MCP client starter to communicate with Context7 server via stdio transport.
 * Provides methods to resolve library IDs and fetch documentation.
 *
 * @author Arvind Menon
 * @since 1.2.0
 */
@Service
@ConditionalOnProperty(prefix = "context7", name = "enabled", havingValue = "true")
public class Context7McpClient {

  private static final Logger logger = LoggerFactory.getLogger(Context7McpClient.class);
  private static final int MAX_TOKENS = 10000;
  private static final int RESPONSE_PREVIEW_LENGTH = 100;
  private static final int DOCS_PREVIEW_LENGTH = 300;

  private final List<McpSyncClient> mcpClients;

  public Context7McpClient(List<McpSyncClient> mcpClients) {
    this.mcpClients = mcpClients;
  }

  /**
   * Resolve a library name to Context7-compatible library ID.
   *
   * @param libraryName the library name (e.g., "spring-boot-starter", "jackson-databind")
   * @return Context7 library ID if found
   */
  public Optional<String> resolveLibraryId(String libraryName) {
    try {
      McpSyncClient context7Client = getContext7Client();
      if (context7Client == null) {
        logger.debug("Context7 MCP client not available");
        return Optional.empty();
      }

      // Try resolution with the provided library name
      Map<String, Object> arguments = Map.of("libraryName", libraryName);
      CallToolResult result =
          context7Client.callTool(new CallToolRequest("resolve-library-id", arguments));

      if (result.content() != null && !result.content().isEmpty()) {
        String response = extractTextFromContent(result.content());
        logger.debug(
            "Context7 response for '{}': {}",
            libraryName,
            response != null ? truncateForPreview(response, RESPONSE_PREVIEW_LENGTH) : "null");

        String libraryId = extractLibraryIdFromResponse(response, libraryName);
        if (libraryId != null && !libraryId.isBlank()) {
          logger.info("✅ Resolved Context7 library ID for {}: {}", libraryName, libraryId);
          return Optional.of(libraryId.trim());
        }
      }

      logger.debug("Context7 resolution failed for {}", libraryName);
      return Optional.empty();

    } catch (Exception e) {
      logger.warn("Error resolving library ID for {}: {}", libraryName, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Extract the best library ID from Context7 resolve response. The response contains multiple
   * libraries with metadata - select the best match based on relevance and quality.
   */
  private String extractLibraryIdFromResponse(String response, String searchTerm) {
    if (response == null || response.isBlank()) {
      return null;
    }

    // Parse the structured response to find library entries
    String[] lines = response.split("\n");
    String bestLibraryId = null;
    double bestScore = -1;

    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();

      // Look for library ID lines: "- Context7-compatible library ID: /org/project"
      if (line.startsWith("- Context7-compatible library ID: ")) {
        String libraryId = line.substring("- Context7-compatible library ID: ".length()).trim();
        if (libraryId.startsWith("/") && libraryId.contains("/")) {

          // Extract title and description for relevance scoring
          String title = extractTitleForLibrary(lines, i);
          String description = extractDescriptionForLibrary(lines, i);

          // Calculate score based on relevance, metadata, and quality
          double score = calculateLibraryScore(lines, i, libraryId, searchTerm, title, description);

          if (score > bestScore) {
            bestScore = score;
            bestLibraryId = libraryId;
          }
        }
      }
    }

    logger.debug(
        "Best library match for '{}': {} (score: {})", searchTerm, bestLibraryId, bestScore);
    return bestLibraryId;
  }

  /**
   * Calculate a smart relevance-aware score for library selection. Combines relevance scoring with
   * quality metrics to pick the most appropriate library.
   */
  private double calculateLibraryScore(
      String[] lines,
      int libraryIdIndex,
      String libraryId,
      String searchTerm,
      String title,
      String description) {
    double trustScore = 0.0;
    int codeSnippets = 0;

    // Look for metadata in the next few lines after the library ID
    for (int j = libraryIdIndex + 1; j < Math.min(lines.length, libraryIdIndex + 6); j++) {
      String metaLine = lines[j].trim();

      if (metaLine.startsWith("- Trust Score: ")) {
        try {
          String scoreStr = metaLine.substring("- Trust Score: ".length()).trim();
          trustScore = Double.parseDouble(scoreStr);
        } catch (NumberFormatException e) {
          // Ignore parse errors
        }
      } else if (metaLine.startsWith("- Code Snippets: ")) {
        try {
          String snippetsStr = metaLine.substring("- Code Snippets: ".length()).trim();
          codeSnippets = Integer.parseInt(snippetsStr);
        } catch (NumberFormatException e) {
          // Ignore parse errors
        }
      } else if (metaLine.startsWith("----------")) {
        // End of this library entry
        break;
      }
    }

    // Calculate relevance score (0.0 to 10.0) - this is the key improvement
    double relevanceScore = calculateRelevanceScore(searchTerm, libraryId, title, description);

    // Quality metrics (trust score 0-10, snippet bonus 0-2)
    double snippetBonus = Math.min(2.0, codeSnippets / 1000.0);
    double qualityScore = trustScore + snippetBonus;

    // Weighted combination: relevance is most important (60%), quality matters (40%)
    double finalScore = (relevanceScore * 0.6) + (qualityScore * 0.4);

    logger.debug(
        "Library '{}' scores - relevance: {}, quality: {} (trust: {}, snippets: {}), final: {}",
        libraryId,
        relevanceScore,
        qualityScore,
        trustScore,
        codeSnippets,
        finalScore);

    return finalScore;
  }

  /**
   * Calculate relevance score based on how well the library matches the search term. Higher scores
   * for exact matches, partial matches, and conceptually related libraries.
   */
  private double calculateRelevanceScore(
      String searchTerm, String libraryId, String title, String description) {
    if (searchTerm == null || searchTerm.isBlank()) {
      return 5.0; // neutral score if no search term
    }

    String searchLower = searchTerm.toLowerCase().trim();
    String idLower = libraryId.toLowerCase();
    String titleLower = title != null ? title.toLowerCase() : "";
    String descLower = description != null ? description.toLowerCase() : "";

    double score = 0.0;

    // Exact matches in library ID (highest priority)
    if (idLower.contains("/" + searchLower + "/") || idLower.endsWith("/" + searchLower)) {
      score += 10.0; // Perfect match
    } else if (idLower.contains(searchLower)) {
      score += 8.0; // Very good match
    }

    // Exact matches in title (second highest priority)
    if (titleLower.equals(searchLower)) {
      score += 9.0;
    } else if (titleLower.contains(searchLower)) {
      score += 7.0;
    }

    // Partial word matches in title and ID
    String[] searchWords = searchLower.split("[-_\\s]+");
    for (String word : searchWords) {
      if (word.length() >= 3) { // Skip very short words
        if (titleLower.contains(word)) score += 2.0;
        if (idLower.contains(word)) score += 1.5;
        if (descLower.contains(word)) score += 1.0;
      }
    }

    // Special boost for official/authoritative sources
    if (idLower.contains("/spring-projects/") && searchLower.contains("spring")) {
      score += 3.0; // Official Spring projects
    } else if (idLower.contains("/context7/")
        && (titleLower.contains("api") || titleLower.contains("docs"))) {
      score += 2.0; // Official documentation
    }

    // Penalty for completely unrelated libraries (like bootstrap-table for spring-boot-starter)
    if (searchLower.contains("spring")
        && !titleLower.contains("spring")
        && !idLower.contains("spring")) {
      if (!titleLower.contains("boot") || titleLower.contains("bootstrap")) {
        // Bootstrap Table gets penalized for being a different kind of "boot"
        score = Math.min(score, 1.0); // Cap at very low relevance
      }
    }

    // General penalty for mismatched library types
    if (score < 2.0 && searchWords.length >= 2) {
      // If it's a multi-word search and we have very low relevance, it's likely unrelated
      boolean hasAnyWordMatch = false;
      for (String word : searchWords) {
        if (word.length() >= 4 && (titleLower.contains(word) || idLower.contains(word))) {
          hasAnyWordMatch = true;
          break;
        }
      }
      if (!hasAnyWordMatch) {
        score = 0.1; // Very low relevance for completely unrelated
      }
    }

    return Math.min(10.0, score); // Cap at 10.0
  }

  /** Extract title for a library from the Context7 response. */
  private String extractTitleForLibrary(String[] lines, int libraryIdIndex) {
    // Look backwards for the title (should be just before the library ID line)
    for (int j = libraryIdIndex - 1; j >= Math.max(0, libraryIdIndex - 3); j--) {
      String line = lines[j].trim();
      if (line.startsWith("- Title: ")) {
        return line.substring("- Title: ".length()).trim();
      }
    }
    return null;
  }

  /** Extract description for a library from the Context7 response. */
  private String extractDescriptionForLibrary(String[] lines, int libraryIdIndex) {
    // Look forward for the description (should be after the library ID line)
    for (int j = libraryIdIndex + 1; j < Math.min(lines.length, libraryIdIndex + 5); j++) {
      String line = lines[j].trim();
      if (line.startsWith("- Description: ")) {
        return line.substring("- Description: ".length()).trim();
      } else if (line.startsWith("----------")) {
        break; // End of this library entry
      }
    }
    return null;
  }

  /**
   * Get library documentation from Context7.
   *
   * @param libraryId Context7-compatible library ID
   * @param topic optional topic to focus on
   * @return documentation content if available
   */
  public Optional<String> getLibraryDocs(String libraryId, String topic) {
    try {
      McpSyncClient context7Client = getContext7Client();
      if (context7Client == null) {
        logger.debug("Context7 MCP client not available");
        return Optional.empty();
      }

      // First attempt: with topic if provided
      if (topic != null && !topic.isBlank()) {
        Map<String, Object> arguments =
            Map.of(
                "context7CompatibleLibraryID", libraryId,
                "topic", topic,
                "tokens", MAX_TOKENS);

        try {
          CallToolResult result =
              context7Client.callTool(new CallToolRequest("get-library-docs", arguments));

          if (result.content() != null && !result.content().isEmpty()) {
            String docs = extractTextFromContent(result.content());
            if (docs != null && !docs.isBlank()) {
              logger.info(
                  "✅ Context7 docs retrieved for {} with topic '{}' (length: {})",
                  libraryId,
                  topic,
                  docs.length());
              logger.debug(
                  "Context7 docs content preview for {} with topic '{}': {}",
                  libraryId,
                  topic,
                  truncateForPreview(docs, DOCS_PREVIEW_LENGTH));
              return Optional.of(docs);
            } else {
              logger.debug(
                  "❌ Context7 returned empty docs for {} with topic '{}'", libraryId, topic);
            }
          } else {
            logger.debug("❌ Context7 returned no content for {} with topic '{}'", libraryId, topic);
          }
        } catch (Exception topicException) {
          logger.debug(
              "Topic-based search failed for {} with topic '{}': {}",
              libraryId,
              topic,
              topicException.getMessage());
          // Continue to fallback without topic
        }
      }

      // Fallback attempt: without topic
      Map<String, Object> arguments =
          Map.of("context7CompatibleLibraryID", libraryId, "tokens", MAX_TOKENS);

      CallToolResult result =
          context7Client.callTool(new CallToolRequest("get-library-docs", arguments));

      if (result.content() != null && !result.content().isEmpty()) {
        String docs = extractTextFromContent(result.content());
        if (docs != null && !docs.isBlank()) {
          logger.info(
              "✅ Context7 fallback docs retrieved for {} without topic (length: {})",
              libraryId,
              docs.length());
          logger.debug(
              "Context7 fallback docs content preview for {}: {}",
              libraryId,
              truncateForPreview(docs, DOCS_PREVIEW_LENGTH));
          return Optional.of(docs);
        } else {
          logger.debug("❌ Context7 fallback returned empty docs for {}", libraryId);
        }
      } else {
        logger.debug("❌ Context7 fallback returned no content for {}", libraryId);
      }

      logger.warn(
          "❌ No Context7 documentation available for {} (both topic-based and fallback failed)",
          libraryId);
      return Optional.empty();

    } catch (Exception e) {
      logger.warn("Error getting library docs for {}: {}", libraryId, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Get the Context7 MCP client from the configured clients. Since we only have one client
   * configured (context7), we can use the first one.
   */
  private McpSyncClient getContext7Client() {
    if (mcpClients.isEmpty()) {
      logger.debug("No MCP clients configured");
      return null;
    }

    // Return the first client - in our case this should be the context7 client
    return mcpClients.get(0);
  }

  /** Extract text content from MCP tool result content. */
  private String extractTextFromContent(List<Content> content) {
    if (content == null || content.isEmpty()) {
      return null;
    }

    for (Content item : content) {
      // Check if this is a TextContent by examining the discriminator field
      if ("text".equals(item.type()) && item instanceof McpSchema.TextContent textContent) {
        return textContent.text();
      }
    }

    return null;
  }

  /** Truncate text for logging preview with ellipsis if needed. */
  private String truncateForPreview(String text, int maxLength) {
    if (text == null || text.length() <= maxLength) {
      return text;
    }
    return text.substring(0, maxLength) + "...";
  }
}
