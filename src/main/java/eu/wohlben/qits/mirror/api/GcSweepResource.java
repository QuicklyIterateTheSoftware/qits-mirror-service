package eu.wohlben.qits.mirror.api;

import eu.wohlben.qits.mirror.gc.CacheEvictionSweep;
import eu.wohlben.qits.mirror.gc.EvictionConfig;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;

/**
 * The sweep door: {@code POST /mirror/api/gc/sweep} (qits-2253).
 *
 * <p><b>The only trigger the eviction sweep has.</b> qits-orchestrator calls it on its own cadence —
 * the mirror has no schedule of its own any more — with the image pins it read from qits-maintenance
 * in the body: {@code {"pins":{"images":[{"name":"node","version":"24-alpine"},…]},"dryRun":false}}.
 * The mirror never fetches the pins itself, so a sweep cannot fail closed on another service; see
 * {@link SweepRequest} for what an absent, empty or malformed pin list means.
 *
 * <p><b>{@code qits:system} alone</b>, as qits-artifacts' {@code sweepGc} is: the caller is a
 * machine running the platform's housekeeping, and an operator who wants to look first has the dry
 * run — a body with {@code "dryRun":true} — through the same machine. Unlike the invalidate door
 * this is not a repair an operator reaches for; it is a policy run, and one caller keeps it one.
 *
 * <p>Answers:
 *
 * <ul>
 *   <li>{@code 200} — the run's receipt, {@link MirrorSweepReport}.
 *   <li>{@code 400} — a body this door does not accept; nothing ran.
 *   <li>{@code 409} — another sweep is still running; nothing ran, and the caller retries on its next
 *       tick. Refused rather than queued: see {@link CacheEvictionSweep}.
 *   <li>{@code 503} — {@code qits.mirror.eviction.enabled=false}; nothing ran.
 * </ul>
 */
@Path("/gc")
@Produces(MediaType.APPLICATION_JSON)
public class GcSweepResource {

  @Inject CacheEvictionSweep sweep;
  @Inject EvictionConfig config;

  /**
   * Runs one sweep of every cache type with the pins in the body, and answers its receipt.
   *
   * <p>A {@code POST} and nothing else, so nothing that merely reads can delete a byte. The plan it
   * applies is computed inside this request; there is no way to submit one.
   */
  @POST
  @Path("/sweep")
  @Operation(operationId = "sweepGc")
  @RolesAllowed("qits:system")
  public MirrorSweepReport sweep(
      @RequestBody(
              required = false,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON,
                      schema = @Schema(type = SchemaType.OBJECT)))
          String body) {
    SweepRequest request;
    try {
      request = SweepRequest.parse(body);
    } catch (IllegalArgumentException refused) {
      throw refuse(
          Response.Status.BAD_REQUEST,
          "the sweep body is {\"pins\":{\"images\":[{\"name\":…,\"version\":…},…]},\"dryRun\":…}"
              + " or absent: "
              + refused.getMessage());
    }
    if (!config.enabled()) {
      throw refuse(
          Response.Status.SERVICE_UNAVAILABLE,
          "cache eviction is disabled (qits.mirror.eviction.enabled=false); nothing swept");
    }
    try {
      return MirrorSweepReport.of(sweep.run(request.pins(), request.dryRun()));
    } catch (CacheEvictionSweep.SweepInProgress running) {
      throw refuse(Response.Status.CONFLICT, running.getMessage());
    }
  }

  /** A refusal with its reason in the body, as {@code MirrorEntryEviction} answers one. */
  private static WebApplicationException refuse(Response.Status status, String message) {
    return new WebApplicationException(
        Response.status(status).type(MediaType.TEXT_PLAIN).entity(message).build());
  }
}
