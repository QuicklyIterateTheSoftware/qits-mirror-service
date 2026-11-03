package eu.wohlben.qits.mirror.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The client-credentials token mint, as quarkus-oidc-client sends it</b> (qits-428): the {@code
 * qits} named client application.properties ships, switched on and pointed at a pact mock server
 * that answers what {@link IdpPactTest#MINT} promises. The row is proven here, rather than
 * in {@link IdpPactTest}, because the client only exists inside a running application, and
 * on a port this profile picks before the application starts, since the client reads its idp from
 * config.
 *
 * <p>No class of this repository's own reads the client: its reader is qits-telemetry-core's
 * header supplier, which only runs when the OpenTelemetry SDK builds its exporters, and the SDK is
 * off under {@code %test}. So the test asks the named client directly, the way qits-docs-service
 * and qits-system-service proved the same client.
 *
 * <p><b>The port is a system property, not a static field.</b> Quarkus loads the test profile in
 * its own classloader, so a static field initialised there and the one this test instance reads are
 * two fields with two free ports: the client dialled one while the mock listened on the other. The
 * first to ask picks the port and parks it in {@link #PORT_PROPERTY}; both classloaders read it
 * back.
 */
@QuarkusTest
@TestProfile(IdpTokenMintPactTest.MockIdp.class)
class IdpTokenMintPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  /** Where the mock server's port is parked, shared by both classloaders. */
  static final String PORT_PROPERTY = "qits.mirror.contract.idp-mock-port";

  /**
   * The {@code qits} client on, as the state's own caller, against the mock server. The id and
   * secret are the Basic pair {@link IdpPactTest#MINT}'s state hands the request, decoded,
   * so the client sends exactly what the pact row expects.
   */
  public static class MockIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> params = IdpPactTest.IDP.params(IdpPactTest.MINT.state());
      String basic = params.get("authorization").substring("Basic ".length());
      String[] pair =
          new String(Base64.getDecoder().decode(basic), StandardCharsets.UTF_8).split(":", 2);
      return Map.of(
          "quarkus.oidc-client.qits.client-enabled", "true",
          "quarkus.oidc-client.qits.auth-server-url", "http://127.0.0.1:" + port() + "/idp",
          "quarkus.oidc-client.qits.client-id", pair[0],
          "quarkus.oidc-client.qits.credentials.secret", pair[1]);
    }
  }

  @Inject
  @NamedOidcClient("qits")
  OidcClient client;

  @Test
  void theQitsClientMintsTheTokenTheIdpAnswers() {
    String minted =
        IdpPactTest.IDP
            .json(IdpPactTest.MINT.state(), IdpPactTest.MINT.operationId())
            .path("access_token")
            .asText();
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            IdpPactTest.PACT.pact(List.of(IdpPactTest.MINT)),
            MockProviderConfig.Companion.httpConfig("127.0.0.1", port(), PactSpecVersion.V4),
            (mockServer, context) -> {
              assertEquals(
                  minted, client.getTokens().await().atMost(Duration.ofSeconds(20)).getAccessToken());
              return null;
            });
    assertInstanceOf(PactVerificationResult.Ok.class, result, String.valueOf(result));
  }

  /** The port the pact mock server listens on: chosen free once per JVM, whichever loader asks. */
  static synchronized int port() {
    String parked = System.getProperty(PORT_PROPERTY);
    if (parked != null) {
      return Integer.parseInt(parked);
    }
    try (ServerSocket socket = new ServerSocket(0)) {
      int port = socket.getLocalPort();
      System.setProperty(PORT_PROPERTY, String.valueOf(port));
      return port;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
