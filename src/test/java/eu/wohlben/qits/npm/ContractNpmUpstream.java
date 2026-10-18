package eu.wohlben.qits.npm;

import io.quarkus.arc.ClientProxy;

/**
 * <b>npmjs, played by {@link StubNpmRegistry}, for the provider contract</b> (ticket qits-1149):
 * {@code maven/ContractMavenUpstream}'s twin, and re-pointed for the same reason — the shipped
 * upstream stays in the configuration that {@code GET /mirror/api/repositories} reports, while the
 * field {@link NpmUpstream} dials is pointed at the stub.
 */
public final class ContractNpmUpstream {

  private ContractNpmUpstream() {}

  /** Points the running proxy at the stub and empties what the stub hosts. Idempotent. */
  public static void pointAtTheStub(NpmUpstream upstream) {
    try {
      var field = NpmUpstream.class.getDeclaredField("upstream");
      field.setAccessible(true);
      field.set(ClientProxy.unwrap(upstream), StubNpmRegistry.INSTANCE.baseUrl());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("NpmUpstream no longer has an 'upstream' field", e);
    }
    StubNpmRegistry.INSTANCE.reset();
  }

  /** Publishes a package upstream at these versions, the last one tagged {@code latest}. */
  public static void hostVersions(String name, String... versions) {
    TinyPackage[] packages = new TinyPackage[versions.length];
    for (int i = 0; i < versions.length; i++) {
      packages[i] = TinyPackage.of(name, versions[i]);
    }
    StubNpmRegistry.INSTANCE.hostVersions(name, packages);
  }
}
