package eu.wohlben.qits.mirror.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.NpmProxyProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The mode a deployment watches the counts in before it agrees with them: plan, report, delete
 * nothing.
 */
@QuarkusTest
@TestProfile(DryRunEvictionSweepTest.Reporting.class)
class DryRunEvictionSweepTest extends EvictionFixture {

  public static class Reporting implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.mirror.eviction.dry-run", "true");
    }
  }

  @Inject CacheEvictionSweep sweep;

  @Test
  void aDryRunSaysWhatWouldGoAndLeavesEveryRowAndEveryByteWhereItIs() throws Exception {
    NpmCache npm = seedNpmCache();

    CacheEvictionSweep.SweepReport report = sweep.run();

    assertTrue(report.dryRun());
    assertEquals(2, report.type(NpmProxyProfile.KEY).condemned(), "the cold version and document");
    assertEquals(0, report.type(NpmProxyProfile.KEY).deleted(), "and nothing was deleted");
    assertEquals(1, report.blobPlan().blobCount(), "one tarball would be reclaimed");
    assertEquals(NPM_COLD_TARBALL, report.blobPlan().reclaimableBytes());
    assertEquals(0, report.blobs().unlinked());

    npmVersions.getEntityManager().clear();
    assertTrue(npmVersions.findOne(NPM_CACHE, NPM_COLD_PACKAGE, "1.3.0").isPresent());
    assertTrue(npmProxyPackuments.findOne(NPM_CACHE, NPM_COLD_PACKAGE).isPresent());
    assertTrue(blobStore.exists(npm.coldTarball()));
  }

  @Test
  void theLineSaysItWasADryRunSoNobodyReadsItAsWorkDone() throws Exception {
    seedNpmCache();

    String line = CacheEvictionSweep.summary(sweep.run());

    assertTrue(line.startsWith("Cache eviction (dry run):"), line);
    assertTrue(line.contains("blobs=1 reclaimable"), line);
  }

  @Test
  void aDryRunMovesAndDropsNoSegment() throws Exception {
    // qits-2248's compaction is a write — it moves chunks under a blob's advisory lock and drops
    // partitions — so it belongs to an executed run and must never run here. A plan is read on
    // demand, as often as somebody asks, and has to stay reviewable without side effects: a dry
    // run that quietly compacted would move bytes an operator only meant to look at.
    String doomed = store(filled(4096 + 1, (byte) 9));
    int closed = openSegment();
    rollSegment();
    Instant longAgo = Instant.now().minus(Duration.ofDays(200));
    backdate(doomed, Duration.ofDays(30));
    npmVersionRow("dryrun-cold-pkg", "1.0.0", doomed, longAgo, longAgo);
    int open = openSegment();
    assertEquals(1, partitionCount(closed), "the case only says anything if the segment exists");

    CacheEvictionSweep.SweepReport report = sweep.run();

    assertTrue(report.dryRun());
    assertEquals(1, report.blobPlan().blobCount(), "the dry run would free the doomed tarball");
    assertNull(report.disk(), "a dry run compacts nothing and claims no disk");
    assertEquals(1, partitionCount(closed), "the closed segment's partition is untouched");
    assertEquals(open, openSegment(), "the open segment did not move either");
    assertTrue(blobStore.exists(doomed), "and the blob itself was never unlinked");

    String line = CacheEvictionSweep.summary(report);
    assertTrue(line.contains("blobs=1 reclaimable"), line);
  }

  /** The blob store's open segment: the partition every new chunk is written into. */
  private int openSegment() {
    return (int) count("select segment from blob_segment");
  }

  /**
   * Closes the open segment the way the store's roll does, minus the locks a single-threaded case
   * does not need: a partition for the next segment, and the open number moved onto it. The roll
   * itself fires only once a segment passes {@code qits.artifacts.blobs.segment-size} (1 GiB), which
   * no fixture here comes near, and it is the library's to test.
   */
  private void rollSegment() {
    int next = openSegment() + 1;
    execute(
        "create table blob_chunk_" + next + " partition of blob_chunk for values in (" + next + ")");
    execute("update blob_segment set segment = " + next);
  }

  /** 1 if segment {@code segment}'s partition exists, 0 once compaction dropped it. */
  private long partitionCount(int segment) {
    return count(
        "select count(*) from pg_inherits i join pg_class c on c.oid = i.inhrelid"
            + " join pg_class p on p.oid = i.inhparent where p.relname = 'blob_chunk'"
            + " and c.relname = 'blob_chunk_" + segment + "'");
  }
}
