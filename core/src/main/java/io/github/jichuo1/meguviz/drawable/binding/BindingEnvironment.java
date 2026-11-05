package io.github.jichuo1.meguviz.drawable.binding;

/**
 * Immutable revisions a prepared result depends on. A result computed under
 * one environment must not be installed under another.
 */
public final class BindingEnvironment {
    private final long resourceRevision;
    private final long registryRevision;

    public BindingEnvironment(long resourceRevision, long registryRevision) {
        if (resourceRevision < 0 || registryRevision < 0) throw new IllegalArgumentException("negative revision");
        this.resourceRevision = resourceRevision;
        this.registryRevision = registryRevision;
    }

    public long resourceRevision() { return resourceRevision; }
    public long registryRevision() { return registryRevision; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BindingEnvironment)) return false;
        BindingEnvironment that = (BindingEnvironment) other;
        return resourceRevision == that.resourceRevision && registryRevision == that.registryRevision;
    }

    @Override public int hashCode() {
        return 31 * (int) (resourceRevision ^ (resourceRevision >>> 32))
                + (int) (registryRevision ^ (registryRevision >>> 32));
    }

    @Override public String toString() {
        return "BindingEnvironment{resource=" + resourceRevision + ", registry=" + registryRevision + '}';
    }
}
