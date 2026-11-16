package eu.wohlben.qits.mirror.gc;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * One cached identity, reduced to the facts the eviction engine reads.
 *
 * <p>This is the whole translation between a cache type and the rule. What an identity <em>is</em> —
 * a mirrored tag, a cached npm version, a cached maven path — stays inside its {@link CacheEvictor};
 * what the engine sees is this record, and nothing here names a protocol.
 *
 * <p><b>A verdict sentence starts with its label</b> — one lower-case word and a colon, {@code
 * "superseded: …"}, {@code "unpinned: …"}, {@code "pinned: …"} — so that a run can count its lines by
 * cause without the engine knowing any type's rules ({@link #labelOf}).
 *
 * @param repository the {@code artifact_repository} row it lives in
 * @param identity the type's own coordinate, spelled the way that type's tools spell it, so a report
 *     can be looked up without translating
 * @param lastAccessAt the <b>effective access time</b>: {@code max(created/updated/fetched,
 *     accessed_at)}. Creation counts as a first access, so something cached an hour ago is young
 *     rather than never-read, and null therefore never reaches the engine
 * @param blobs every blob this identity names. The engine puts them in the released or the retained
 *     set; which of them may actually be unlinked stays {@link MirrorBlobSweep}'s answer alone
 * @param condemnation null for an identity the window judges, which is nearly all of them; otherwise
 *     the sentence saying why its type condemns it outright — nothing can reach it any more, or (a
 *     mirrored tag, qits-2253) no image pin names it — and the engine condemns it on this run
 *     whatever its access time. Only the type can know that — reachability and what a pin names
 *     are protocol facts — so the engine takes the verdict as given and echoes the sentence
 * @param keptBecause null for an identity the window judges; otherwise the sentence saying why its
 *     type keeps it whatever its access time — a mirrored tag an image pin names (qits-2253). The
 *     mirror image of {@code condemnation}, and never set together with it
 */
public record CachedIdentity(
    String repository,
    String identity,
    Instant lastAccessAt,
    Set<String> blobs,
    String condemnation,
    String keptBecause) {

  /** One lower-case word, a colon and a space: the label every verdict sentence leads with. */
  private static final java.util.regex.Pattern LABELLED =
      java.util.regex.Pattern.compile("^([a-z][a-z-]*): .+", java.util.regex.Pattern.DOTALL);

  public CachedIdentity {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(
        lastAccessAt,
        "lastAccessAt: an evictor must fold creation into the access time, so that a row with no"
            + " read yet reads as young rather than as unknown");
    blobs = Set.copyOf(blobs);
    if (condemnation != null && keptBecause != null) {
      throw new IllegalArgumentException(
          identity + " is both condemned and kept regardless of the window; a type says one");
    }
    requireLabelled(condemnation);
    requireLabelled(keptBecause);
  }

  /** An identity the window judges — the ordinary case. */
  public CachedIdentity(
      String repository, String identity, Instant lastAccessAt, Set<String> blobs) {
    this(repository, identity, lastAccessAt, blobs, null, null);
  }

  /**
   * An identity its type condemns regardless of the window, with the sentence a report echoes.
   *
   * @param reason why it dies on this run whatever its age; required, because a verdict nobody can
   *     read is not reviewable
   */
  public static CachedIdentity condemned(
      String repository, String identity, Instant lastAccessAt, Set<String> blobs, String reason) {
    return new CachedIdentity(
        repository, identity, lastAccessAt, blobs, Objects.requireNonNull(reason, "reason"), null);
  }

  /**
   * An identity its type keeps regardless of the window, with the sentence a report echoes — the
   * other half of the seam {@link #condemned} opened (qits-2253: a mirrored tag an image pin names).
   *
   * @param reason why it stays however long nobody has pulled it; required, for the same reason a
   *     condemnation's is
   */
  public static CachedIdentity kept(
      String repository, String identity, Instant lastAccessAt, Set<String> blobs, String reason) {
    return new CachedIdentity(
        repository, identity, lastAccessAt, blobs, null, Objects.requireNonNull(reason, "reason"));
  }

  /** The label a verdict sentence leads with ({@code "superseded"} for "superseded: …"), or null. */
  public static String labelOf(String sentence) {
    if (sentence == null) {
      return null;
    }
    java.util.regex.Matcher labelled = LABELLED.matcher(sentence);
    return labelled.matches() ? labelled.group(1) : null;
  }

  private static void requireLabelled(String sentence) {
    if (sentence != null && labelOf(sentence) == null) {
      throw new IllegalArgumentException(
          "a verdict sentence leads with its label, as in \"superseded: …\": " + sentence);
    }
  }

  /** Whether this identity was last touched before the cut — the access rule, said once. */
  boolean unaccessedSince(Instant cut) {
    return lastAccessAt.isBefore(cut);
  }

  /** Whether its type condemned it outright, so the window is not asked. */
  boolean condemnedRegardlessOfWindow() {
    return condemnation != null;
  }

  /** Whether its type kept it outright, so the window is not asked. */
  boolean keptRegardlessOfWindow() {
    return keptBecause != null;
  }
}
