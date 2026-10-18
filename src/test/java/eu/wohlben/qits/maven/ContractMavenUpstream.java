package eu.wohlben.qits.maven;

import io.quarkus.arc.ClientProxy;

/**
 * <b>Central, played by {@link StubMavenRepository}, for the provider contract</b> (ticket
 * qits-1149): the one door through which {@code mirror/contracts/ProviderStates} reaches the stub,
 * which is package-private here.
 *
 * <p><b>Why the bean is re-pointed rather than the profile.</b> {@code ContractProfile} keeps the
 * SHIPPED upstream on purpose: {@code GET /mirror/api/repositories} answers with it, and a golden
 * master recorded on a stub's random port would show consumers {@code http://127.0.0.1:41773}. So
 * the listing keeps reading the configuration while the proxy's own upstream field, the one {@link
 * MavenUpstream} dials, is pointed at the stub. Every byte the cache then serves went through the
 * real fetch, store and serve path; only the far end is a stub, as it is in every other test here.
 */
public final class ContractMavenUpstream {

  private ContractMavenUpstream() {}

  /** Points the running proxy at the stub and empties what the stub hosts. Idempotent. */
  public static void pointAtTheStub(MavenUpstream upstream) {
    try {
      var field = MavenUpstream.class.getDeclaredField("upstream");
      field.setAccessible(true);
      field.set(ClientProxy.unwrap(upstream), StubMavenRepository.INSTANCE.baseUrl());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("MavenUpstream no longer has an 'upstream' field", e);
    }
    StubMavenRepository.INSTANCE.reset();
  }

  /** Publishes one artifact's {@code maven-metadata.xml} upstream, listing these versions. */
  public static void hostMetadata(String groupId, String artifactId, String... versions) {
    StubMavenRepository.INSTANCE.hostMetadata(
        groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml",
        StubMavenRepository.metadataDocument(groupId, artifactId, versions));
  }
}
