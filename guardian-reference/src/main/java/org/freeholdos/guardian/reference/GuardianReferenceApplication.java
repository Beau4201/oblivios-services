// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.reference;

import java.io.PrintWriter;
import org.freeholdos.guardian.application.GuardianApplicationResult;
import org.freeholdos.guardian.remediation.RemediationOutcome;

public final class GuardianReferenceApplication {
    private GuardianReferenceApplication() { }

    public static void main(String[] args) {
        System.exit(run(args, new PrintWriter(System.out, true)));
    }

    static int run(String[] args, PrintWriter out) {
        if (args.length != 1 || !"background-location".equals(args[0])) {
            out.println("Usage: background-location");
            return 2;
        }

        out.println("LOCAL REFERENCE SIMULATION — NOT ANDROID ENFORCEMENT");
        try {
            GuardianReferenceScenario scenario = GuardianReferenceScenario.create();
            GuardianApplicationResult.DetectedIncident detected = requireDetected(
                scenario.detectBackgroundLocation());
            GuardianApplicationResult.RemediationCompleted approved = requireRemediation(
                scenario.approve(
                    detected.incident().incidentId(), detected.incident().recommendedActionId()));
            GuardianApplicationResult.UndoCompleted undone = requireUndo(scenario.undo(approved.auditId()));
            out.println("DETECTED " + detected.incident().severity());
            out.println(detected.incident().recommendedActionId());
            out.println(approved.status());
            out.println(undone.status());
            return approved.status() == RemediationOutcome.Status.VERIFIED_SUCCESS
                && undone.status() == RemediationOutcome.Status.UNDONE ? 0 : 1;
        } catch (ReferenceScenarioFailure failure) {
            out.println(failure.getMessage());
            return 1;
        }
    }

    private static GuardianApplicationResult.DetectedIncident requireDetected(GuardianApplicationResult result) {
        if (result instanceof GuardianApplicationResult.DetectedIncident detected) {
            return detected;
        }
        throw new ReferenceScenarioFailure("DETECTION_FAILED");
    }

    private static GuardianApplicationResult.RemediationCompleted requireRemediation(
        GuardianApplicationResult result) {
        if (result instanceof GuardianApplicationResult.RemediationCompleted remediation) {
            return remediation;
        }
        throw new ReferenceScenarioFailure("REMEDIATION_FAILED");
    }

    private static GuardianApplicationResult.UndoCompleted requireUndo(GuardianApplicationResult result) {
        if (result instanceof GuardianApplicationResult.UndoCompleted undo) {
            return undo;
        }
        throw new ReferenceScenarioFailure("UNDO_FAILED");
    }

    private static final class ReferenceScenarioFailure extends RuntimeException {
        private ReferenceScenarioFailure(String message) {
            super(message);
        }
    }
}
