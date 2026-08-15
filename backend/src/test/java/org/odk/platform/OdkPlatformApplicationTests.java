package org.odk.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "odk.test.application-context-smoke=true")
@ActiveProfiles("test")
class OdkPlatformApplicationTests {

    @Test
    void contextLoads() {
        // Verifies Spring context loads successfully
    }
}