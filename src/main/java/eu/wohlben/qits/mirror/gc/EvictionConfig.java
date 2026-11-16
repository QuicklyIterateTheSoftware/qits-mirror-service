package eu.wohlben.qits.mirror.gc;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import io.smallrye.config.ConfigMapping;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the sweep does and how long a cached entry may sit unused before it is eligible:
 * {@code qits.mirror.eviction.*}.
 *
 * <p>The windows are keyed by the type's <b>wire name</b> — {@code npm-proxy}, {@code maven-proxy},
 * {@code oci-mirror} — because that is the spelling an operator reads in every report and every
 * repository listing, and a configuration key nobody can match to a report line is a knob nobody
 * turns.
 *
 * <p><b>A type with no window is an error, not a default.</b> {@link #requireWindow} throws rather
 * than assuming a number: a cache type nobody configured is a decision nobody took, and guessing a
 * window is guessing what may be deleted. Adding an evictor therefore means adding one line of
 * configuration, which {@code EvictionConfigTest} holds by looping over the registered evictors.
 */
@ConfigMapping(prefix = "qits.mirror.eviction")
public interface EvictionConfig {

  /**
   * Whether a sweep does anything at all.
   *
   * <p>The kill switch a deployment reaches for when something is wrong: false makes {@code POST
   * /mirror/api/gc/sweep} answer 503 naming this key, so the caller's run fails visibly rather than
   * reporting a sweep that never happened.
   */
  boolean enabled();

  /**
   * Plan and report, delete nothing — for every sweep, whatever its caller asked.
   *
   * <p>The mode a new deployment runs in until somebody has read a sweep's counts and agreed with
   * them. A caller can ask for a dry run on its own ({@code "dryRun":true} in the door's body), but
   * cannot ask this one away: true here outranks a body that says false.
   */
  boolean dryRun();

  /** {@code qits.mirror.eviction.oci-mirror.*}: what the mirror's tag rule reads besides the body. */
  OciMirror ociMirror();

  /** The OCI mirror's own keys. */
  interface OciMirror {

    /**
     * Image pins no maintenance scan sees, as {@code name:tag} in the door's name forms ({@code
     * hub/nicolargo/glances:4.5.6-full}, {@code moby/buildkit:v0.33.0}).
     *
     * <p>Unioned with the door's pins <b>only when the door's are present</b>: absent pins mean the
     * caller could not read them, the tag rule falls back to the window, and a list of two cannot
     * stand in for the estate's. A stale entry keeps one tag nobody pulls, which costs disk until it
     * is removed; a missing one costs a re-pull.
     */
    Optional<List<String>> configPins();
  }

  /** How long an identity may sit unaccessed before it is eligible, ISO-8601, per wire name. */
  Map<String, Duration> window();

  /**
   * One type's window, or a refusal naming the missing key.
   *
   * @param typeKey the stored key, e.g. {@code NPM_PROXY}
   * @throws IllegalStateException the type has no window configured
   */
  default Duration requireWindow(String typeKey) {
    String wireName = RepositoryTypeProfile.wireNameOf(typeKey);
    Duration window = window().get(wireName);
    if (window == null) {
      throw new IllegalStateException(
          "no eviction window configured for "
              + wireName
              + "; set qits.mirror.eviction.window."
              + wireName
              + " to an ISO-8601 duration such as P30D");
    }
    return window;
  }
}
