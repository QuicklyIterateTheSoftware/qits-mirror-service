package eu.wohlben.qits.mirror.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running mirror</b>, the way qits-projects-service
 * and qits-edge-service do.
 *
 * <p>The pacts come off the test classpath: each consumer publishes its pact as a jar holding
 * {@code pacts/<consumer>_qits-mirror-service.json} (repository names on both sides), this repo
 * pins that jar as a test dependency, and qits-maintenance bumps the pin when the consumer releases
 * a changed pact. {@link ClasspathPactLoader} finds them all.
 *
 * <p><b>One consumer pins a pact</b> (qits-1149): qits-maintenance-service ({@code
 * eu.wohlben.qits:qits-maintenance-service-pacts-qits-mirror-service}, test-scoped in the pom) —
 * its ScanService reading a cached maven artifact's {@code maven-metadata.xml} and an npm
 * packument, each with versions and absent upstream. So this class carries no {@code
 * @IgnoreNoPactsToVerify} and {@link ClasspathPactLoader#REQUIRED} is true: a classpath that lost
 * the pin fails rather than verifying nothing. A new consumer's jar is pinned beside it, and any
 * state its pact names that {@link ProviderStates} does not answer is added there with its golden
 * master.
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP,
 * unauthenticated: the listings are open. Every {@code @State} method delegates to {@link
 * ProviderStates}; {@link #target} fails an unknown state, and an interaction without {@code
 * comments.references.qits-call} or {@code qits-trigger}.
 */
@QuarkusTest
@TestProfile(ContractProfile.class)
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-mirror-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-mirror does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    context.verifyInteraction();
  }

  // --- the states: each one line into the registry -------------------------------------------

  @State(ProviderStates.THE_DEFAULT_CACHES)
  Map<String, String> theDefaultCaches() {
    return states.params(ProviderStates.THE_DEFAULT_CACHES);
  }

  @State(ProviderStates.AN_NPM_CACHE_HOLDING_A_PACKAGE)
  Map<String, String> anNpmCacheHoldingAPackage() {
    return states.params(ProviderStates.AN_NPM_CACHE_HOLDING_A_PACKAGE);
  }

  @State(ProviderStates.A_CACHED_MAVEN_ARTIFACT)
  Map<String, String> aCachedMavenArtifact() {
    return states.params(ProviderStates.A_CACHED_MAVEN_ARTIFACT);
  }

  @State(ProviderStates.A_CACHED_NPM_PACKAGE)
  Map<String, String> aCachedNpmPackage() {
    return states.params(ProviderStates.A_CACHED_NPM_PACKAGE);
  }

  @State(ProviderStates.NO_MAVEN_ARTIFACT_UPSTREAM)
  Map<String, String> noMavenArtifactUpstream() {
    return states.params(ProviderStates.NO_MAVEN_ARTIFACT_UPSTREAM);
  }

  @State(ProviderStates.NO_NPM_PACKAGE_UPSTREAM)
  Map<String, String> noNpmPackageUpstream() {
    return states.params(ProviderStates.NO_NPM_PACKAGE_UPSTREAM);
  }
}
