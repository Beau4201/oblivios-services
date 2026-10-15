// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.ui

sealed interface GuardianUiState {
    data class Detected(
        val severity: String,
        val actionId: String,
        val title: String,
        val summary: String,
        val compatibilityImpact: String,
    ) : GuardianUiState

    data class Verified(val auditId: String, val detail: String) : GuardianUiState

    data class Undone(val auditId: String, val detail: String) : GuardianUiState

    data class Failure(val detail: String) : GuardianUiState
}
