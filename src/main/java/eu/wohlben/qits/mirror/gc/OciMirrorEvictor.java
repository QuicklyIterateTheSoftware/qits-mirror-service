package eu.wohlben.qits.mirror.gc;

import eu.wohlben.qits.artifacts.control.OciManifestFootprints;
import eu.wohlben.qits.artifacts.control.OciMirrorProfile;
import eu.wohlben.qits.artifacts.control.OciRegistryCollection;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mirror's facts: what a cached identity is, when it was last pulled, and how a row goes.
 *
 * <p><b>Identity is the OCI model the store already has</b> — a cached tag, and a manifest no tag
 * names. Both, because both are what upstream drift leaves behind: a mutable tag like {@code jdk-25}
 * moves upstream, the next pull binds the tag to new bytes, and the manifest it used to name stays
 * as a row nobody can reach by any coordinate. Enumerating only tags would hide exactly the thing
 * that grows.
 *
 * <p><b>A manifest no current tag reaches is superseded, and it dies on the next run whatever its
 * age.</b> Reachable means named by a tag, or inside the closure of a manifest a tag names — an
 * index's children are reachable through it. Anything else is what a rebind left behind: nothing
 * can pull it by tag, and a pull by digest of an upstream-superseded manifest is a miss the mirror
 * can serve again from upstream. Judging it by the window instead kept every superseded {@code
 * jdk-25} for a month, a gigabyte each, and that is how a disk filled. A superseded index takes its
 * unreachable children with it on the same run, since none of them is reachable either. The grace
 * window still withholds one whose bytes are young, and the blob sweep still keeps every layer it
 * shares with the manifest that replaced it.
 *
 * <p><b>A manifest a tag names is not a candidate of its own.</b> Its tag is its identity; the row
 * dies when the tag does, on a later run once it is untagged. Listing both would let the window
 * condemn a manifest under a live tag — which {@code collectManifest} refuses anyway, so the only
 * outcome would be an error column nobody can act on.
 *
 * <p><b>A child of a cached index is a candidate under the window, and evicting one is not a
 * corruption.</b> A pull
 * arrives index-first and fetches children lazily, one per architecture actually asked for, so a
 * mirror index referencing a child with no local row is the normal state of a partially-pulled
 * image. An architecture nobody has pulled in a month therefore ages out and is re-fetched on the
 * next miss, which is the whole cache bargain. The child's <em>bytes</em> outlive its row for as
 * long as the index needs them: the surviving tag's footprint reaches them through the index's
 * closure, so the sweep never unlinks a blob a live index still names.
 *
 * <p><b>Effective access is {@code max(created/updated, accessed_at)}.</b> A tag's non-access
 * timestamp is {@code updated_at}, which is what an upstream rebind moves — the moment those bytes
 * became this tag's, and the honest floor for "how long has nobody wanted this".
 *
 * <p>Deletion runs through the registry's own funnel ({@code OciRegistryCollection}), which is where
 * the {@code oci_mirror_tag_check} freshness row is removed with its tag. That cleanup belongs to
 * the funnel rather than here for the usual reason: a second caller cannot forget what has no second
 * way in.
 */
@Singleton
public class OciMirrorEvictor implements CacheEvictor {

  /** The digest form the identities here are spelled with. */
  private static final String DIGEST_PREFIX = "@sha256:";

  /** The sentence on a manifest condemned because no current tag reaches it. */
  static final String SUPERSEDED =
      "superseded: no tag reaches this manifest, so it is condemned regardless of the window";

  @Inject CacheRoots roots;
  @Inject OciTagRepository tags;
  @Inject OciManifestRepository manifests;
  @Inject OciManifestFootprints footprints;
  @Inject OciRegistryCollection registry;

  @Override
  public String typeKey() {
    return OciMirrorProfile.KEY;
  }

  @Override
  public List<CachedIdentity> enumerate() {
    List<CachedIdentity> candidates = new ArrayList<>();
    for (String repository : roots.of(typeKey())) {
      for (String image : manifests.listImageNames(repository)) {
        collect(repository, image, candidates);
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * Tags first, then manifests: a dead tag over a dead manifest has to lose its row before {@code
   * collectManifest}'s a-tag-still-names-it belt looks. The two cannot both be condemned in one run
   * here — a tagged manifest is never enumerated — but the order costs nothing and removes the
   * question.
   */
  @Override
  public Applied delete(EvictionPlan plan, GraceWindow grace) {
    List<JudgedIdentity> deleted = new ArrayList<>();
    List<JudgedIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (JudgedIdentity dead : plan.dead()) {
      if (!isManifest(dead)) {
        deleteTag(dead, grace, deleted, withheld, errors);
      }
    }
    for (JudgedIdentity dead : plan.dead()) {
      if (isManifest(dead)) {
        deleteManifest(dead, grace, deleted, withheld, errors);
      }
    }
    return new Applied(deleted, withheld, errors);
  }

  /** One namespace's image: every cached tag, then every manifest no tag names. */
  private void collect(String repository, String image, List<CachedIdentity> candidates) {
    Map<String, OciManifest> byDigest = new LinkedHashMap<>();
    for (OciManifest manifest : manifests.listByImage(repository, image)) {
      byDigest.put(manifest.digest, manifest);
    }
    Set<String> tagged = new HashSet<>();
    Set<String> reachable = new HashSet<>();
    for (OciTag tag : tags.listByImage(repository, image)) {
      tagged.add(tag.manifestDigest);
      Set<String> closure = blobsOf(byDigest.get(tag.manifestDigest));
      reachable.add(tag.manifestDigest);
      reachable.addAll(closure);
      candidates.add(
          new CachedIdentity(
              repository, image + ":" + tag.tag, latest(tag.updatedAt, tag.accessedAt), closure));
    }
    for (OciManifest manifest : byDigest.values()) {
      if (tagged.contains(manifest.digest)) {
        continue;
      }
      String identity = image + DIGEST_PREFIX + manifest.digest;
      Instant access = latest(manifest.createdAt, manifest.accessedAt);
      Set<String> closure = footprints.of(manifest).keySet();
      candidates.add(
          reachable.contains(manifest.digest)
              ? new CachedIdentity(repository, identity, access, closure)
              : CachedIdentity.condemned(repository, identity, access, closure, SUPERSEDED));
    }
  }

  /** Everything a manifest reaches, or nothing when the row it named is already gone. */
  private Set<String> blobsOf(OciManifest manifest) {
    return manifest == null ? Set.of() : footprints.of(manifest).keySet();
  }

  /** Creation counts as the first access, so a tag cached minutes ago reads as young. */
  private static Instant latest(Instant created, Instant accessed) {
    return accessed == null || accessed.isBefore(created) ? created : accessed;
  }

  private static boolean isManifest(JudgedIdentity identity) {
    return identity.identity().contains(DIGEST_PREFIX);
  }

  private void deleteTag(
      JudgedIdentity dead,
      GraceWindow grace,
      List<JudgedIdentity> deleted,
      List<JudgedIdentity> withheld,
      List<String> errors) {
    // An image name cannot contain a colon, so the last one separates image from tag.
    int colon = dead.identity().lastIndexOf(':');
    String image = dead.identity().substring(0, colon);
    String tagName = dead.identity().substring(colon + 1);
    try {
      OciTag row = tags.findOne(dead.repository(), image, tagName).orElse(null);
      if (row == null) {
        errors.add(dead.identity() + ": no such tag row — the store moved since planning");
        return;
      }
      if (anyWithinGrace(dead.repository(), image, row.manifestDigest, grace)) {
        withheld.add(dead);
        return;
      }
      registry.collectTag(dead.repository(), image, tagName);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  private void deleteManifest(
      JudgedIdentity dead,
      GraceWindow grace,
      List<JudgedIdentity> deleted,
      List<JudgedIdentity> withheld,
      List<String> errors) {
    int at = dead.identity().lastIndexOf(DIGEST_PREFIX);
    String image = dead.identity().substring(0, at);
    String digest = dead.identity().substring(at + DIGEST_PREFIX.length());
    try {
      if (anyWithinGrace(dead.repository(), image, digest, grace)) {
        withheld.add(dead);
        return;
      }
      registry.collectManifest(dead.repository(), image, digest);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  /**
   * Whether any blob the manifest's closure releases is still inside the grace window — the gate on
   * identity deletion, because a row deleted over a young file would strand that file row-less and
   * therefore untouchable forever. A manifest row already gone gates on nothing.
   */
  private boolean anyWithinGrace(
      String repository, String image, String digest, GraceWindow grace) {
    OciManifest manifest = manifests.findOne(repository, image, digest).orElse(null);
    if (manifest == null) {
      return false;
    }
    return footprints.of(manifest).keySet().stream().anyMatch(grace::withinGrace);
  }
}
