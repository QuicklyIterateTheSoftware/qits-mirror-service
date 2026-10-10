package eu.wohlben.qits.mirror.contracts;

import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.mirror.MirrorRepositorySeeder;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * <b>qits-mirror's provider states</b> (ticket qits-1149): each sets up the store one consumer
 * situation needs and hands back its parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before it records each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 *
 * <p><b>A state can run twice.</b> Each one ensures the default cache roots (idempotent) and
 * replaces the rows it owns before writing them, so applying it again gives the same answer.
 */
@ApplicationScoped
public class ProviderStates {

  /** The five cache roots this service registers, and nothing cached in them. */
  public static final String THE_DEFAULT_CACHES = "the default caches";

  /** The npm cache holding one package version with one dist tag. */
  public static final String AN_NPM_CACHE_HOLDING_A_PACKAGE = "an npm cache holding a package";

  static final String NPM_PACKAGE = "left-pad";
  static final String NPM_VERSION = "1.3.0";
  static final int NPM_TARBALL_BYTES = 70;
  static final Instant CACHED_AT = Instant.parse("2026-01-01T00:00:00Z");

  /** What a state hands back: its parameters, keys sorted. */
  public record Setup(Map<String, String> params) {}

  @Inject MirrorRepositorySeeder seeder;
  @Inject BlobStore blobStore;

  @Inject
  @DataSource("mirror")
  AgroalDataSource database;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(THE_DEFAULT_CACHES, this::theDefaultCaches);
    states.put(AN_NPM_CACHE_HOLDING_A_PACKAGE, this::anNpmCacheHoldingAPackage);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /**
   * The two package caches ({@code npmjs}, {@code central}) and the three OCI namespaces ({@code
   * hub}, {@code quay}, {@code redhat}, from the V1 prefill), with nothing cached in any of them.
   */
  private Setup theDefaultCaches() {
    seeder.ensureDefaults();
    clearNpmCache();
    return new Setup(Map.of());
  }

  /**
   * The default caches, and in {@code npmjs} the package {@code left-pad} at {@code 1.3.0}: a
   * 70-byte tarball, tagged {@code latest}.
   */
  private Setup anNpmCacheHoldingAPackage() {
    seeder.ensureDefaults();
    clearNpmCache();
    byte[] tarball = new byte[NPM_TARBALL_BYTES];
    Arrays.fill(tarball, (byte) 11);
    BlobStore.StagedBlob staged =
        blobStore.stage(new ByteArrayInputStream(tarball), Long.MAX_VALUE);
    blobStore.promote(staged);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmVersion version = new NpmVersion();
              version.repository = MirrorRepositorySeeder.NPM_CACHE;
              version.packageName = NPM_PACKAGE;
              version.version = NPM_VERSION;
              version.tarballBlobId = staged.sha256();
              version.manifestJson = "{}";
              version.createdAt = CACHED_AT;
              version.accessedAt = CACHED_AT;
              version.persist();
              NpmDistTag tag = new NpmDistTag();
              tag.repository = MirrorRepositorySeeder.NPM_CACHE;
              tag.packageName = NPM_PACKAGE;
              tag.tag = "latest";
              tag.version = NPM_VERSION;
              tag.updatedAt = CACHED_AT;
              tag.persist();
            });
    Map<String, String> params = new TreeMap<>();
    params.put("repository", MirrorRepositorySeeder.NPM_CACHE);
    return new Setup(Collections.unmodifiableMap(params));
  }

  /** Empties the npm cache's version and tag rows, so a state owns what it lists. */
  private void clearNpmCache() {
    for (String table : new String[] {"npm_dist_tag", "npm_version"}) {
      String sql = "delete from " + table + " where repository = ?";
      try (Connection connection = database.getConnection();
          PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, MirrorRepositorySeeder.NPM_CACHE);
        statement.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException(sql, e);
      }
    }
  }
}
