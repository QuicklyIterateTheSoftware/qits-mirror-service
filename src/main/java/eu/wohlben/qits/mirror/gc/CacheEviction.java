package eu.wohlben.qits.mirror.gc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The engine: delete everything unaccessed past the window, and everything its type condemns
 * outright; keep everything else, and everything its type keeps outright.
 *
 * <p>It is short because a cache has nothing to protect structurally. Every byte this service holds
 * came from upstream and can be fetched again, so the only question worth asking is whether anything
 * still uses it. Being wrong costs one re-download; being wrong in the other direction costs disk
 * forever.
 *
 * <p><b>Unaccessed means the effective access time is older than the window</b>, and the effective
 * access time is {@code max(created/updated/fetched, accessed_at)} — creation counts as a first
 * access, which is what keeps a tag cached ten minutes ago from reading as never-read. The evictor
 * computes it ({@link CachedIdentity#lastAccessAt()}); this engine only compares it.
 *
 * <p><b>Unreachable is a verdict that outranks the window, and the engine does not reach it
 * itself.</b> An identity no coordinate of its type can name any more — a mirrored manifest an
 * upstream rebind left behind — is not "unaccessed lately" but unaccessible for good, and waiting
 * out a window for it only parks its bytes on disk for a month. Whether something is reachable is a
 * protocol fact, so the evictor decides it and says so ({@link CachedIdentity#condemnation()}); the
 * engine condemns that identity on this run, echoes the evictor's sentence, and asks no clock. The
 * safety below the plan is unchanged by it: the grace window still withholds an identity over young
 * bytes, and the blob sweep still keeps every blob a survivor names.
 *
 * <p><b>No release rule lives here, deliberately.</b> A cache's content has no releases of
 * <em>ours</em> to protect: a mirrored {@code jdk-25} is upstream's release, and keeping it forever
 * because upstream calls it a release is how a mirror never shrinks. Version protection is
 * qits-artifacts' business, earned by own-ness, and this service holds nothing of its own.
 *
 * <p><b>Pinned is the verdict on the other side, and the engine does not reach it either</b>
 * (qits-2253). The owner's rule for the mirror is that it keeps only what is pinned: a mirrored tag
 * an image pin names stays however long nobody pulled it, and one no pin names dies on this run
 * however recently somebody did. What a pin names is the type's fact — a pin is a Dockerfile
 * reference, and only the mirror evictor knows which cached tag that is — so the evictor says so
 * ({@link CachedIdentity#keptBecause()} beside {@link CachedIdentity#condemnation()}) and the engine
 * keeps or condemns on its word. The grace window and the blob sweep sit below this verdict
 * exactly as they sit below the reachability one.
 *
 * <p><b>The pins arrive in the request, so the mirror never calls another service.</b>
 * qits-orchestrator reads qits-maintenance's image pins and hands them to {@code POST
 * /mirror/api/gc/sweep}; they reach the evictor as {@link ImagePins} through {@link #plan(CacheEvictor,
 * Duration, Instant, ImagePins)}. A run whose caller could not read them is handed {@link
 * ImagePins#ABSENT}, and every tag is judged by the window as before — a sweep must never fail
 * closed on another service's availability, and it must never read "no pins" as "nothing pinned".
 * The npm and maven caches have no pins and ignore them.
 *
 * <p>Stateless and not a bean: something that binds a configured window to a {@link CacheEvictor}
 * constructs one. Deletion is the evictor's {@link CacheEvictor#delete}, because eviction removes
 * exactly the identities this plan condemns and adds nothing of its own.
 */
public final class CacheEviction {

  /** The rule sentence a report echoes for one cache type. */
  public static String rule(Duration window) {
    return "cache: delete every identity unaccessed for longer than "
        + iso(window)
        + ". Creation counts as the first access, so nothing is eligible before the window has"
        + " passed since it was cached — unless its type says nothing can reach it any more, or"
        + " that no image pin names it, which condemns it at once; or that an image pin names"
        + " it, which keeps it whatever its age.";
  }

  /** The sentence on a kept line. */
  public static String keptAccessed(Duration window) {
    return "accessed inside the " + iso(window) + " window";
  }

  /** The sentence on a condemned line. */
  public static String deadUnaccessed(Duration window) {
    return "cached content unaccessed for longer than " + iso(window);
  }

  /**
   * Reads the evictor's identities and says what would die. Deletes nothing.
   *
   * @param evictor the type's own facts — what exists, and when each was last touched
   * @param window the configured eviction window for that type
   * @param now the run's clock, passed in rather than read here so a plan and its receipt judge
   *     every identity against one instant
   */
  public EvictionPlan plan(CacheEvictor evictor, Duration window, Instant now) {
    return plan(evictor, window, now, ImagePins.ABSENT);
  }

  /**
   * {@link #plan(CacheEvictor, Duration, Instant)} over the image pins the run was handed.
   *
   * @param pins what the evictor reads its pin verdicts from; {@link ImagePins#ABSENT} leaves every
   *     identity to the window and the type's own reachability verdict
   */
  public EvictionPlan plan(CacheEvictor evictor, Duration window, Instant now, ImagePins pins) {
    Instant cut = now.minus(window);
    List<JudgedIdentity> dead = new ArrayList<>();
    List<JudgedIdentity> kept = new ArrayList<>();
    Set<String> released = new HashSet<>();
    Set<String> retained = new HashSet<>();

    for (CachedIdentity candidate : evictor.enumerate(pins)) {
      if (candidate.condemnedRegardlessOfWindow()) {
        dead.add(
            new JudgedIdentity(
                candidate.repository(), candidate.identity(), candidate.condemnation(), true));
        released.addAll(candidate.blobs());
      } else if (candidate.keptRegardlessOfWindow()) {
        kept.add(
            new JudgedIdentity(
                candidate.repository(), candidate.identity(), candidate.keptBecause(), true));
        retained.addAll(candidate.blobs());
      } else if (candidate.unaccessedSince(cut)) {
        dead.add(
            new JudgedIdentity(
                candidate.repository(), candidate.identity(), deadUnaccessed(window)));
        released.addAll(candidate.blobs());
      } else {
        kept.add(
            new JudgedIdentity(
                candidate.repository(), candidate.identity(), keptAccessed(window)));
        retained.addAll(candidate.blobs());
      }
    }

    dead.sort(BY_IDENTITY);
    kept.sort(BY_IDENTITY);
    return dead.isEmpty()
        ? EvictionPlan.nothingDies(kept, retained)
        : new EvictionPlan(dead, kept, released, retained);
  }

  /**
   * ISO-8601, in the spelling the configuration uses.
   *
   * <p>{@code Duration.toString()} normalises a whole number of days to hours ({@code P30D} comes
   * back as {@code PT720H}), and a report that answered in hours for a window somebody wrote in days
   * reads as a different number than the one they set.
   */
  public static String iso(Duration window) {
    return window.toDaysPart() > 0 && window.minusDays(window.toDaysPart()).isZero()
        ? "P" + window.toDaysPart() + "D"
        : window.toString();
  }

  static final Comparator<JudgedIdentity> BY_IDENTITY =
      Comparator.comparing(JudgedIdentity::repository).thenComparing(JudgedIdentity::identity);
}
