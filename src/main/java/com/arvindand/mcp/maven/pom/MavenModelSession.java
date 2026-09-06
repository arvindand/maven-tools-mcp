package com.arvindand.mcp.maven.pom;

import com.arvindand.mcp.maven.model.MavenCoordinate;
import com.arvindand.mcp.maven.util.MavenCoordinateParser;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.StringModelSource;
import org.apache.maven.model.composition.DefaultDependencyManagementImporter;
import org.apache.maven.model.composition.DependencyManagementImporter;
import org.apache.maven.model.interpolation.ModelInterpolator;
import org.apache.maven.model.interpolation.StringVisitorModelInterpolator;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.model.profile.activation.ProfileActivator;
import org.apache.maven.model.resolution.ModelResolver;
import org.codehaus.plexus.interpolation.AbstractValueSource;
import org.codehaus.plexus.interpolation.ValueSource;

/**
 * Builds Maven's effective model in an isolated, bounded, request-local resolution session.
 *
 * <p>Only the injected fetcher supplies models. POM repositories, relative filesystem parents and
 * server-specific profile activation cannot influence resolution. Apache Maven owns inheritance,
 * interpolation and import precedence; this class records import candidates for diagnostics.
 *
 * @author Arvind Menon
 * @since 3.2.2
 */
final class MavenModelSession implements ModelResolver {
  private final PomFetcher fetcher;
  private final List<String> warnings;
  private final Map<String, ModelSource> sources = new HashMap<>();
  private final Map<String, List<Dependency>> candidates = new HashMap<>();
  private final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
  private long totalCharacters;

  MavenModelSession(PomFetcher fetcher, List<String> warnings) {
    this.fetcher = fetcher;
    this.warnings = warnings;
  }

  ModelBuildingResult build(String xml) {
    PomInputLimits.check(xml);
    totalCharacters = xml.length();
    DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
    request.setModelSource(new StringModelSource(xml, "input-pom"));
    request.setModelResolver(this);
    request.setSystemProperties(new Properties());
    request.setUserProperties(new Properties());
    request.setProcessPlugins(false);
    request.setLocationTracking(true);
    request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
    try {
      return factory().newInstance().build(request);
    } catch (ModelBuildingException ex) {
      ex.getProblems().forEach(problem -> warnings.add(problem.getMessage()));
      ModelBuildingResult partial = ex.getResult();
      if (partial != null && partial.getEffectiveModel() != null) {
        return partial;
      }
      throw new IllegalArgumentException("Maven could not build this POM: " + ex.getMessage(), ex);
    }
  }

  List<Dependency> candidates(Model model) {
    return candidates.getOrDefault(model.getId(), List.of());
  }

  private DefaultModelBuilderFactory factory() {
    return new DefaultModelBuilderFactory() {
      @Override
      protected ProfileActivator[] newProfileActivators() {
        // Default profiles are selected by Maven itself, independently of environment activators.
        return new ProfileActivator[0];
      }

      @Override
      protected ModelInterpolator newModelInterpolator() {
        StringVisitorModelInterpolator interpolator =
            new StringVisitorModelInterpolator() {
              @Override
              protected List<ValueSource> createValueSources(
                  Model model,
                  File directory,
                  ModelBuildingRequest request,
                  ModelProblemCollector problems) {
                List<ValueSource> originals =
                    super.createValueSources(model, directory, request, problems);
                return originals.stream().map(source -> boundedSource(source, originals)).toList();
              }

              @Override
              public Model interpolateModel(
                  Model model,
                  File directory,
                  ModelBuildingRequest request,
                  ModelProblemCollector problems) {
                checkExpansion(model);
                return super.interpolateModel(model, directory, request, problems);
              }
            };
        interpolator.setPathTranslator(newPathTranslator());
        interpolator.setUrlNormalizer(newUrlNormalizer());
        interpolator.setVersionPropertiesProcessor(newModelVersionPropertiesProcessor());
        return interpolator;
      }

      @Override
      protected DependencyManagementImporter newDependencyManagementImporter() {
        return new DefaultDependencyManagementImporter() {
          @Override
          public void importManagement(
              Model model,
              List<? extends DependencyManagement> imports,
              ModelBuildingRequest request,
              ModelProblemCollector problems) {
            List<Dependency> entries = new ArrayList<>();
            if (model.getDependencyManagement() != null) {
              entries.addAll(model.getDependencyManagement().getDependencies());
            }
            if (imports != null)
              imports.forEach(management -> entries.addAll(management.getDependencies()));
            candidates.put(model.getId(), List.copyOf(entries));
            super.importManagement(model, imports, request, problems);
          }
        };
      }
    };
  }

  private static ValueSource boundedSource(ValueSource source, List<ValueSource> originals) {
    return new AbstractValueSource(false) {
      @Override
      public Object getValue(String expression) {
        Object value = source.getValue(expression);
        if (value instanceof String text) {
          // Preflight only: return Maven's original value so its interpolation semantics and
          // post-processors remain authoritative, including cycle diagnostics.
          PropertyInterpolator.expand(
              text, key -> lookupString(originals, key), new HashSet<>(), 0, 16_384);
        }
        return value;
      }
    };
  }

  private static String lookupString(List<ValueSource> sources, String key) {
    for (ValueSource source : sources) {
      Object value = source.getValue(key);
      if (value != null) return value instanceof String text ? text : null;
    }
    return null;
  }

  private void checkExpansion(Model model) {
    checkDeadline();
    Map<String, String> properties = new LinkedHashMap<>();
    model.getProperties().forEach((key, value) -> properties.put(key.toString(), value.toString()));
    // Expand each property once before handing the model to Maven's interpolator. This bounds
    // acyclic amplification as well as the size of each substitution.
    Map<String, String> expanded = new HashMap<>();
    properties.forEach(
        (key, value) -> expanded.put(key, PropertyInterpolator.interpolate(value, properties)));
    String serialized = serialize(model);
    PropertyInterpolator.expand(
        serialized, expanded, new HashSet<>(), 0, 4 * PomInputLimits.MAX_DOCUMENT_CHARS);
    model.getProfiles().stream()
        .filter(profile -> profile.getActivation() != null)
        .filter(
            profile ->
                profile.getActivation().getJdk() != null
                    || profile.getActivation().getOs() != null
                    || profile.getActivation().getProperty() != null
                    || profile.getActivation().getFile() != null)
        .forEach(
            profile ->
                warnings.add(
                    "Profile "
                        + profile.getId()
                        + " has environment activation; only activeByDefault profiles are evaluated"));
  }

  @Override
  public ModelSource resolveModel(String groupId, String artifactId, String version) {
    checkDeadline();
    MavenCoordinate coordinate = MavenCoordinate.of(groupId, artifactId, version);
    MavenCoordinateParser.validateRepositoryCoordinate(coordinate);
    String key = coordinate.toCoordinateString();
    ModelSource existing = sources.get(key);
    if (existing != null) return existing;
    if (sources.size() >= PomInputLimits.MAX_MODELS) {
      throw new IllegalArgumentException("POM resolution exceeds 64 fetched models");
    }
    Model model;
    try {
      model = fetcher.fetch(coordinate).map(Model::clone).orElse(null);
    } catch (RuntimeException ex) {
      warnings.add("Could not fetch " + key + " (" + ex.getClass().getSimpleName() + ")");
      model = null;
    }
    if (model == null) {
      warnings.add("Parent or imported BOM " + key + " could not be fetched");
      model = new Model();
      model.setModelVersion("4.0.0");
      model.setGroupId(groupId);
      model.setArtifactId(artifactId);
      model.setVersion(version);
      model.setPackaging("pom");
    }
    String xml = serialize(model);
    PomInputLimits.check(xml);
    totalCharacters += xml.length();
    if (totalCharacters > 4L * PomInputLimits.MAX_DOCUMENT_CHARS) {
      throw new IllegalArgumentException("POM resolution exceeds four MiB of model characters");
    }
    ModelSource source = new StringModelSource(xml, key);
    sources.put(key, source);
    return source;
  }

  private void checkDeadline() {
    if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
      throw new IllegalArgumentException("POM resolution exceeded its time budget");
    }
  }

  private static String serialize(Model model) {
    try {
      StringWriter writer = new StringWriter();
      new MavenXpp3Writer().write(writer, model);
      return writer.toString();
    } catch (IOException ex) {
      throw new IllegalStateException("Could not serialize the Maven model", ex);
    }
  }

  @Override
  public ModelSource resolveModel(Parent parent) {
    return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
  }

  @Override
  public ModelSource resolveModel(Dependency dependency) {
    return resolveModel(
        dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
  }

  @Override
  public void addRepository(Repository repository) {
    // Repository endpoints are configured by the operator, never by untrusted POMs.
  }

  @Override
  public void addRepository(Repository repository, boolean replace) {
    addRepository(repository);
  }

  @Override
  public ModelResolver newCopy() {
    return this; // Copies share the same request budget and immutable sources.
  }
}
