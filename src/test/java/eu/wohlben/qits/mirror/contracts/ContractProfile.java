package eu.wohlben.qits.mirror.contracts;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * <b>The provider contract's application</b>: golden-master recording and consumer pact
 * verification run in it, and nothing else does.
 *
 * <p>It gives the two package caches their SHIPPED upstreams. The suite's default points them at a
 * closed port, and {@code GET /mirror/api/repositories} answers with that value, so a golden master
 * recorded on it would show consumers {@code http://localhost:1}. Nothing here dials an upstream:
 * the contract only reads listings.
 *
 * <p>A profile of its own also means a Quarkus start of its own, on a database Flyway cleaned at
 * start, so no other test class's rows reach a recorded answer.
 */
public class ContractProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "qits.artifacts.npm.proxy.upstream", "https://registry.npmjs.org",
        "qits.artifacts.maven.proxy.upstream", "https://repo1.maven.org/maven2");
  }
}
