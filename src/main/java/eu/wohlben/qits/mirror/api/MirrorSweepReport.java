package eu.wohlben.qits.mirror.api;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.mirror.gc.CacheEviction;
import eu.wohlben.qits.mirror.gc.CacheEvictionSweep;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * What {@code POST /mirror/api/gc/sweep} answers: the counts the run's log line has, as fields.
 *
 * <p>The receipt qits-orchestrator keeps for a run, so it says what the log line says and nothing
 * the log line does not — no identity lists, which on a full mirror are thousands of lines nobody
 * reads off a scheduler's record. The disk figures stay in the log too: compaction is the library's
 * measurement, and a receipt that carried it would be a second place to keep in step with it.
 *
 * @param startedAt the run's one clock
 * @param dryRun whether anything was deleted — the caller's {@code dryRun} or the deployment's
 *     {@code qits.mirror.eviction.dry-run}, whichever said so
 * @param pins what the mirrored tags were judged by
 * @param types one line per cache type, sorted by type
 * @param blobs what the reconciliation freed, or would free on a dry run
 * @param summary the run's log line, verbatim
 */
public record MirrorSweepReport(
    Instant startedAt,
    boolean dryRun,
    Pins pins,
    List<TypeLine> types,
    Blobs blobs,
    String summary) {

  /**
   * The image pins the run was handed.
   *
   * @param supplied whether the body carried {@code pins.images}. False means mirrored tags were
   *     judged by the window, as they are when the caller could not read the pins
   * @param images how many the body carried; the configured pins are not counted
   */
  public record Pins(boolean supplied, int images) {}

  /**
   * One cache type's line.
   *
   * @param type the wire name: {@code npm-proxy}, {@code maven-proxy}, {@code oci-mirror}
   * @param window the window it was judged against, ISO-8601, or null when it failed before planning
   * @param condemned identities the plan condemned
   * @param superseded of those, how many nothing can reach any more (oci-mirror only)
   * @param unpinned of those, how many no image pin names (oci-mirror only, and only with pins)
   * @param deleted rows actually gone — zero on a dry run
   * @param kept identities the plan kept
   * @param pinned of those, how many an image pin names (oci-mirror only, and only with pins)
   * @param withheld condemned identities left whole because a blob they release is still inside the
   *     grace window
   * @param errors what could not be applied, each with its reason
   * @param failure why the type was skipped whole, or null
   * @param note the type's standing caption, or null
   */
  public record TypeLine(
      String type,
      String window,
      int condemned,
      int superseded,
      int unpinned,
      int deleted,
      int kept,
      int pinned,
      int withheld,
      List<String> errors,
      String failure,
      String note) {}

  /**
   * The blob reconciliation's figures.
   *
   * @param reclaimable blobs no surviving row names once the plans are applied
   * @param reclaimableBytes what they occupy
   * @param unlinked blobs actually removed — zero on a dry run
   * @param reclaimedBytes what those occupied
   * @param withheld candidates younger than the grace window: the plan's count on a dry run, the
   *     unlink loop's on a real one
   * @param withheldBytes what those occupy
   * @param stillReferenced candidates something named again between planning and unlinking
   * @param alreadyGone candidates that were not there any more
   */
  public record Blobs(
      int reclaimable,
      long reclaimableBytes,
      int unlinked,
      long reclaimedBytes,
      int withheld,
      long withheldBytes,
      int stillReferenced,
      int alreadyGone) {}

  /** The receipt for one run's report. */
  static MirrorSweepReport of(CacheEvictionSweep.SweepReport report) {
    List<TypeLine> types =
        report.types().stream()
            .map(MirrorSweepReport::line)
            .sorted(Comparator.comparing(TypeLine::type))
            .toList();
    var plan = report.blobPlan();
    var outcome = report.blobs();
    Blobs blobs =
        new Blobs(
            plan.blobCount(),
            plan.reclaimableBytes(),
            outcome.unlinked(),
            outcome.reclaimedBytes(),
            report.dryRun() ? plan.withheldByGraceWindow() : outcome.withheldByGraceWindow(),
            report.dryRun() ? plan.withheldBytes() : outcome.withheldBytes(),
            outcome.stillReferenced(),
            outcome.alreadyGone());
    return new MirrorSweepReport(
        report.startedAt(),
        report.dryRun(),
        new Pins(report.pinsSupplied(), report.imagePins()),
        types,
        blobs,
        CacheEvictionSweep.summary(report));
  }

  private static TypeLine line(CacheEvictionSweep.TypeResult type) {
    return new TypeLine(
        RepositoryTypeProfile.wireNameOf(type.typeKey()),
        type.window() == null ? null : CacheEviction.iso(type.window()),
        type.condemned(),
        type.superseded(),
        type.unpinned(),
        type.deleted(),
        type.kept(),
        type.pinned(),
        type.withheldByGraceWindow(),
        type.errors(),
        type.failure(),
        type.note());
  }
}
