// SPDX-License-Identifier: Apache-2.0
package org.oblivios.guardian.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class GuardianReferenceApplicationTest {
    @Test
    void backgroundLocationScenarioPrintsSimulationNoticeAndSuccessfulUndo() {
        CapturedRun run = run("background-location");

        assertEquals(0, run.exitCode());
        assertTrue(run.stdout().contains("LOCAL REFERENCE SIMULATION — NOT ANDROID ENFORCEMENT"));
        assertTrue(run.stdout().contains("DETECTED"));
        assertTrue(run.stdout().contains("REVOKE_BACKGROUND_LOCATION"));
        assertTrue(run.stdout().contains("VERIFIED_SUCCESS"));
        assertTrue(run.stdout().contains("UNDONE"));
        assertFalse(run.stdout().contains("org.example.app"));
    }

    @Test
    void unsupportedArgumentExitsTwo() {
        CapturedRun run = run("anything-else");

        assertEquals(2, run.exitCode());
    }

    private static CapturedRun run(String argument) {
        StringWriter output = new StringWriter();
        int exitCode = GuardianReferenceApplication.run(new String[] {argument}, new PrintWriter(output, true));
        return new CapturedRun(exitCode, output.toString());
    }

    private record CapturedRun(int exitCode, String stdout) { }
}
