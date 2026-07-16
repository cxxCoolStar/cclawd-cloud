package ai.openagent.bootstrap.status;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PlatformStatusServiceTest {

    @Test
    void formatsUptimeUsingStableCompactUnits() {
        assertEquals("0s", PlatformStatusService.formatDuration(Duration.ZERO));
        assertEquals("1m 5s", PlatformStatusService.formatDuration(Duration.ofSeconds(65)));
        assertEquals("2h 3m 4s", PlatformStatusService.formatDuration(Duration.ofSeconds(7_384)));
        assertEquals("1d 1h 1m 1s", PlatformStatusService.formatDuration(Duration.ofSeconds(90_061)));
    }
}

