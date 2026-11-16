package eu.wohlben.qits.mirror.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.mirror.gc.ImagePins;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The sweep door's body rules, without an application (qits-2253). */
class SweepRequestTest {

  @Test
  void pinsAndDryRunAreReadAndUnknownMembersIgnored() {
    SweepRequest request =
        SweepRequest.parse(
            "{\"pins\":{\"images\":[{\"name\":\"hub/library/postgres\",\"version\":\"18.4\"},"
                + "{\"name\":\"node\",\"version\":\"24-alpine\",\"source\":\"Dockerfile\"}],"
                + "\"deployments\":{}},\"dryRun\":true,\"runId\":\"x\"}");

    assertTrue(request.dryRun());
    assertEquals(
        List.of(
            new ImagePins.ImagePin("hub/library/postgres", "18.4"),
            new ImagePins.ImagePin("node", "24-alpine")),
        request.pins().images());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "{}", "{\"pins\":null}", "{\"pins\":{}}", "{\"pins\":{\"images\":null}}"})
  void absentPinsAreTheWindowRuleNotARefusal(String body) {
    SweepRequest request = SweepRequest.parse(body);

    assertFalse(request.pins().present());
    assertFalse(request.dryRun());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"pins\":{\"images\":[]}}",
        "{not json",
        "[]",
        "{\"pins\":[]}",
        "{\"pins\":{\"images\":{}}}",
        "{\"pins\":{\"images\":[\"node:24\"]}}",
        "{\"pins\":{\"images\":[{\"name\":\"node\"}]}}",
        "{\"pins\":{\"images\":[{\"name\":\"node\",\"version\":24}]}}",
        "{\"pins\":{\"images\":[{\"name\":\"\",\"version\":\"24\"}]}}",
        "{\"pins\":{\"images\":[{\"name\":\"node:24\",\"version\":\"24\"}]}}",
        "{\"pins\":{\"images\":[{\"name\":\"node\",\"version\":\"a/b\"}]}}",
        "{\"dryRun\":\"yes\"}"
      })
  void anEmptyListOrAMalformedBodyIsRefused(String body) {
    assertThrows(IllegalArgumentException.class, () -> SweepRequest.parse(body));
  }
}
