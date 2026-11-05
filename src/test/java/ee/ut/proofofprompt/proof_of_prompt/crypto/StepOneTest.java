package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Maven/JUnit bridge. No Spring context or external service is required. */
class StepOneTest {
    @Test
    void allProtocolChecks() throws Exception {
        var resource = Objects.requireNonNull(getClass().getResource("/step1-test-vectors.properties"));
        CoreTests.main(new String[] { Path.of(resource.toURI()).toString() });
    }
}
