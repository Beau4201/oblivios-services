package org.sovereignos.guardian.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.sovereignos.guardian.contracts.GuardianIncidentEvidenceV1;
import org.junit.jupiter.api.Test;

class GuardianIncidentEvidenceContentBoundaryTest {
    private static final Pattern FORBIDDEN_EXPORT_VERBS = Pattern.compile(
        "export|upload|send|share|sync|publish|transmit|backup|cloud|analytics|broadcast",
        Pattern.CASE_INSENSITIVE);

    private static final List<Class<?>> EVIDENCE_API_SURFACE = List.of(
        GuardianIncidentEvidenceV1.class,
        GuardianEvidenceRetentionPeriod.class,
        AppLabelResolver.class,
        ResolvedAppIdentity.class,
        GuardianIncidentEvidenceObservation.class,
        GuardianIncidentEvidenceDeriver.class,
        GuardianIncidentEvidenceStore.class,
        InMemoryGuardianIncidentEvidenceStore.class);

    @Test
    void evidenceContractExposesOnlyTheDocumentedFields() {
        assertEquals(
            List.of("schemaVersion", "packageName", "appLabel", "iconKey", "permissionCategory",
                "firstObservedAt", "lastObservedAt", "occurrenceCount", "ruleReason",
                "compatibilityImpact", "registeredActionId", "collapsed"),
            Arrays.stream(GuardianIncidentEvidenceV1.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList());
    }

    @Test
    void evidenceApiSurfaceHasNoExportOrHandoffMethod() {
        for (Class<?> type : EVIDENCE_API_SURFACE) {
            for (Method method : type.getMethods()) {
                assertFalse(FORBIDDEN_EXPORT_VERBS.matcher(method.getName()).find(),
                    () -> type.getSimpleName() + "#" + method.getName() + " looks like an export path");
            }
        }
    }

    @Test
    void evidenceApiSurfaceNeverTouchesNetworkOrIoTypes() {
        for (Class<?> type : EVIDENCE_API_SURFACE) {
            for (Method method : type.getMethods()) {
                assertFalse(isNetworkOrIoType(method.getReturnType()),
                    () -> type.getSimpleName() + "#" + method.getName() + " returns a network/IO type");
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertFalse(isNetworkOrIoType(parameterType),
                        () -> type.getSimpleName() + "#" + method.getName() + " accepts a network/IO type");
                }
            }
        }
    }

    private static boolean isNetworkOrIoType(Class<?> type) {
        String packageName = type.getPackageName();
        return packageName.startsWith("java.net") || packageName.startsWith("java.io");
    }
}
