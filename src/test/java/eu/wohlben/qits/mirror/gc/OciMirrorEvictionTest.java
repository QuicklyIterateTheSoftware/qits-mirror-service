package eu.wohlben.qits.mirror.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    CacheEvictionSweep.SweepReport report = sweep.run(ImagePins.ABSENT, false);

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

    CacheEvictionSweep.SweepReport report = sweep.run(ImagePins.ABSENT, false);

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

  // === qits-2253: the mirror keeps only what is pinned ==========================================

  private static ImagePins pins(String... nameColonTag) {
    return ImagePins.of(
        java.util.Arrays.stream(nameColonTag).map(ImagePins.ImagePin::parse).toList());
  }

  private static final String MIRROR_PIN =
      "quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25";

  @Test
  void aPinnedTagNobodyPulledForMonthsIsKeptAndSaysWhy() throws Exception {
    // The owner's rule: what is pinned stays, however cold. The quay/… form names the quay
    // namespace and the rest of the path is the image inside it.
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(200));

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now(), pins(MIRROR_PIN));

    JudgedIdentity tag = judged(plan.kept(), MIRROR_IMAGE + ":jdk-25");
    assertEquals(OciMirrorEvictor.PINNED, tag.rule());
    assertTrue(tag.regardlessOfWindow());
    assertEquals(
        List.of(MIRROR_IMAGE + "@sha256:" + mirror.child()),
        identities(plan.dead()),
        "the index's lazily pulled child keeps the window rule — only tags are pinned");
    assertEquals(CacheEviction.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
  }

  @Test
  void aTagNoPinNamesDiesTheMinuteItWasCachedAndSaysWhy() throws Exception {
    // The other half: freshly pulled, inside every window, and named by no pin — condemned.
    seedMirror();

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now(), pins("node:24-alpine"));

    JudgedIdentity tag = judged(plan.dead(), MIRROR_IMAGE + ":jdk-25");
    assertEquals(OciMirrorEvictor.UNPINNED, tag.rule());
    assertEquals("unpinned: no image pin names this tag", tag.rule());
    assertTrue(tag.regardlessOfWindow());
  }

  @Test
  void aBareHubNamePinsTheLibraryImageItPullsAs() throws Exception {
    // `FROM node:24-alpine` is a Hub pull of library/node, cached in the hub namespace; the pin
    // keeps exactly that tag and no sibling of it.
    seedImage("hub", "library/node", "24-alpine", (byte) 21);
    seedImage("hub", "library/node", "22-alpine", (byte) 22);

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now(), pins("node:24-alpine"));

    assertEquals(OciMirrorEvictor.PINNED, judged(plan.kept(), "library/node:24-alpine").rule());
    assertEquals(OciMirrorEvictor.UNPINNED, judged(plan.dead(), "library/node:22-alpine").rule());
  }

  @Test
  void aPinSpelledWithTheHubNamespaceNamesTheSameTag() throws Exception {
    // hub/library/postgres and hub/postgres are one pull, and so one pin.
    seedImage("hub", "library/postgres", "18.4", (byte) 23);
    seedImage("hub", "library/postgres", "17.6", (byte) 24);

    EvictionPlan plan =
        engine.plan(evictor, WINDOW, Instant.now(), pins("hub/library/postgres:18.4", "hub/postgres:17.6"));

    assertEquals(
        List.of("library/postgres:17.6", "library/postgres:18.4"), identities(plan.kept()));
    assertEquals(List.of(), plan.dead());
  }

  @Test
  void aConfiguredPinKeepsItsTagBesideTheCallersPins() throws Exception {
    // qits.mirror.eviction.oci-mirror.config-pins ships moby/buildkit:v0.33.0 and
    // hub/nicolargo/glances:4.5.6-full: images pinned in service config that no maintenance scan
    // sees. The caller's pins never name them, and they stay all the same.
    seedImage("hub", "moby/buildkit", "v0.33.0", (byte) 25);
    seedImage("hub", "nicolargo/glances", "4.5.6-full", (byte) 26);
    seedImage("hub", "moby/buildkit", "v0.32.0", (byte) 27);

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now(), pins("node:24-alpine"));

    assertEquals(
        List.of("moby/buildkit:v0.33.0", "nicolargo/glances:4.5.6-full"), identities(plan.kept()));
    assertEquals(List.of("moby/buildkit:v0.32.0"), identities(plan.dead()));
  }

  @Test
  void withoutPinsEveryTagKeepsTheWindowRuleAndNothingNewIsCondemned() throws Exception {
    // The orchestrator could not read the pins: a failed read is not "nothing is pinned". The young
    // unpinned tag stays on the window, and so does a configured one — config pins are not read.
    seedMirror();
    seedImage("hub", "moby/buildkit", "v0.32.0", (byte) 28);

    EvictionPlan plan = engine.plan(evictor, WINDOW, Instant.now(), ImagePins.ABSENT);

    assertEquals(List.of(), plan.dead());
    assertEquals(CacheEviction.keptAccessed(WINDOW), judged(plan.kept(), MIRROR_IMAGE + ":jdk-25").rule());
    assertTrue(plan.kept().stream().noneMatch(JudgedIdentity::regardlessOfWindow));
  }

  @Test
  void anUnpinnedTagOverYoungBytesIsWithheldWholeByTheRun() throws Exception {
    // Unpinned outranks the window, never the grace window — the strand hazard is the same.
    MirrorStore mirror = seedMirror();
    backdate(mirror.layer(), Duration.ZERO);

    CacheEvictionSweep.SweepReport report = sweep.run(pins("node:24-alpine"), false);

    CacheEvictionSweep.TypeResult oci = report.type(OciMirrorProfile.KEY);
    assertEquals(1, oci.unpinned());
    assertEquals(0, oci.superseded());
    assertEquals(0, oci.deleted());
    assertEquals(1, oci.withheldByGraceWindow());
    ociTags.getEntityManager().clear();
    assertTrue(ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isPresent(), "the row stays");
    assertTrue(blobStore.exists(mirror.layer()));
  }

  @Test
  void aRunWithPinsDeletesTheUnpinnedTagAndCountsBothSidesOnItsLine() throws Exception {
    seedMirror();
    seedImage("hub", "library/node", "24-alpine", (byte) 29);

    CacheEvictionSweep.SweepReport report = sweep.run(pins("node:24-alpine"), false);

    CacheEvictionSweep.TypeResult oci = report.type(OciMirrorProfile.KEY);
    assertTrue(report.pinsSupplied());
    assertEquals(1, report.imagePins());
    assertEquals(1, oci.unpinned());
    assertEquals(1, oci.pinned());
    assertEquals(1, oci.deleted());
    ociTags.getEntityManager().clear();
    assertTrue(ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isEmpty());
    assertTrue(ociTags.findOne("hub", "library/node", "24-alpine").isPresent());
    String line = CacheEvictionSweep.summary(report);
    assertTrue(line.contains(OciMirrorProfile.KEY + "=1 condemned (1 unpinned)/1 deleted/"), line);
    assertTrue(line.contains(" kept (1 pinned)"), line);
  }

  @Test
  void aDryRunWithPinsSaysWhatWouldGoAndDeletesNothing() throws Exception {
    MirrorStore mirror = seedMirror();

    CacheEvictionSweep.SweepReport report = sweep.run(pins("node:24-alpine"), true);

    assertTrue(report.dryRun(), "the caller's dryRun is honoured with the config's false");
    assertEquals(1, report.type(OciMirrorProfile.KEY).unpinned());
    assertEquals(0, report.type(OciMirrorProfile.KEY).deleted());
    assertNull(report.disk(), "and nothing compacts");
    ociTags.getEntityManager().clear();
    assertTrue(ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isPresent());
    assertTrue(blobStore.exists(mirror.layer()));
  }

  @Test
  void aSecondRunWhileOneIsInFlightIsRefusedAndTouchesNothing() throws Exception {
    seedMirror();
    sweep.inFlight().set(true);
    try {
      assertThrows(
          CacheEvictionSweep.SweepInProgress.class, () -> sweep.run(pins("node:24-alpine"), false));
    } finally {
      sweep.inFlight().set(false);
    }
    ociTags.getEntityManager().clear();
    assertTrue(ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isPresent());
  }

  private static JudgedIdentity judged(List<JudgedIdentity> lines, String identity) {
    return lines.stream()
        .filter(line -> line.identity().equals(identity))
        .findFirst()
        .orElseThrow(() -> new AssertionError(identity + " not in " + identities(lines)));
  }
}
