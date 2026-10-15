// SPDX-License-Identifier: Apache-2.0
package org.freeholdos.guardian.evidence;

/**
 * Resolves the owner-visible app label and icon key for a package name. In this
 * reference implementation there is no real Android package manager available;
 * production callers supply a resolver backed by whatever local lookup is used.
 */
@FunctionalInterface
public interface AppLabelResolver {
    ResolvedAppIdentity resolve(String packageName);
}
