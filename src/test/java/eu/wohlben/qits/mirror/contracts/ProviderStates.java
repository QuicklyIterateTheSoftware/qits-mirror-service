package eu.wohlben.qits.mirror.contracts;

import eu.wohlben.qits.artifacts.control.NpmPackageName;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.maven.ContractMavenUpstream;
import eu.wohlben.qits.maven.MavenUpstream;
import eu.wohlben.qits.mirror.MirrorRepositorySeeder;
import eu.wohlben.qits.npm.ContractNpmUpstream;
import eu.wohlben.qits.npm.NpmUpstream;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
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
 *
 * <p><b>The cache states have an upstream, and it is a stub.</b> The four states qits-maintenance
 * reads through the caches ({@code LatestResolver}, for an EXTERNAL pin) point the running proxies
 * at {@code StubMavenRepository} / {@code StubNpmRegistry} — see {@link ContractMavenUpstream} for
 * why the bean is re-pointed rather than the profile — and publish there what the state names. A
 * "cached" state then reads it through the proxy once, so the row really is in the cache before the
 * consumer asks; a "no ... upstream" state publishes nothing, and the 404 is the proxy's answer to
 * an upstream that was asked and has no such name.
 */
@ApplicationScoped
public class ProviderStates {

  /** The five cache roots this service registers, and nothing cached in them. */
  public static final String THE_DEFAULT_CACHES = "the default caches";

  /** The npm cache holding one package version with one dist tag. */
  public static final String AN_NPM_CACHE_HOLDING_A_PACKAGE = "an npm cache holding a package";

  /**
   * qits-maintenance's external maven pin: Central lists three versions of it, and the mirror has
   * read its {@code maven-metadata.xml} once.
   */
  public static final String A_CACHED_MAVEN_ARTIFACT =
      "a cached maven artifact with published versions";

  /**
   * qits-maintenance's external npm pin: npmjs lists two versions, the newer tagged {@code latest},
   * and the mirror has read the packument once.
   */
  public static final String A_CACHED_NPM_PACKAGE = "a cached npm package with published versions";

  /** A maven artifact Central does not have, and so neither does the cache: metadata answers 404. */
  public static final String NO_MAVEN_ARTIFACT_UPSTREAM = "no maven artifact of that name upstream";

  /** An npm package npmjs does not have, and so neither does the cache: the packument answers 404. */
  public static final String NO_NPM_PACKAGE_UPSTREAM = "no npm package of that name upstream";

  static final String MAVEN_GROUP = "org.example";
  static final String MAVEN_ARTIFACT = "contract-library";
  static final String MAVEN_ABSENT_ARTIFACT = "contract-absent";
  static final String[] MAVEN_VERSIONS = {"1.0.0", "1.1.0", "2.0.0"};
  static final String NPM_CACHED_PACKAGE = "contract-package";
  static final String NPM_ABSENT_PACKAGE = "contract-absent-package";
  static final String[] NPM_VERSIONS = {"1.0.0", "1.1.0"};

  static final String NPM_PACKAGE = "left-pad";
  static final String NPM_VERSION = "1.3.0";
  static final int NPM_TARBALL_BYTES = 70;
  static final Instant CACHED_AT = Instant.parse("2026-01-01T00:00:00Z");

  /** What a state hands back: its parameters, keys sorted. */
  public record Setup(Map<String, String> params) {}

  @Inject MirrorRepositorySeeder seeder;
  @Inject BlobStore blobStore;
  @Inject MavenUpstream mavenUpstream;
  @Inject NpmUpstream npmUpstream;

  @Inject
  @DataSource("mirror")
  AgroalDataSource database;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(THE_DEFAULT_CACHES, this::theDefaultCaches);
    states.put(AN_NPM_CACHE_HOLDING_A_PACKAGE, this::anNpmCacheHoldingAPackage);
    states.put(A_CACHED_MAVEN_ARTIFACT, this::aCachedMavenArtifact);
    states.put(A_CACHED_NPM_PACKAGE, this::aCachedNpmPackage);
    states.put(NO_MAVEN_ARTIFACT_UPSTREAM, this::noMavenArtifactUpstream);
    states.put(NO_NPM_PACKAGE_UPSTREAM, this::noNpmPackageUpstream);
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
    clearMavenCache();
    return new Setup(Map.of());
  }

  /**
   * The default caches, and in {@code npmjs} the package {@code left-pad} at {@code 1.3.0}: a
   * 70-byte tarball, tagged {@code latest}.
   */
  private Setup anNpmCacheHoldingAPackage() {
    seeder.ensureDefaults();
    clearNpmCache();
    clearMavenCache();
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

  /**
   * Central publishes {@code org.example:contract-library} at {@code 1.0.0}, {@code 1.1.0} and
   * {@code 2.0.0}, and the {@code central} cache has read its {@code maven-metadata.xml}.
   */
  private Setup aCachedMavenArtifact() {
    seeder.ensureDefaults();
    clearNpmCache();
    clearMavenCache();
    ContractMavenUpstream.pointAtTheStub(mavenUpstream);
    ContractMavenUpstream.hostMetadata(MAVEN_GROUP, MAVEN_ARTIFACT, MAVEN_VERSIONS);
    inRequestContext(
        () ->
            mavenUpstream.metadata(
                MirrorRepositorySeeder.MAVEN_CACHE, mavenMetadataPath(MAVEN_ARTIFACT)));
    return mavenParams(MAVEN_ARTIFACT);
  }

  /** Central has no {@code org.example:contract-absent}, and the cache holds nothing of it. */
  private Setup noMavenArtifactUpstream() {
    seeder.ensureDefaults();
    clearNpmCache();
    clearMavenCache();
    ContractMavenUpstream.pointAtTheStub(mavenUpstream);
    return mavenParams(MAVEN_ABSENT_ARTIFACT);
  }

  /**
   * npmjs publishes {@code contract-package} at {@code 1.0.0} and {@code 1.1.0}, {@code latest} on
   * the newer, and the {@code npmjs} cache has read the packument.
   */
  private Setup aCachedNpmPackage() {
    seeder.ensureDefaults();
    clearNpmCache();
    clearMavenCache();
    ContractNpmUpstream.pointAtTheStub(npmUpstream);
    ContractNpmUpstream.hostVersions(NPM_CACHED_PACKAGE, NPM_VERSIONS);
    inRequestContext(
        () ->
            npmUpstream.packument(
                MirrorRepositorySeeder.NPM_CACHE, NpmPackageName.parse(NPM_CACHED_PACKAGE)));
    return npmParams(NPM_CACHED_PACKAGE);
  }

  /** npmjs has no {@code contract-absent-package}, and the cache holds nothing of it. */
  private Setup noNpmPackageUpstream() {
    seeder.ensureDefaults();
    clearNpmCache();
    clearMavenCache();
    ContractNpmUpstream.pointAtTheStub(npmUpstream);
    return npmParams(NPM_ABSENT_PACKAGE);
  }

  /**
   * A maven state's params: the coordinate as qits-maintenance names it ({@code name}, {@code
   * groupId:artifactId}) and as the path spells it ({@code groupPath}, {@code artifact}) — the keys
   * qits-artifacts' own maven states use, so a consumer reads both providers the same way.
   */
  private static Setup mavenParams(String artifact) {
    Map<String, String> params = new TreeMap<>();
    params.put("artifact", artifact);
    params.put("groupPath", MAVEN_GROUP.replace('.', '/'));
    params.put("name", MAVEN_GROUP + ":" + artifact);
    return new Setup(Collections.unmodifiableMap(params));
  }

  /** An npm state's params: the package as qits-maintenance names it, and as the path does. */
  private static Setup npmParams(String pkg) {
    Map<String, String> params = new TreeMap<>();
    params.put("name", pkg);
    params.put("package", pkg);
    return new Setup(Collections.unmodifiableMap(params));
  }

  private static String mavenMetadataPath(String artifact) {
    return MAVEN_GROUP.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
  }

  /** Runs a proxy read off the test thread's request, the way a route would run it. */
  private static void inRequestContext(Runnable read) {
    ManagedContext context = Arc.container().requestContext();
    boolean active = context.isActive();
    if (!active) {
      context.activate();
    }
    try {
      read.run();
    } finally {
      if (!active) {
        context.terminate();
      }
    }
  }

  /**
   * Empties the npm cache's version and tag rows and its cached upstream packuments, so a state
   * owns what it lists.
   */
  private void clearNpmCache() {
    delete(MirrorRepositorySeeder.NPM_CACHE, "npm_dist_tag", "npm_version", "npm_proxy_packument");
  }

  /** Empties the maven cache's cached upstream metadata, so a state owns what it serves. */
  private void clearMavenCache() {
    delete(MirrorRepositorySeeder.MAVEN_CACHE, "maven_proxy_metadata");
  }

  private void delete(String repository, String... tables) {
    for (String table : tables) {
      String sql = "delete from " + table + " where repository = ?";
      try (Connection connection = database.getConnection();
          PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, repository);
        statement.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException(sql, e);
      }
    }
  }
}
