package org.rhq.common.wildfly;

/**
 * Contains the metadata about a Wildfly patch.
 *
 * @author Lukas Krejci
 * @since 4.12
 */
public final class Patch {
    public enum Type {
        ONE_OFF, CUMULATIVE;

        @Override
        public String toString() {
            switch (this) {
            case ONE_OFF:
                return "one-off";
            case CUMULATIVE:
                return "cumulative";
            default:
                throw new AssertionError("toString() of Patch.Type inconsistent with its definition.");
            }
        }
    }

    private final String id;
    private final Type type;
    private final String identityName;
    private final String targetVersion;
    private final String description;
    private final String contents;

    public Patch(String id, Type type, String identityName, String targetVersion, String description, String contents) {
        this.id = id;
        this.type = type;
        this.identityName = identityName;
        this.targetVersion = targetVersion;
        this.description = description;
        this.contents = contents;
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public String getIdentityName() {
        return identityName;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public String getDescription() {
        return description;
    }

    public String getContents() {
        return contents;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Patch)) {
            return false;
        }

        Patch patch = (Patch) o;

        if (id != null ? !id.equals(patch.id) : patch.id != null) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Patch[");
        sb.append("description='").append(description).append('\'');
        sb.append(", id='").append(id).append('\'');
        sb.append(", identityName='").append(identityName).append('\'');
        sb.append(", targetVersion='").append(targetVersion).append('\'');
        sb.append(", type=").append(type);
        sb.append(']');
        return sb.toString();
    }
}
