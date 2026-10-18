package eu.wohlben.qits.mirror.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-mirror's provider golden masters</b> — {@code golden-masters/} at the repository
 * root, the source of the published golden-master packages consumers write their pacts against.
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it runs the state ({@link
 * ProviderStates}), calls the operation and renders {@code
 * golden-masters/<state-slug>/<operationId>.json}; then it renders {@code golden-masters/index.json}
 * in the format qits-projects-service set (format version 1).
 *
 * <p><b>Instants are frozen.</b> A cache root's {@code createdAt} is the time Flyway or the seeder
 * wrote it, so every string value shaped like an ISO-8601 instant becomes {@value #FROZEN_INSTANT}
 * and its path goes in the index's {@code frozen.instants}.
 *
 * <p><b>Not every answer is a JSON document</b>, and the format says so the way qits-artifacts'
 * recorder does:
 *
 * <ul>
 *   <li>{@code file} — only when the answer is a JSON document. A {@code maven-metadata.xml} (XML)
 *       and a 404 record no file: the status, and {@code headers}, are the whole answer. The
 *       consumer helper binds JSON paths only, so an XML body could not be bound if it were here.
 *   <li>{@code headers} — the response headers a consumer reads ({@code Content-Type}).
 *   <li>{@code frozen.volatile} — the paths whose value depends on the request rather than on the
 *       state: a packument's {@code dist.tarball} is rewritten to the asking host, which is a random
 *       port here. The recording holds {@code ""} there; a consumer matches it by type.
 * </ul>
 *
 * <p>Nothing else varies between runs.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file — including a
 * committed {@code .json} no interaction produces any more. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites instead, and deletes such stale files; see {@link
 * GoldenFiles}.
 */
@QuarkusTest
@TestProfile(ContractProfile.class)
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;

  /** The application name, as every provider's index names itself. */
  static final String PROVIDER = "qits-mirror";

  static final String FROZEN_INSTANT = "2026-01-01T00:00:00Z";

  static final Pattern INSTANT =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})");

  /**
   * One recorded interaction. {@code path} is the template; {@code {name}} segments are filled from
   * the state's params.
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      int status,
      boolean recordsAnswer,
      List<String> headers,
      List<String> blanked) {

    /** A JSON answer, recorded whole. */
    Interaction(String state, String operationId, String method, String path, int status) {
      this(state, operationId, method, path, status, true, List.of(), List.of());
    }

    /** Not a JSON document: the status and these headers are the answer. */
    static Interaction statusOnly(
        String state, String operationId, String path, int status, String... headers) {
      return new Interaction(state, operationId, "GET", path, status, false, List.of(headers), List.of());
    }

    /** A JSON answer whose strings at {@code blanked} depend on the request and record as "". */
    Interaction blanking(String... paths) {
      return new Interaction(
          state, operationId, method, path, status, recordsAnswer, headers, List.of(paths));
    }
  }

  /** {@code GET /artifacts/maven/central/...}: the Central cache, where qits-maintenance reads it. */
  static final String MAVEN_METADATA =
      "/artifacts/maven/central/{groupPath}/{artifact}/maven-metadata.xml";

  /** {@code GET /npm/npmjs/<package>}: the npmjs cache on the mount qits-maintenance derives. */
  static final String NPM_PACKUMENT = "/npm/npmjs/{package}";

  /**
   * The service publishes no OpenAPI document, so the operation ids are this table's. Renaming one
   * is a contract change all the same.
   */
  static final List<Interaction> INTERACTIONS =
      List.of(
          new Interaction(
              ProviderStates.THE_DEFAULT_CACHES,
              "listRepositories",
              "GET",
              "/mirror/api/repositories",
              200),
          new Interaction(
              ProviderStates.THE_DEFAULT_CACHES,
              "listUpstreams",
              "GET",
              "/mirror/api/upstreams",
              200),
          new Interaction(
              ProviderStates.AN_NPM_CACHE_HOLDING_A_PACKAGE,
              "listRepositoryPackages",
              "GET",
              "/mirror/api/repositories/{repository}/packages",
              200),
          // The caches, as qits-maintenance's LatestResolver reads them for an EXTERNAL pin.
          Interaction.statusOnly(
              ProviderStates.A_CACHED_MAVEN_ARTIFACT,
              "getMavenMetadata",
              MAVEN_METADATA,
              200,
              "Content-Type"),
          Interaction.statusOnly(
              ProviderStates.NO_MAVEN_ARTIFACT_UPSTREAM, "getMavenMetadata", MAVEN_METADATA, 404),
          new Interaction(
                  ProviderStates.A_CACHED_NPM_PACKAGE, "getNpmPackument", "GET", NPM_PACKUMENT, 200)
              .blanking("$.versions[*].dist.tarball"),
          Interaction.statusOnly(
              ProviderStates.NO_NPM_PACKAGE_UPSTREAM, "getNpmPackument", NPM_PACKUMENT, 404));

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @Test
  void goldenMastersMatchTheProvider() throws Exception {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();

    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    for (Interaction interaction : INTERACTIONS) {
      Map<String, String> params = states.params(interaction.state());
      HttpResponse<String> response = call(interaction, params);
      Set<String> instants = new LinkedHashSet<>();
      JsonNode body = null;
      if (interaction.recordsAnswer()) {
        body = JSON.readTree(response.body());
        for (String blanked : interaction.blanked()) {
          if (blank(body, blanked.substring(2).split("\\."), 0) == 0) {
            failures.add(blanked + " names no string in the " + interaction.operationId() + " answer");
          }
        }
        body = freeze(body, "$", instants);
      }
      String slug = ProviderStates.slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
      params.forEach(frozenParams::put);
      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", frozenParams);
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(frozenParams)) {
        failures.add("State '" + interaction.state() + "' gave different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      operation.put("path", interaction.path());
      operation.put("status", interaction.status());
      if (interaction.recordsAnswer()) {
        operation.put("file", file);
      }
      if (!interaction.headers().isEmpty()) {
        ObjectNode headers = operation.putObject("headers");
        for (String name : interaction.headers()) {
          headers.put(
              name,
              response
                  .headers()
                  .firstValue(name)
                  .orElseThrow(() -> new AssertionError(file + " answered no " + name)));
        }
      }
      ObjectNode frozen = operation.putObject("frozen");
      frozen.putArray("ids");
      ArrayNode instantPaths = frozen.putArray("instants");
      instants.forEach(instantPaths::add);
      frozen.putArray("strings");
      if (!interaction.blanked().isEmpty()) {
        ArrayNode volatilePaths = frozen.putArray("volatile");
        interaction.blanked().forEach(volatilePaths::add);
      }
      frozen.putNull("listFilteredTo");
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      if (interaction.recordsAnswer()) {
        written.add(file);
        check(dir.resolve(file), GoldenJson.render(body), update, failures);
      }
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  /** The operation's answer, with the path's {@code {name}} segments filled from the params. */
  private HttpResponse<String> call(Interaction interaction, Map<String, String> params) throws Exception {
    String path = interaction.path();
    for (Map.Entry<String, String> param : params.entrySet()) {
      path = path.replace("{" + param.getKey() + "}", param.getValue());
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(base.toString()).resolve(path))
            .method(interaction.method(), HttpRequest.BodyPublishers.noBody())
            .header("Accept", "application/json")
            .build();
    HttpResponse<String> response;
    try (HttpClient client = HttpClient.newHttpClient()) {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + path
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + response.body());
    }
    return response;
  }

  /**
   * Sets the strings at {@code segments} (from {@code at} on) to {@code ""}; a segment ending in
   * {@code [*]} names every member of that object or element of that array (a packument's {@code
   * versions}, keyed by version). Answers how many it set — qits-artifacts' rule, unchanged.
   */
  static int blank(JsonNode node, String[] segments, int at) {
    String segment = segments[at];
    boolean each = segment.endsWith("[*]");
    String key = each ? segment.substring(0, segment.length() - 3) : segment;
    if (!(node instanceof ObjectNode object) || !object.has(key)) {
      return 0;
    }
    JsonNode child = object.get(key);
    boolean last = at == segments.length - 1;
    if (!each) {
      if (last) {
        if (!child.isTextual()) {
          return 0;
        }
        object.put(key, "");
        return 1;
      }
      return blank(child, segments, at + 1);
    }
    List<JsonNode> members = new ArrayList<>();
    child.elements().forEachRemaining(members::add);
    int set = 0;
    for (JsonNode member : members) {
      set += last ? 0 : blank(member, segments, at + 1);
    }
    return set;
  }

  /**
   * The body with every whole-string instant replaced by {@link #FROZEN_INSTANT}; each replaced
   * value's path ({@code $.a.b}, array elements as {@code [*]}) is added to {@code paths}.
   */
  static JsonNode freeze(JsonNode node, String path, Set<String> paths) {
    if (node.isTextual() && INSTANT.matcher(node.asText()).matches()) {
      paths.add(path);
      return TextNode.valueOf(FROZEN_INSTANT);
    }
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      List<String> names = new ArrayList<>();
      object.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        object.set(name, freeze(object.get(name), path + "." + name, paths));
      }
    } else if (node.isArray()) {
      ArrayNode array = (ArrayNode) node;
      for (int i = 0; i < array.size(); i++) {
        array.set(i, freeze(array.get(i), path + "[*]", paths));
      }
    }
    return node;
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  /** Every committed {@code .json} under the directory, relative and {@code /}-separated. */
  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
