package eu.wohlben.qits.mirror.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A repository row's {@code evictionWindow} spells whole days as days (qits-2358): {@link
 * Duration#toString} would render the shipped {@code P30D} as {@code PT720H}.
 */
class EvictionWindowRenderingTest {

  @Test
  void wholeDaysAreSpelledInDays() {
    assertEquals("P30D", MirrorExplorer.iso(Duration.parse("P30D")));
    assertEquals("P90D", MirrorExplorer.iso(Duration.parse("P90D")));
    assertEquals("P1D", MirrorExplorer.iso(Duration.ofHours(24)));
  }

  @Test
  void anythingElseIsDurationToString() {
    assertEquals("PT36H", MirrorExplorer.iso(Duration.ofHours(36)));
    assertEquals("PT30M", MirrorExplorer.iso(Duration.ofMinutes(30)));
    assertEquals("PT0S", MirrorExplorer.iso(Duration.ZERO));
  }
}
