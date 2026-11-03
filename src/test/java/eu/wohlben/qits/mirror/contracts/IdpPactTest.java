package eu.wohlben.qits.mirror.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import io.quarkus.oidc.OidcConfigurationMetadata;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.junit.jupiter.api.Test;

/**
 * What this service asks qits-idp-service for (ticket qits-1149), {@code
 * pacts/qits-mirror-service_qits-idp-service.json}. When the machine gate is on, quarkus-oidc reads
 * the discovery document at startup and then the JWKS its {@code jwks_uri} names. It checks a
 * token's {@code iss} against the document's {@code issuer}, and finds the signing key by {@code
 * kid}, built from {@code kty}, {@code n} and {@code e} and selected by {@code alg} and {@code use}.
 *
 * <p>The calls parse the answers with the classes quarkus-oidc uses: {@link
 * OidcConfigurationMetadata} and jose4j's {@link JsonWebKeySet}.
 *
 * <p>The third row is independent of that gate: the client-credentials mint of the named client
 * {@code qits} (epic qits-428), {@link #MINT} — {@code POST /idp/token} with HTTP Basic, {@code
 * grant_type=client_credentials} and {@code audience=qits-platform}, recorded by qits-idp as {@code
 * issueToken} in {@value #A_SERVICE_CLIENT_CALLING_ITS_PEERS}. qits-telemetry-core's exporters spend
 * the token on every OTLP export, so those exports are its trigger. {@link IdpTokenMintPactTest}
 * runs it through the real oidc client, because the client only exists inside the application.
 */
class IdpPactTest {

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final String STATE = "a published signing key";

  static final GoldenInteraction DISCOVERY =
      GoldenInteraction.of(Trigger.event("StartupEvent"), STATE, "getOpenIdConfiguration")
          .consumes("issuer", "jwks_uri", "token_endpoint")
          // quarkus-oidc compares each token's iss with this value, so a different string breaks us.
          .exact("issuer");

  static final GoldenInteraction JWKS =
      GoldenInteraction.of(Trigger.event("StartupEvent"), STATE, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  /** The qits-idp state that records the client-credentials mint of a service client. */
  static final String A_SERVICE_CLIENT_CALLING_ITS_PEERS = "a service client calling its peers";

  /** The mint, caused by whichever OTLP export needs a bearer first. */
  static final GoldenInteraction MINT =
      GoldenInteraction.of(
              Trigger.of("call", "call", "qits-telemetry-core's BearerHeaders (every OTLP export)"),
              A_SERVICE_CLIENT_CALLING_ITS_PEERS,
              "issueToken")
          .header("Authorization", "{authorization}")
          .consumes("access_token", "expires_in");

  static final ConsumerPact PACT =
      ConsumerPact.of("qits-mirror-service", IDP, DISCOVERY, JWKS, MINT);

  @Test
  void quarkusOidcReadsTheDiscoveryDocument() {
    PACT.run(DISCOVERY, (url, recorded) -> {
      OidcConfigurationMetadata metadata = new OidcConfigurationMetadata(new JsonObject(get(url + recorded.path())));
      // The issuer is compared with each token's iss, so it must be the value qits-idp signs with.
      assertEquals(IDP.json(STATE, "getOpenIdConfiguration").path("issuer").asText(), metadata.getIssuer());
      assertNotNull(metadata.getJsonWebKeySetUri());
      assertNotNull(metadata.getTokenUri());
    });
  }

  @Test
  void quarkusOidcFindsThePublishedSigningKey() {
    PACT.run(JWKS, (url, recorded) -> {
      JsonWebKeySet keys = new JsonWebKeySet(get(url + recorded.path()));
      assertFalse(keys.getJsonWebKeys().isEmpty());
      JsonWebKey key = keys.findJsonWebKey(recorded.params().get("kid"), RsaJsonWebKey.KEY_TYPE, "sig", "RS256");
      assertNotNull(key, () -> "an RSA signing key for RS256 named by the state's kid in " + keys.getJsonWebKeys());
      assertNotNull(((RsaJsonWebKey) key).getRsaPublicKey().getModulus());
    });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  private static String get(String url) throws Exception {
    HttpResponse<String> answer = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, answer.statusCode());
    return answer.body();
  }
}
