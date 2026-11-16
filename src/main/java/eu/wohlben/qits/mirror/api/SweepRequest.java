package eu.wohlben.qits.mirror.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.mirror.gc.ImagePins;
import java.util.ArrayList;
import java.util.List;

/**
 * The sweep door's body, read: {@code {"pins":{"images":[{"name":…,"version":…},…]},"dryRun":…}}.
 *
 * <p>Read from the raw string rather than bound, as qits-artifacts' {@code GcPlanController} reads
 * its {@code POST /gc/sweep}: unknown members are ignored, so qits-orchestrator can send what it
 * sends every GC door, and every refusal names what was wrong rather than answering Jackson's
 * mapping error.
 *
 * <ul>
 *   <li>No body, no {@code pins}, or no {@code pins.images} — the caller could not read the pins.
 *       {@link ImagePins#ABSENT}: mirrored tags keep the window rule.
 *   <li>{@code pins.images} an empty array — refused. "Nothing is pinned" is never true on this
 *       estate, and believing it would condemn every cached tag.
 *   <li>Malformed JSON, a member of the wrong shape, an entry without a usable {@code name} or
 *       {@code version} — refused. A caller that meant to supply pins and mistyped one must not be
 *       answered with a run that condemned the tag that pin was for.
 * </ul>
 *
 * @param pins the image pins, or {@link ImagePins#ABSENT}
 * @param dryRun whether the caller asked for a plan only
 */
record SweepRequest(ImagePins pins, boolean dryRun) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * The body, read.
   *
   * @throws IllegalArgumentException the body is not one this door accepts, with the reason
   */
  static SweepRequest parse(String body) {
    if (body == null || body.isBlank()) {
      return new SweepRequest(ImagePins.ABSENT, false);
    }
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JsonProcessingException malformed) {
      throw new IllegalArgumentException(
          "the body is not JSON: " + malformed.getOriginalMessage());
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("the body is a JSON object, or absent");
    }
    return new SweepRequest(pins(root.get("pins")), dryRun(root.get("dryRun")));
  }

  private static boolean dryRun(JsonNode dryRun) {
    if (dryRun == null || dryRun.isNull()) {
      return false;
    }
    if (!dryRun.isBoolean()) {
      throw new IllegalArgumentException("dryRun is true or false, got " + dryRun);
    }
    return dryRun.booleanValue();
  }

  private static ImagePins pins(JsonNode pins) {
    if (pins == null || pins.isNull()) {
      return ImagePins.ABSENT;
    }
    if (!pins.isObject()) {
      throw new IllegalArgumentException("pins is an object, as in {\"images\":[…]}, got " + pins);
    }
    JsonNode images = pins.get("images");
    if (images == null || images.isNull()) {
      return ImagePins.ABSENT;
    }
    if (!images.isArray()) {
      throw new IllegalArgumentException("pins.images is an array, got " + images);
    }
    if (images.isEmpty()) {
      throw new IllegalArgumentException(
          "pins.images is empty, which would condemn every cached tag; leave pins.images out when"
              + " the pins could not be read");
    }
    List<ImagePins.ImagePin> read = new ArrayList<>();
    for (int i = 0; i < images.size(); i++) {
      JsonNode entry = images.get(i);
      if (!entry.isObject()
          || !entry.path("name").isTextual()
          || !entry.path("version").isTextual()) {
        throw new IllegalArgumentException(
            "pins.images[" + i + "] is {\"name\":…,\"version\":…} with two strings, got " + entry);
      }
      try {
        read.add(new ImagePins.ImagePin(entry.get("name").asText(), entry.get("version").asText()));
      } catch (IllegalArgumentException wrong) {
        throw new IllegalArgumentException("pins.images[" + i + "]: " + wrong.getMessage());
      }
    }
    return ImagePins.of(read);
  }
}
