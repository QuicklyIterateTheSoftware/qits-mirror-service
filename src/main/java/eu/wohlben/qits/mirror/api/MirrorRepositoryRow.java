package eu.wohlben.qits.mirror.api;

import java.time.Instant;

/**
 * One cache root, as the explorer lists it.
 *
 * <p><b>Two fields are promised and the rest is not</b>, and the client is written to that: {@code
 * name} and {@code type} have real columns, everything else is drawn as a dynamic column by whatever
 * the value turns out to be. So a field added here appears in the UI with no client release — and a
 * field that would be a guess must not be added at all, because a plausible wrong number in a table
 * is worse than a missing one.
 *
 * <p>Declaration order is the wire order and the column order after it, so identity comes first and
 * bookkeeping last.
 *
 * @param name the repository row's name — the path segment a client addresses: {@code
 *     /artifacts/npm/npmjs/…}, {@code /artifacts/maven/central/…}, {@code /v2/quay/…}
 * @param type the <b>kebab wire form</b> ({@code npm-proxy}, {@code maven-proxy}, {@code
 *     oci-mirror}), never the stored screaming-snake key. The three are all this service registers.
 * @param upstream what this root is a cache <b>of</b> — the whole reason a row here is interesting.
 *     A config URL for the two package caches, the registry domain for an OCI namespace. Null where
 *     nothing answers, which is a row whose upstream was deleted: the namespace keeps serving what
 *     is cached and can fetch nothing new, and a blank cell is the honest picture of that.
 * @param createdAt when the row was written. Cheap — it is on the row already.
 * @param evictionWindow how long an entry here may sit unused before the next sweep evicts it, as
 *     an ISO-8601 duration ({@code P30D}, {@code P90D}; whole days are spelled in days, never as
 *     {@code PT720H}) — so a page can say "entries unused longer than this are evicted by the next
 *     sweep" without a copy of the configuration. It is the type's {@code
 *     qits.mirror.eviction.window.<wire name>}. Null when the sweep is switched off ({@code
 *     qits.mirror.eviction.enabled=false}) or the type has no window: an unswept cache has no
 *     window, and printing one would promise an eviction that never comes. <b>The WINDOW rule
 *     only</b>: an OCI tag is also judged by the image pins the sweep is handed at sweep time — a
 *     pinned tag is kept and an unpinned one condemned whatever its age — and this row does not
 *     know those pins.
 */
public record MirrorRepositoryRow(
    String name, String type, String upstream, Instant createdAt, String evictionWindow) {}
