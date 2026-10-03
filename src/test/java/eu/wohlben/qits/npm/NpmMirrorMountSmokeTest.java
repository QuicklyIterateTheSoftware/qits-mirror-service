package eu.wohlben.qits.npm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.mirror.MirrorRepositorySeeder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The npm cache on its SECOND mount, {@code /npm} — at the root of this service's own hostname,
 * which is the one the edge reaches it on: the hostname alone picks the application, so every path
 * on this service's own host is this service's own, including {@code /npm}; {@code /artifacts} is
 * qits-artifacts' route on qits-artifacts' own host, not on this one.
 *
 * <p>What this adds over qits-registries-npm's {@code NpmMirrorMountTest} is this service's own
 * configuration: that the mount is switched on here, at the path the README and the client
 * {@code .npmrc} name, and that {@code /artifacts/npm} still answers beside it. The claim that
 * matters is about {@code dist.tarball}: a packument names its tarballs by absolute url, so a
 * document fetched through the edge must name tarballs on the host the client dialled AND on the
 * mount it dialled — otherwise {@code npm install} resolves through the edge and then 404s on every
 * download.
 *
 * <p>The profile is {@link NpmCacheSmokeTest}'s own, so this class costs no Quarkus restart.
 */
@QuarkusTest
@TestProfile(NpmCacheSmokeTest.AgainstTheStubUpstream.class)
class NpmMirrorMountSmokeTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String RUN = java.util.UUID.randomUUID().toString().substring(0, 8);

  /** What the edge forwards for a client that dialled https://mirror.example. */
  private static final String PUBLIC = "https://mirror.example";

  @TestHTTPResource("/")
  URL root;

  @Inject MirrorRepositorySeeder seeder;

  @ConfigProperty(name = "quarkus.quinoa.ignored-path-prefixes")
  List<String> spaIgnoredPrefixes;

  @BeforeEach
  void seedAndResetUpstream() {
    seeder.ensureDefaults();
    StubNpmRegistry.INSTANCE.reset();
  }

  @Test
  void throughTheEdgeThePackumentNamesTarballsOnTheMirrorMountAndThePublicHost() {
    TinyPackage subject = upstream("edge-" + RUN + "-" + UNIQUE.incrementAndGet());

    try (NpmClient viaEdge = throughTheEdge(); NpmClient npm = inNetwork()) {
      HttpResponse<String> packument =
          viaEdge.packumentAt("npm", MirrorRepositorySeeder.NPM_CACHE, subject.name());
      assertEquals(200, packument.statusCode(), packument.body());
      String tarballUrl = NpmClient.tarballUrl(NpmClient.parse(packument.body()), "1.0.0");
      assertEquals(
          PUBLIC + "/npm/npmjs/" + subject.name() + "/-/" + subject.tarballFile(),
          tarballUrl);

      // The same path on this process: the bytes, and the HEAD twin a probing client sends first.
      String here = tarballUrl.replace(PUBLIC + "/", root.toString());
      assertEquals(200, npm.head(here).statusCode());
      HttpResponse<byte[]> tarball = npm.tarball(here);
      assertEquals(200, tarball.statusCode());
      assertArrayEquals(subject.tarball(), tarball.body());
    }
  }

  @Test
  void aScopedPackageResolvesAndDownloadsOnTheMirrorMount() {
    TinyPackage subject = upstream("@mirrored/pkg-" + RUN + "-" + UNIQUE.incrementAndGet());

    try (NpmClient viaEdge = throughTheEdge(); NpmClient npm = inNetwork()) {
      // npm encodes the scope separator for a packument and follows the tarball url verbatim.
      HttpResponse<String> packument =
          viaEdge.packumentAt(
              "npm", MirrorRepositorySeeder.NPM_CACHE, subject.name().replace("/", "%2f"));
      assertEquals(200, packument.statusCode(), packument.body());
      String tarballUrl = NpmClient.tarballUrl(NpmClient.parse(packument.body()), "1.0.0");
      assertEquals(
          PUBLIC + "/npm/npmjs/" + subject.name() + "/-/" + subject.tarballFile(),
          tarballUrl);

      HttpResponse<byte[]> tarball = npm.tarball(tarballUrl.replace(PUBLIC + "/", root.toString()));
      assertEquals(200, tarball.statusCode());
      assertArrayEquals(subject.tarball(), tarball.body());
    }
  }

  @Test
  void theArtifactsMountIsUnchangedInNetworkAndFollowsTheForwardedHost() {
    TinyPackage subject = upstream("unmoved-" + RUN + "-" + UNIQUE.incrementAndGet());
    String tail = "/artifacts/npm/npmjs/" + subject.name() + "/-/" + subject.tarballFile();

    // In-network, no forwarding hop: exactly today's url — this process' authority, /artifacts.
    try (NpmClient npm = inNetwork()) {
      JsonNode packument = npm.packumentJson(MirrorRepositorySeeder.NPM_CACHE, subject.name());
      assertEquals(
          root.toString().replaceAll("/$", "") + tail, NpmClient.tarballUrl(packument, "1.0.0"));
    }
    // Forwarded: the host the client dialled, still on the mount it dialled.
    try (NpmClient viaEdge = throughTheEdge()) {
      JsonNode packument = viaEdge.packumentJson(MirrorRepositorySeeder.NPM_CACHE, subject.name());
      assertEquals(PUBLIC + tail, NpmClient.tarballUrl(packument, "1.0.0"));
    }
  }

  @Test
  void theMirrorMountIsAMachinePathAndNeverTheSpa() {
    // A path with no handler behind it answers npm's JSON envelope from the registry's own
    // catch-all — a real route under /npm, which the SPA fallback (a late catch-all) never gets
    // ahead of.
    try (NpmClient npm = inNetwork()) {
      HttpResponse<String> miss = npm.get("npm/npmjs/-/v1/search?text=left-pad");
      assertEquals(404, miss.statusCode());
      assertTrue(miss.body().contains("\"error\""), "npm's envelope, never a page: " + miss.body());
    }
    // And the belt: Quinoa is off in tests, so the fallback itself cannot be exercised here — what
    // can be is that the mount sits inside a segment the fallback is told to leave alone.
    // Segment-matched, as Quinoa matches: /npm covers /npm/npmjs, /npmx would not.
    assertTrue(
        spaIgnoredPrefixes.stream()
            .anyMatch(prefix -> "/npm/npmjs".equals(prefix) || "/npm/npmjs".startsWith(prefix + "/")),
        "quarkus.quinoa.ignored-path-prefixes must cover /npm/npmjs; is " + spaIgnoredPrefixes);
  }

  private TinyPackage upstream(String name) {
    TinyPackage subject = TinyPackage.of(name, "1.0.0");
    StubNpmRegistry.INSTANCE.hostPackage(subject);
    return subject;
  }

  private NpmClient throughTheEdge() {
    return inNetwork()
        .header("X-Forwarded-Host", "mirror.example")
        .header("X-Forwarded-Proto", "https");
  }

  private NpmClient inNetwork() {
    return new NpmClient(URI.create(root.toString()));
  }
}
