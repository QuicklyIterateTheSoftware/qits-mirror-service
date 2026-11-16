package eu.wohlben.qits.mirror.gc;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /mirror/api/gc/sweep} (qits-2253): who may call it, what a body may say, and that
 * what it answers is the run's receipt. What a run does is the eviction suites' subject; this is the
 * door.
 */
@QuarkusTest
class GcSweepDoorTest extends EvictionFixture {

  private static final String DOOR = "/mirror/api/gc/sweep";
  private static final String NODE_PINNED =
      "{\"pins\":{\"images\":[{\"name\":\"node\",\"version\":\"24-alpine\"}]},\"dryRun\":true}";

  @Inject CacheEvictionSweep sweep;

  @Test
  void anAnonymousCallerIsNotAdmitted() {
    given().contentType(ContentType.JSON).body(NODE_PINNED).when().post(DOOR).then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "operator", roles = {"qits:admin", "qits:desk-admin", "qits:agent"})
  void onlyTheMachineRoleIsAdmitted() {
    // The policy run is the orchestrator's; an operator's own dry run goes through the same machine.
    given().contentType(ContentType.JSON).body(NODE_PINNED).when().post(DOOR).then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "qits-orchestrator", roles = "qits:system")
  void aDryRunWithPinsAnswersTheReceiptAndDeletesNothing() {
    seedMirror();
    seedImage("hub", "library/node", "24-alpine", (byte) 31);

    given()
        .contentType(ContentType.JSON)
        .body(NODE_PINNED)
        .when()
        .post(DOOR)
        .then()
        .statusCode(200)
        .body("dryRun", is(true))
        .body("pins.supplied", is(true))
        .body("pins.images", is(1))
        .body("types", hasSize(3))
        .body("types.find { it.type == 'oci-mirror' }.window", is("P30D"))
        .body("types.find { it.type == 'oci-mirror' }.condemned", is(1))
        .body("types.find { it.type == 'oci-mirror' }.unpinned", is(1))
        .body("types.find { it.type == 'oci-mirror' }.superseded", is(0))
        .body("types.find { it.type == 'oci-mirror' }.pinned", is(1))
        .body("types.find { it.type == 'oci-mirror' }.deleted", is(0))
        .body("blobs.unlinked", is(0))
        .body("summary", containsString("Cache eviction (dry run):"));

    ociTags.getEntityManager().clear();
    assertTrue(ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isPresent());
  }

  @Test
  @TestSecurity(user = "qits-orchestrator", roles = "qits:system")
  void noBodyIsAWindowRunWithNoPins() {
    given()
        .when()
        .post(DOOR)
        .then()
        .statusCode(200)
        .body("dryRun", is(false))
        .body("pins.supplied", is(false))
        .body("pins.images", is(0));
  }

  @Test
  @TestSecurity(user = "qits-orchestrator", roles = "qits:system")
  void anEmptyPinListIsRefusedAndNothingRuns() {
    seedMirror();

    given()
        .contentType(ContentType.JSON)
        .body("{\"pins\":{\"images\":[]},\"dryRun\":false}")
        .when()
        .post(DOOR)
        .then()
        .statusCode(400)
        .body(containsString("pins.images is empty"));

    ociTags.getEntityManager().clear();
    assertTrue(ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isPresent());
  }

  @Test
  @TestSecurity(user = "qits-orchestrator", roles = "qits:system")
  void aMalformedBodyIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"pins\":{\"images\":[{\"name\":\"node\"}]}}")
        .when()
        .post(DOOR)
        .then()
        .statusCode(400)
        .body(containsString("pins.images[0]"));
    given().contentType(ContentType.JSON).body("{nope").when().post(DOOR).then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "qits-orchestrator", roles = "qits:system")
  void aCallWhileARunIsInFlightIsA409() {
    sweep.inFlight().set(true);
    try {
      given()
          .contentType(ContentType.JSON)
          .body(NODE_PINNED)
          .when()
          .post(DOOR)
          .then()
          .statusCode(409)
          .body(containsString("already running"));
    } finally {
      sweep.inFlight().set(false);
    }
  }
}
