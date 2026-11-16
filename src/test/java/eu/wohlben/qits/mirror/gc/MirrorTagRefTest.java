package eu.wohlben.qits.mirror.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which cached tag an image pin names — the pull path's resolution, restated (qits-2253). No
 * Quarkus: the rule is a function of the name and the namespaces the repository table lists.
 */
class MirrorTagRefTest {

  private static final Set<String> NAMESPACES = Set.of("hub", "quay", "redhat");

  private static MirrorTagRef resolve(String name, String tag) {
    return MirrorTagRef.resolve(new ImagePins.ImagePin(name, tag), NAMESPACES, "hub");
  }

  @Test
  void aBareNameIsHubsLibraryImage() {
    assertEquals(new MirrorTagRef("hub", "library/node", "24-alpine"), resolve("node", "24-alpine"));
  }

  @Test
  void aNameOutsideEveryNamespaceIsAHubImageWhole() {
    assertEquals(
        new MirrorTagRef("hub", "moby/buildkit", "v0.33.0"), resolve("moby/buildkit", "v0.33.0"));
  }

  @Test
  void aFirstSegmentNamingANamespaceIsThatNamespace() {
    assertEquals(
        new MirrorTagRef("hub", "library/postgres", "18.4"), resolve("hub/library/postgres", "18.4"));
    assertEquals(
        new MirrorTagRef("quay", "quarkus/ubi9-quarkus-mandrel-builder-image", "jdk-25"),
        resolve("quay/quarkus/ubi9-quarkus-mandrel-builder-image", "jdk-25"));
    assertEquals(
        new MirrorTagRef("hub", "nicolargo/glances", "4.5.6-full"),
        resolve("hub/nicolargo/glances", "4.5.6-full"));
  }

  @Test
  void onlyHubExpandsASingleSegmentImage() {
    // hub/postgres is library/postgres, as the daemon and OciMirrorUpstreams.normalize expand it;
    // quay has no implicit namespace.
    assertEquals(new MirrorTagRef("hub", "library/postgres", "18.4"), resolve("hub/postgres", "18.4"));
    assertEquals(new MirrorTagRef("quay", "single", "1"), resolve("quay/single", "1"));
  }

  @Test
  void theNamespacesAreTheLiveOnesNotAHardcodedList() {
    // A namespace registered later (ghcr) is read from the table; one that is gone is a Hub path.
    assertEquals(
        new MirrorTagRef("ghcr", "org/tool", "1"),
        MirrorTagRef.resolve(
            new ImagePins.ImagePin("ghcr/org/tool", "1"), Set.of("hub", "ghcr"), "hub"));
    assertEquals(
        new MirrorTagRef("hub", "quay/x", "1"),
        MirrorTagRef.resolve(new ImagePins.ImagePin("quay/x", "1"), Set.of("hub"), "hub"));
  }

  @Test
  void withNoHubNamespaceANameOutsideEveryNamespaceNamesNothing() {
    assertNull(MirrorTagRef.resolve(new ImagePins.ImagePin("node", "24"), Set.of("quay"), null));
  }

  @Test
  void aConfiguredPinSplitsOnTheLastColonAfterTheLastSlash() {
    assertEquals(
        new ImagePins.ImagePin("hub/nicolargo/glances", "4.5.6-full"),
        ImagePins.ImagePin.parse("hub/nicolargo/glances:4.5.6-full"));
    assertEquals(
        new ImagePins.ImagePin("moby/buildkit", "v0.33.0"),
        ImagePins.ImagePin.parse(" moby/buildkit:v0.33.0 "));
    assertThrows(IllegalArgumentException.class, () -> ImagePins.ImagePin.parse("moby/buildkit"));
    assertThrows(IllegalArgumentException.class, () -> ImagePins.ImagePin.parse("host:5000/img"));
    assertThrows(IllegalArgumentException.class, () -> ImagePins.ImagePin.parse("node:"));
    assertThrows(IllegalArgumentException.class, () -> ImagePins.ImagePin.parse(":24"));
  }

  @Test
  void anEmptyPinListIsNotAbsent() {
    assertThrows(IllegalArgumentException.class, () -> ImagePins.of(java.util.List.of()));
  }
}
