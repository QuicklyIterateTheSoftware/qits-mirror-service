package eu.wohlben.qits.mirror.gc;

import java.util.Set;

/**
 * One cached mirror tag as the store keys it: the namespace, the image inside it, and the tag.
 *
 * <p>{@link #resolve} is how an image pin becomes one of these, and it follows the pull path rather
 * than inventing a grammar: {@code OciRegistryService.resolveForPull} serves a name whose first
 * segment is a mirror namespace from that namespace, and remaps any other name whole into Docker
 * Hub's — and Hub spells a single-segment image with its implicit {@code library/}, which
 * qits-registries' {@code OciMirrorUpstreams.normalize} applies and this restates, because that one
 * is package-private. A pin that resolved differently from the pull it protects would keep a tag no
 * build reads and condemn the one it does.
 *
 * @param repository the mirror namespace ({@code artifact_repository.name}), e.g. {@code hub}
 * @param image the image inside it, e.g. {@code library/postgres}
 * @param tag the tag
 */
public record MirrorTagRef(String repository, String image, String tag) {

  /** Hub's implicit namespace for single-segment images: {@code node} is {@code library/node}. */
  static final String HUB_LIBRARY = "library";

  /** The identity {@code OciMirrorEvictor} spells a cached tag with: {@code image:tag}. */
  public String identity() {
    return image + ":" + tag;
  }

  /**
   * Where a pin's image is cached.
   *
   * <ul>
   *   <li>{@code hub/library/postgres} → ({@code hub}, {@code library/postgres}): the first segment
   *       names a mirror namespace, and the rest is the image inside it.
   *   <li>{@code quay/quarkus/x} → ({@code quay}, {@code quarkus/x}).
   *   <li>{@code moby/buildkit} → ({@code hub}, {@code moby/buildkit}): {@code moby} names no
   *       namespace, so the whole name is a Hub image, as the pull remap serves it.
   *   <li>{@code node} → ({@code hub}, {@code library/node}): a bare Hub image, expanded.
   * </ul>
   *
   * @param pin the pin as qits-maintenance or configuration names it
   * @param mirrorRepositories the live mirror namespaces, read from the repository table
   * @param hubRepository the namespace fronting Docker Hub, or null when none is registered — then a
   *     name outside every namespace resolves to nothing this mirror can hold
   * @return the cached tag it names, or null when it can name none here
   */
  public static MirrorTagRef resolve(
      ImagePins.ImagePin pin, Set<String> mirrorRepositories, String hubRepository) {
    String name = pin.name();
    int slash = name.indexOf('/');
    if (slash > 0 && mirrorRepositories.contains(name.substring(0, slash))) {
      String repository = name.substring(0, slash);
      return new MirrorTagRef(
          repository, normalize(repository, hubRepository, name.substring(slash + 1)), pin.version());
    }
    if (hubRepository == null) {
      return null;
    }
    return new MirrorTagRef(hubRepository, normalize(hubRepository, hubRepository, name), pin.version());
  }

  /** Hub's one rule: a single-segment image lives under {@code library/}. Every other upstream: as is. */
  private static String normalize(String repository, String hubRepository, String image) {
    if (!repository.equals(hubRepository) || image.indexOf('/') >= 0) {
      return image;
    }
    return HUB_LIBRARY + "/" + image;
  }
}
