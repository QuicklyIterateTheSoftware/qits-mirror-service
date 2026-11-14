package eu.wohlben.qits.mirror.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.OciMediaTypes;
import eu.wohlben.qits.artifacts.control.OciMirrorProfile;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The mirror's live rule: cold content dies, warm content stays, and the mechanisms that keep an
 * eviction from breaking something — the grace window, the funnel's own cleanup, the index closure —
 * are asserted rather than assumed.
 */
@QuarkusTest
class OciMirrorEvictionTest extends EvictionFixture {

  private static final Duration WINDOW = Duration.ofDays(30);

  @Inject OciMirrorEvictor evictor;

  @Inject CacheEvictionSweep sweep;

  private final CacheEviction engine = new CacheEviction();

  @Test
  void aTagNobodyHasPulledInsideTheWindowDiesAndItsFreshnessRowGoesWithIt() throws Exception {
    // The rule end to end: a cached tag cold for two months is evicted, its row is gone, and so is
    // the oci_mirror_tag_check row the miss path wrote beside it — a freshness row for a tag that no
    // longer exists is a row nothing would ever read or delete again.
    MirrorStore mirror = seedMirror();
    mirrorTagCheck("jdk-25", Instant.now().minus(Duration.ofDays(60)));
    ageMirrorRows(Duration.ofDays(60));

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());
    CacheEvictor.Applied applied = evictor.delete(plan, blobId -> false);

    assertEquals(OciMirrorProfile.KEY, evictor.typeKey());
    assertEquals(
        List.of(MIRROR_IMAGE + ":jdk-25", MIRROR_IMAGE + "@sha256:" + mirror.child()),
        identities(plan.dead()),
        "the tag, and the child manifest no tag names — upstream drift leaves both behind");
    assertEquals(List.of(), plan.kept());
    assertEquals(
        List.of(MIRROR_IMAGE + ":jdk-25", MIRROR_IMAGE + "@sha256:" + mirror.child()),
        identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());

    ociTags.getEntityManager().clear();
    assertTrue(
        ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isEmpty(), "the tag row is gone");
    assertTrue(
        ociManifests.findOne(MIRROR_REPO, MIRROR_IMAGE, mirror.child()).isEmpty(),
        "the untagged child row is gone");
    assertTrue(
        mirrorTagChecks.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isEmpty(),
        "the freshness row travels with the tag, through the funnel rather than through a caller");
  }

  @Test
  void aTagSpelledLikeAReleaseDiesAllTheSameBecauseTheReleaseIsUpstreams() throws Exception {
    // The one rule this engine deliberately does NOT have. Upstream calls jdk-25 a release; keeping
    // it forever on that basis is how a mirror never shrinks. Version protection is own-ness's, and
    // this service holds nothing of its own.
    seedMirror();
    ageMirrorRows(Duration.ofDays(200));

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertTrue(identities(plan.dead()).contains(MIRROR_IMAGE + ":jdk-25"));
  }

  @Test
  void aTagPulledInsideTheWindowStaysAndBothLinesNameTheirRule() throws Exception {
    // The comparison a reviewer reads down the page: one identity kept, one condemned, each saying
    // why. "Kept" alone is not reviewable; "accessed inside the P30D window" is.
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(60));
    touchMirrorTag("jdk-25", Instant.now().minus(Duration.ofDays(2)));

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(MIRROR_IMAGE + ":jdk-25"), identities(plan.kept()));
    assertEquals(CacheEviction.keptAccessed(WINDOW), plan.kept().get(0).rule());
    assertEquals(List.of(MIRROR_IMAGE + "@sha256:" + mirror.child()), identities(plan.dead()));
    assertEquals(CacheEviction.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
  }

  @Test
  void aFreshlyCachedImageIsYoungRatherThanNeverPulled() throws Exception {
    // Creation counts as the first access. Without that, everything the mirror ever fetched would be
    // eligible the moment it landed — a cache that deletes what it just paid for.
    seedMirror();

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(), plan.dead(), "nothing has aged out of a store minutes old");
    assertEquals(2, plan.kept().size(), "the tag and the untagged child");
    assertEquals(
        census.take().live(OciMirrorProfile.KEY).keySet(),
        plan.blobsRetained(),
        "with nothing condemned, what this type retains is the census's own live set for it");
  }

  @Test
  void aChildManifestOfAKeptIndexIsEvictableAndItsBytesSurviveAnyway() throws Exception {
    // The lazy-pull bargain, stated as a test. An architecture nobody pulls ages out; the index that
    // lists it stays; and the child's bytes stay reachable through the index's closure, so the sweep
    // never unlinks them. Re-fetching that architecture costs one upstream request.
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(60));
    touchMirrorTag("jdk-25", Instant.now());

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(MIRROR_IMAGE + "@sha256:" + mirror.child()), identities(plan.dead()));
    assertTrue(plan.blobsReleased().contains(mirror.child()), "the dying row did name those bytes");
    assertTrue(
        plan.blobsRetained().contains(mirror.child()),
        "and the surviving index still does — the sweep subtracts, a plan never does");
    assertTrue(plan.blobsRetained().contains(mirror.index()));
  }

  @Test
  void anIdentityOverAYoungBlobIsWithheldWholeWithItsRowsIntact() throws Exception {
    // The strand hazard, carried over to this type: deleting a row while its file is inside the
    // grace window would leave the blob row-less — untouchable by construction, therefore never
    // reclaimed. The identity waits out the window with its file.
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(60));

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());
    CacheEvictor.Applied applied = evictor.delete(plan, blobId -> blobId.equals(mirror.layer()));

    assertEquals(List.of(), applied.deleted(), "the layer under both identities is too young");
    assertEquals(
        List.of(MIRROR_IMAGE + ":jdk-25", MIRROR_IMAGE + "@sha256:" + mirror.child()),
        identities(applied.withheldByGraceWindow()));
    assertEquals(List.of(), applied.errors(), "withheld is the window working, not an error");
    ociTags.getEntityManager().clear();
    assertTrue(ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isPresent(), "the row stays");
  }

  @Test
  void aTagReboundUpstreamCondemnsTheManifestItLeftBehindAlthoughThatManifestIsYoung()
      throws Exception {
    // The disk-filling case. jdk-25 moved upstream minutes ago and the pull bound it to new bytes;
    // the index it used to name is a row no tag reaches. Waiting out the window would park it for a
    // month, and a moving tag leaves one of these behind on every move.
    MirrorStore mirror = seedMirror();
    Rebound rebound = rebindMirrorTag(mirror);

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    JudgedIdentity superseded = judged(plan.dead(), MIRROR_IMAGE + "@sha256:" + mirror.index());
    assertEquals(OciMirrorEvictor.SUPERSEDED, superseded.rule());
    assertTrue(superseded.regardlessOfWindow(), "condemned on reachability, not on age");
    assertEquals(List.of(MIRROR_IMAGE + ":jdk-25"), identities(plan.kept()), "the tag is warm");
    assertTrue(plan.blobsRetained().contains(rebound.manifest()), "and names its new bytes");
  }

  @Test
  void aYoungSupersededIndexTakesItsUnreachableChildrenWithIt() throws Exception {
    // The child was reachable only through the superseded index, so it is unreachable too — and
    // goes on the same run rather than on the run after the index has gone.
    MirrorStore mirror = seedMirror();
    rebindMirrorTag(mirror);

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());
    CacheEvictor.Applied applied = evictor.delete(plan, blobId -> false);

    List<String> both =
        java.util.stream.Stream.of(mirror.index(), mirror.child())
            .map(digest -> MIRROR_IMAGE + "@sha256:" + digest)
            .sorted()
            .toList();
    assertEquals(both, identities(plan.dead()));
    assertTrue(
        plan.dead().stream()
            .allMatch(
                dead ->
                    dead.regardlessOfWindow() && dead.rule().equals(OciMirrorEvictor.SUPERSEDED)));
    assertEquals(2, plan.condemnedRegardlessOfWindow());
    assertEquals(both, identities(applied.deleted()));
    ociManifests.getEntityManager().clear();
    assertTrue(ociManifests.findOne(MIRROR_REPO, MIRROR_IMAGE, mirror.index()).isEmpty());
    assertTrue(ociManifests.findOne(MIRROR_REPO, MIRROR_IMAGE, mirror.child()).isEmpty());
  }

  @Test
  void aChildTheCurrentIndexStillListsIsJudgedByTheWindowAndStaysWhileYoung() throws Exception {
    // Reachability is the closure, not the tag alone. The tag moves to a new index that still lists
    // the seeded child: the old index is superseded, the child is not — it keeps the lazy-pull rule.
    MirrorStore mirror = seedMirror();
    long childSize =
        ociManifests.findOne(MIRROR_REPO, MIRROR_IMAGE, mirror.child()).orElseThrow().size;
    byte[] indexBytes = indexManifest(Map.of(mirror.child(), childSize));
    String index = store(indexBytes);
    mirrorManifestRow(index, indexBytes, OciMediaTypes.OCI_INDEX_V1);
    rebindMirrorTag("jdk-25", index);

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(MIRROR_IMAGE + "@sha256:" + mirror.index()), identities(plan.dead()));
    assertEquals(
        List.of(MIRROR_IMAGE + ":jdk-25", MIRROR_IMAGE + "@sha256:" + mirror.child()).stream()
            .sorted()
            .toList(),
        identities(plan.kept()));
    assertEquals(
        CacheEviction.keptAccessed(WINDOW),
        judged(plan.kept(), MIRROR_IMAGE + "@sha256:" + mirror.child()).rule());
  }

  @Test
  void aSupersededManifestOverAYoungBlobIsWithheldWholeByTheRun() throws Exception {
    // Superseded outranks the window, never the grace window: a row deleted over a young file would
    // strand that file row-less for good.
    MirrorStore mirror = seedMirror();
    rebindMirrorTag(mirror);
    backdate(mirror.child(), Duration.ZERO);

    CacheEvictionSweep.SweepReport report = sweep.run();

    CacheEvictionSweep.TypeResult oci = report.type(OciMirrorProfile.KEY);
    assertEquals(2, oci.condemned());
    assertEquals(2, oci.superseded());
    assertEquals(0, oci.deleted());
    assertEquals(2, oci.withheldByGraceWindow(), "the index and the child, both over that blob");
    ociManifests.getEntityManager().clear();
    assertTrue(ociManifests.findOne(MIRROR_REPO, MIRROR_IMAGE, mirror.index()).isPresent());
    assertTrue(ociManifests.findOne(MIRROR_REPO, MIRROR_IMAGE, mirror.child()).isPresent());
    assertTrue(blobStore.exists(mirror.child()));
  }

  @Test
  void layersTheSupersededAndTheNewManifestShareSurviveTheRun() throws Exception {
    // The census's half of the bargain. The superseded rows go and their own bytes with them; the
    // config and layer the new manifest still names stay, because the sweep subtracts across every
    // survivor.
    MirrorStore mirror = seedMirror();
    Rebound rebound = rebindMirrorTag(mirror);

    CacheEvictionSweep.SweepReport report = sweep.run();

    assertEquals(2, report.type(OciMirrorProfile.KEY).deleted());
    assertFalse(blobStore.exists(mirror.index()), "the superseded index's own bytes are freed");
    assertFalse(blobStore.exists(mirror.child()), "and so are the unreachable child's");
    assertTrue(blobStore.exists(mirror.layer()), "the shared layer is still served");
    assertTrue(blobStore.exists(mirror.config()), "and so is the shared config");
    assertTrue(blobStore.exists(rebound.newLayer()));
    assertTrue(blobStore.exists(rebound.manifest()));
  }

  @Test
  void aStoreWithNoMirroredImageIsAnEmptyPlanRatherThanAFailure() throws Exception {
    // The shipped state of a deployment that has not pulled anything through yet. The three mirror
    // namespaces are there from V1 and hold nothing.
    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now());

    assertEquals(List.of(), plan.kept());
    assertEquals(List.of(), plan.dead());
    assertEquals(Set.of(), plan.blobsRetained());
  }

  private static JudgedIdentity judged(List<JudgedIdentity> lines, String identity) {
    return lines.stream()
        .filter(line -> line.identity().equals(identity))
        .findFirst()
        .orElseThrow(() -> new AssertionError(identity + " not in " + identities(lines)));
  }
}
