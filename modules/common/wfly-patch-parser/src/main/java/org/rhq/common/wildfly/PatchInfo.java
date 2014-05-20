package org.rhq.common.wildfly;

/**
 * A wrapper object around the 2 actual descriptions of the 2 types of the Wildfly patches. Use the {@link #is(Class)}
 * and {@link #as(Class)} methods to check for and cast to the appropriate types.
 * <p/>
 * The type of the patch can either be {@link org.rhq.common.wildfly.Patch} or
 * {@link org.rhq.common.wildfly.PatchBundle}.
 *
 * @author Lukas Krejci
 * @since 4.12
 */
public final class PatchInfo {

    private final Object patch;

    PatchInfo(Patch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("patch == null");
        }
        this.patch = patch;
    }

    PatchInfo(PatchBundle patchBundle) {
        if (patchBundle == null) {
            throw new IllegalArgumentException("patchBundle == null");
        }
        this.patch = patchBundle;
    }

    /**
     * Checks if the type of this patch is compatible with the provided one.
     */
    public boolean is(Class<?> patchClass) {
        return patchClass.isAssignableFrom(patch.getClass());
    }

    /**
     * Casts this patch to the provided type. It is recommended to check for the applicability of the cast using the
     * {@link #is(Class)} method first.
     */
    public <T> T as(Class<T> patchClass) {
        return patchClass.cast(patch);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PatchInfo)) {
            return false;
        }

        PatchInfo patchInfo = (PatchInfo) o;

        if (!patch.equals(patchInfo.patch)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return patch.hashCode();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("PatchInfo[");
        sb.append(patch);
        sb.append(']');
        return sb.toString();
    }
}
