package eu.wohlben.qits.mirror.gc;

import java.util.List;
import java.util.Objects;

/**
 * The image pins one sweep was handed — or the fact that it was handed none (qits-2253).
 *
 * <p><b>Absent and empty are different answers, and only one of them is allowed.</b> Absent means
 * the caller could not read the pins — qits-orchestrator reads them from qits-maintenance's {@code
 * GET /maintenance/api/pins/images} before every sweep, and a failed read sends no {@code images}
 * member — so the run falls back to the window for mirrored tags and condemns nothing new. An empty
 * list would mean "nothing is pinned", which on this estate is never true and would condemn every
 * cached tag at once; the door refuses it with a 400 before a run starts, so it never reaches here.
 *
 * @param images the pins as the caller named them, or null when it supplied none
 */
public record ImagePins(List<ImagePin> images) {

  /** No pins arrived: mirrored tags keep the window rule. */
  public static final ImagePins ABSENT = new ImagePins(null);

  public ImagePins {
    if (images != null) {
      if (images.isEmpty()) {
        throw new IllegalArgumentException(
            "an empty pin list would condemn every cached tag; absent pins are null, not empty");
      }
      images = List.copyOf(images);
    }
  }

  /** The pins a caller supplied — at least one. */
  public static ImagePins of(List<ImagePin> images) {
    return new ImagePins(Objects.requireNonNull(images, "images"));
  }

  /** Whether the caller supplied pins, so mirrored tags are judged by them instead of the window. */
  public boolean present() {
    return images != null;
  }

  /** How many pins the caller supplied; zero when absent. */
  public int count() {
    return images == null ? 0 : images.size();
  }

  /**
   * One image pin: a Dockerfile reference with the registry host stripped, and its tag.
   *
   * <p>Spelled the way qits-maintenance answers it — {@code {"name":"hub/library/postgres",
   * "version":"18.4"}}, {@code {"name":"node","version":"24-alpine"}} — which is the way a {@code
   * FROM} line spells it. Which cached tag that names is {@link MirrorTagRef#resolve}'s answer, not
   * this record's.
   *
   * @param name the image name, host stripped: {@code node}, {@code moby/buildkit}, {@code
   *     quay/quarkus/ubi9-quarkus-mandrel-builder-image}
   * @param version the tag
   */
  public record ImagePin(String name, String version) {

    public ImagePin {
      name = requireName(name);
      version = requireTag(version);
    }

    /**
     * One {@code name:tag} entry from configuration. The tag is what follows the last {@code :}
     * after the last {@code /}, so a name can never be cut inside a path segment.
     *
     * @throws IllegalArgumentException the entry names no tag, or an empty name
     */
    public static ImagePin parse(String entry) {
      String trimmed = entry == null ? "" : entry.trim();
      int colon = trimmed.lastIndexOf(':');
      if (colon <= trimmed.lastIndexOf('/') || colon == trimmed.length() - 1) {
        throw new IllegalArgumentException(
            "an image pin is name:tag, as in moby/buildkit:v0.33.0 — got '" + entry + "'");
      }
      return new ImagePin(trimmed.substring(0, colon), trimmed.substring(colon + 1));
    }

    private static String requireName(String name) {
      if (name == null
          || name.isBlank()
          || !name.equals(name.trim())
          || name.indexOf(':') >= 0
          || name.indexOf('@') >= 0
          || name.startsWith("/")
          || name.endsWith("/")
          || name.contains("//")) {
        throw new IllegalArgumentException(
            "an image pin's name is a reference with no host, tag or digest, as in"
                + " hub/library/postgres — got '"
                + name
                + "'");
      }
      return name;
    }

    private static String requireTag(String tag) {
      if (tag == null
          || tag.isBlank()
          || !tag.equals(tag.trim())
          || tag.indexOf(':') >= 0
          || tag.indexOf('/') >= 0
          || tag.indexOf('@') >= 0) {
        throw new IllegalArgumentException(
            "an image pin's version is a tag, as in 18.4 — got '" + tag + "'");
      }
      return tag;
    }
  }
}
