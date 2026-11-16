package eu.wohlben.qits.mirror.gc;

import eu.wohlben.qits.blobstore.control.BlobCompaction;
import eu.wohlben.qits.blobstore.control.BlobReclaim;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * The run: plan every cache type, delete what each condemned, reclaim the blobs nothing reaches any
 * more. One call, one log line, one report.
 *
 * <p><b>qits-orchestrator is the only clock</b> (qits-2253). There is no schedule here any more: the
 * orchestrator calls {@code POST /mirror/api/gc/sweep} on its own cadence, with the image pins it
 * read from qits-maintenance in the body, and {@link #run(ImagePins, boolean)} — the single entry
 * point — is what that door calls. The pins are why the trigger moved: a sweep that judges mirrored
 * tags by what is pinned needs the pins, and the mirror must not fail closed on another service's
 * availability by fetching them itself. A run handed {@link ImagePins#ABSENT} — the orchestrator's
 * pin read failed — is the window-only run this used to be.
 *
 * <p><b>One run at a time.</b> A second call while one is running is refused at once with {@link
 * SweepInProgress} (the door answers 409) rather than queued: two runs planning against a store the
 * other is deleting from would judge identities against rows that are going, and a caller that
 * waited would hold a request open for as long as a sweep of the whole cache takes. The orchestrator
 * retries on its next tick.
 *
 * <p><b>The order inside one run is the safety property.</b> Every type is planned against <b>one</b>
 * census and one clock, then every type deletes its own rows, then the blob sweep reconciles across
 * all of them. Planning a type after another type's deletions would judge two types against two
 * different stores, and reclaiming per type would unlink a layer a second type still names.
 *
 * <p><b>A type that fails is skipped whole and the rest of the run proceeds.</b> Its blobs are then
 * counted live by the reconciliation, so a broken type reclaims nothing rather than reclaiming
 * something nobody vouched for.
 *
 * <p><b>The plans handed to the blob sweep are the ones that were planned, not a recomputed
 * "applied" set.</b> An identity the grace window withheld keeps its rows, so the fresh pre-unlink
 * census still sees the blobs it names and the sweep counts them as still referenced. That is the
 * belt the withheld case rests on, and it is the same one that catches a pull arriving mid-run.
 *
 * <p><b>Then the disk comes back, which the unlink loop alone never did (qits-2248).</b> {@link
 * MirrorBlobSweep#execute} deletes chunk rows, and PostgreSQL keeps the space they held inside the
 * table's files — it reuses it for a later write but never returns it to the filesystem, so a run
 * that reclaimed half the cache left the volume exactly as full. After the blob loop, a real run
 * therefore calls the library's {@link BlobCompaction}, which drains mostly-dead chunk segments and
 * drops their partitions — the one operation that unlinks files — and the figures it measured ride
 * beside the data figures in the one log line. A dry run never reaches it: compaction moves and
 * drops partitions, which is a write, and a plan must stay reviewable without side effects.
 */
@ApplicationScoped
public class CacheEvictionSweep {

  private static final Logger LOG = Logger.getLogger(CacheEvictionSweep.class);

  /** The verdict labels a run counts by cause ({@link JudgedIdentity#label()}). */
  static final String SUPERSEDED = "superseded";

  static final String UNPINNED = "unpinned";
  static final String PINNED = "pinned";

  @Inject Instance<CacheEvictor> evictors;
  @Inject EvictionConfig config;
  @Inject MirrorBlobCensus census;
  @Inject MirrorBlobSweep sweep;
  @Inject BlobReclaim blobs;
  @Inject BlobCompaction compaction;

  private final CacheEviction engine = new CacheEviction();

  /**
   * What one run did, per type and in total.
   *
   * @param startedAt the run's one clock — every type was judged against this instant
   * @param dryRun whether anything was deleted at all
   * @param pinsSupplied whether the run was handed image pins, so mirrored tags were judged by them
   *     rather than by the window
   * @param imagePins how many image pins the caller supplied; zero when it supplied none
   * @param types one line per registered evictor, sorted by type key — CDI's own order varies
   *     between starts, and a log line or receipt whose order moved would read as a change
   * @param blobPlan what the reconciliation freed, or would free on a dry run
   * @param blobs the unlink loop's own counts; all zero on a dry run
   * @param disk what the post-sweep compaction measured (qits-2248), or null on a dry run or when
   *     compaction itself failed — a failure is a logged warning, never a failed sweep
   */
  public record SweepReport(
      Instant startedAt,
      boolean dryRun,
      boolean pinsSupplied,
      int imagePins,
      List<TypeResult> types,
      MirrorBlobSweep.SweepPlan blobPlan,
      MirrorBlobSweep.SweepOutcome blobs,
      BlobCompaction.CompactionResult disk) {

    public SweepReport {
      types =
          types.stream()
              .sorted(java.util.Comparator.comparing(TypeResult::typeKey))
              .toList();
    }

    /** One type's line, by key. */
    public TypeResult type(String typeKey) {
      return types.stream()
          .filter(result -> result.typeKey().equals(typeKey))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("no such type in this run: " + typeKey));
    }
  }

  /**
   * One type's line.
   *
   * @param typeKey the stored type key
   * @param window the window it was judged against, or null when it failed before planning
   * @param condemned identities the plan condemned
   * @param superseded how many of those its type condemned regardless of the window, because
   *     nothing can reach them any more; a subset of {@code condemned}, never added to it
   * @param unpinned how many of those its type condemned because no image pin names them
   *     (qits-2253); a subset of {@code condemned} too, and disjoint from {@code superseded}
   * @param kept identities it kept
   * @param pinned how many of those it kept because an image pin names them; a subset of {@code
   *     kept}
   * @param deleted rows actually gone — zero on a dry run
   * @param withheldByGraceWindow identities left whole because a blob they release is still young
   * @param errors what could not be applied, each with its reason
   * @param failure why the type was skipped whole, or null
   * @param note the type's standing caption, or null
   */
  public record TypeResult(
      String typeKey,
      Duration window,
      int condemned,
      int superseded,
      int unpinned,
      int kept,
      int pinned,
      int deleted,
      int withheldByGraceWindow,
      List<String> errors,
      String failure,
      String note) {

    public TypeResult {
      errors = List.copyOf(errors);
    }
  }

  /** Refused because another run is still in flight; the door answers 409. */
  public static final class SweepInProgress extends RuntimeException {
    SweepInProgress() {
      super("a mirror sweep is already running; retry once it has finished");
    }
  }

  /** Whether a run is in flight — the one-at-a-time guard, held across the whole of {@link #run}. */
  private final AtomicBoolean running = new AtomicBoolean();

  /**
   * The guard itself, for a case that has to hold it while it knocks. A method rather than the
   * field, because a test holds the client proxy and a field read on that would be the proxy's own.
   */
  AtomicBoolean inFlight() {
    return running;
  }

  /**
   * Plans every type against one census and one clock, applies what is not a dry run, and reclaims.
   * Logs the one line and answers the report it summarises.
   *
   * <p>{@code @ActivateRequestContext} because the persistence session is bound to it, and a caller
   * off a request thread — a test, a future job — has no request of its own.
   *
   * @param pins the image pins mirrored tags are judged by, or {@link ImagePins#ABSENT} to judge them
   *     by the window
   * @param dryRunRequested plan and report only; {@code qits.mirror.eviction.dry-run} forces this on
   *     whatever the caller says
   * @throws SweepInProgress another run has not finished
   */
  @ActivateRequestContext
  public SweepReport run(ImagePins pins, boolean dryRunRequested) {
    if (!running.compareAndSet(false, true)) {
      throw new SweepInProgress();
    }
    try {
      SweepReport report = sweepOnce(pins, dryRunRequested || config.dryRun());
      LOG.info(summary(report));
      return report;
    } finally {
      running.set(false);
    }
  }

  private SweepReport sweepOnce(ImagePins pins, boolean dryRun) {
    Instant startedAt = Instant.now();
    MirrorBlobCensus.Census taken = census.take();

    Map<String, EvictionPlan> plans = new HashMap<>();
    List<TypeResult> results = new ArrayList<>();
    for (CacheEvictor evictor : evictors) {
      try {
        Duration window = config.requireWindow(evictor.typeKey());
        EvictionPlan plan = engine.plan(evictor, window, startedAt, pins);
        plans.put(evictor.typeKey(), plan);
        results.add(
            new TypeResult(
                evictor.typeKey(),
                window,
                plan.dead().size(),
                EvictionPlan.labelled(plan.dead(), SUPERSEDED),
                EvictionPlan.labelled(plan.dead(), UNPINNED),
                plan.kept().size(),
                EvictionPlan.labelled(plan.kept(), PINNED),
                0,
                0,
                List.of(),
                null,
                evictor.note()));
      } catch (RuntimeException failed) {
        results.add(
            new TypeResult(
                evictor.typeKey(),
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                message(failed),
                evictor.note()));
      }
    }

    if (dryRun) {
      return new SweepReport(
          startedAt,
          true,
          pins.present(),
          pins.count(),
          results,
          sweep.plan(taken, plans),
          MirrorBlobSweep.SweepOutcome.nothing(),
          null);
    }

    CacheEvictor.GraceWindow grace = graceWindow();
    for (CacheEvictor evictor : evictors) {
      EvictionPlan plan = plans.get(evictor.typeKey());
      if (plan == null) {
        continue; // the type failed to plan; its blobs stay live for this run
      }
      try {
        apply(results, evictor.typeKey(), evictor.delete(plan, grace));
      } catch (RuntimeException failed) {
        // A type whose deletion blew up keeps its plan out of the reconciliation: its rows are in an
        // unknown state, and unlinking blobs against an unknown state is the one thing never worth
        // guessing at.
        plans.remove(evictor.typeKey());
        fail(results, evictor.typeKey(), message(failed));
      }
    }

    MirrorBlobSweep.SweepOutcome outcome = sweep.execute(taken, plans);
    return new SweepReport(
        startedAt,
        false,
        pins.present(),
        pins.count(),
        results,
        sweep.plan(taken, plans),
        outcome,
        compact());
  }

  /**
   * Gives back the disk the run's deletes left behind, and says what that was worth — or null.
   *
   * <p><b>Outside any transaction, by construction.</b> {@link BlobCompaction#compact()} commits
   * each step on its own and refuses a caller's transaction with an {@link IllegalStateException};
   * {@link #run} holds none at the point this is called — every delete above committed in a
   * transaction of its own, opened and closed inside the evictor or the store — and this method is
   * not {@code @Transactional} and carries no surrounding one. Should a caller ever wrap a sweep in
   * one, the refusal lands in the catch below as a logged warning and a report with no disk figures,
   * never as a failed sweep.
   *
   * <p><b>A failure never fails the sweep.</b> By the time this runs the identity rows and blobs are
   * already gone, and the report is the only record of which; turning a compaction error into a
   * thrown exception would throw that record away to report something the next run will retry
   * anyway. It is logged as a warning, with the cause, and the report carries no disk figures rather
   * than invented ones.
   */
  private BlobCompaction.CompactionResult compact() {
    try {
      return compaction.compact();
    } catch (RuntimeException failed) {
      LOG.warnf(
          failed,
          "Cache eviction: blob compaction failed, so this run reports no disk; the deletes stand"
              + " and the next run compacts again — %s",
          message(failed));
      return null;
    }
  }

  /** The disk half of a run's blob log line, beside the data figures it is not. */
  private static String diskLine(BlobCompaction.CompactionResult disk) {
    if (disk == null) {
      return "disk not compacted (see the warning above)";
    }
    return String.format(
        "disk %d -> %d bytes (blob tables), %d -> %d bytes (database), %d segments dropped, %d"
            + " drained, %d held (%s)",
        disk.blobTablesBytesBefore(),
        disk.blobTablesBytesAfter(),
        disk.databaseBytesBefore(),
        disk.databaseBytesAfter(),
        disk.segmentsDropped(),
        disk.segmentsDrained(),
        disk.segmentsHeld(),
        disk.state());
  }

  /** The one log line a run leaves behind. */
  public static String summary(SweepReport report) {
    StringBuilder line = new StringBuilder(report.dryRun() ? "Cache eviction (dry run):" : "Cache eviction:");
    for (TypeResult type : report.types()) {
      line.append(' ').append(type.typeKey()).append('=');
      if (type.failure() != null) {
        line.append("failed(").append(type.failure()).append(')');
        continue;
      }
      line.append(type.condemned()).append(" condemned");
      List<String> causes = new ArrayList<>();
      if (type.superseded() > 0) {
        causes.add(type.superseded() + " " + SUPERSEDED);
      }
      if (type.unpinned() > 0) {
        causes.add(type.unpinned() + " " + UNPINNED);
      }
      if (!causes.isEmpty()) {
        line.append(" (").append(String.join(", ", causes)).append(')');
      }
      line.append('/')
          .append(type.deleted())
          .append(" deleted/")
          .append(type.kept())
          .append(" kept");
      if (type.pinned() > 0) {
        line.append(" (").append(type.pinned()).append(' ').append(PINNED).append(')');
      }
      if (type.withheldByGraceWindow() > 0) {
        line.append('/').append(type.withheldByGraceWindow()).append(" withheld");
      }
      if (!type.errors().isEmpty()) {
        line.append('/').append(type.errors().size()).append(" errors");
      }
    }
    if (report.dryRun()) {
      line.append(" blobs=")
          .append(report.blobPlan().blobCount())
          .append(" reclaimable (")
          .append(report.blobPlan().reclaimableBytes())
          .append(" bytes)");
      return line.toString();
    }
    line.append(" blobs=")
        .append(report.blobs().unlinked())
        .append(" unlinked (")
        .append(report.blobs().reclaimedBytes())
        .append(" bytes)");
    if (report.blobs().withheldByGraceWindow() > 0) {
      line.append(", ").append(report.blobs().withheldByGraceWindow()).append(" withheld");
    }
    if (report.blobs().stillReferenced() > 0) {
      line.append(", ").append(report.blobs().stillReferenced()).append(" still referenced");
    }
    if (report.blobs().alreadyGone() > 0) {
      line.append(", ").append(report.blobs().alreadyGone()).append(" already gone");
    }
    line.append(", ").append(diskLine(report.disk()));
    return line.toString();
  }

  /**
   * The grace window as an evictor asks it: is this blob younger than the store allows.
   *
   * <p>Read once per run against one instant, so every identity of every type is judged on the same
   * clock the unlink will use.
   */
  private CacheEvictor.GraceWindow graceWindow() {
    Instant graceStartsAt = Instant.now().minus(sweep.graceWindow());
    return blobId -> {
      Instant written = blobs.lastWrittenAt(blobId);
      return written != null && written.isAfter(graceStartsAt);
    };
  }

  private static void apply(
      List<TypeResult> results, String typeKey, CacheEvictor.Applied applied) {
    edit(
        results,
        typeKey,
        result ->
            new TypeResult(
                result.typeKey(),
                result.window(),
                result.condemned(),
                result.superseded(),
                result.unpinned(),
                result.kept(),
                result.pinned(),
                applied.deleted().size(),
                applied.withheldByGraceWindow().size(),
                applied.errors(),
                null,
                result.note()));
  }

  private static void fail(List<TypeResult> results, String typeKey, String failure) {
    edit(
        results,
        typeKey,
        result ->
            new TypeResult(
                result.typeKey(),
                result.window(),
                result.condemned(),
                result.superseded(),
                result.unpinned(),
                result.kept(),
                result.pinned(),
                result.deleted(),
                result.withheldByGraceWindow(),
                result.errors(),
                failure,
                result.note()));
  }

  private static void edit(
      List<TypeResult> results, String typeKey, java.util.function.UnaryOperator<TypeResult> edit) {
    for (int i = 0; i < results.size(); i++) {
      if (results.get(i).typeKey().equals(typeKey)) {
        results.set(i, edit.apply(results.get(i)));
        return;
      }
    }
  }

  private static String message(RuntimeException failed) {
    String message = failed.getMessage();
    return failed.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
