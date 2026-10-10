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
 * and its path goes in the index's {@code frozen.instants}. Nothing else varies between runs.
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
  record Interaction(String state, String operationId, String method, String path, int status) {}

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
              200));

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
      JsonNode body = call(interaction, params);
      Set<String> instants = new LinkedHashSet<>();
      body = freeze(body, "$", instants);
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
      operation.put("file", file);
      ObjectNode frozen = operation.putObject("frozen");
      frozen.putArray("ids");
      ArrayNode instantPaths = frozen.putArray("instants");
      instants.forEach(instantPaths::add);
      frozen.putArray("strings");
      frozen.putNull("listFilteredTo");
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      written.add(file);
      check(dir.resolve(file), GoldenJson.render(body), update, failures);
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
  private JsonNode call(Interaction interaction, Map<String, String> params) throws Exception {
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
    return JSON.readTree(response.body());
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
