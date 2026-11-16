package eu.wohlben.qits.mirror.gc;

/**
 * One identity as a plan judged it, with the rule that judged it.
 *
 * <p>The rule travels with the identity rather than with the plan as a whole because a report that
 * says only "kept" is not reviewable: "accessed inside the P30D window" is an answer somebody can
 * argue with.
 *
 * @param repository the {@code artifact_repository} row the identity lives in
 * @param identity the type's own coordinate
 * @param rule the sentence that condemned or saved it
 * @param regardlessOfWindow whether it was judged on its type's word rather than on its access time:
 *     condemned as superseded or unpinned, or kept as pinned. Its {@code rule} then leads with that
 *     label ({@link #label()})
 */
public record JudgedIdentity(
    String repository, String identity, String rule, boolean regardlessOfWindow) {

  /** A line the window decided. */
  public JudgedIdentity(String repository, String identity, String rule) {
    this(repository, identity, rule, false);
  }

  /**
   * The label of a type's verdict — {@code superseded}, {@code unpinned}, {@code pinned} — or null
   * for a line the window decided.
   */
  public String label() {
    return regardlessOfWindow ? CachedIdentity.labelOf(rule) : null;
  }
}
