package org.rhq.common.wildfly;

import java.util.Iterator;

/**
 * Contains metadata about a Wildfly patch bundle.
 *
 * @author Lukas Krejci
 * @since 4.12
 */
public final class PatchBundle implements Iterable<PatchBundle.Element> {

    /**
     * A patch bundle contains a bunch of elements described by this class.
     */
    public static final class Element {
        private final String fileName;
        private final Patch patch;

        public Element(String fileName, Patch patch) {
            if (fileName == null) {
                throw new IllegalArgumentException("fileName == null");
            }

            if (patch == null) {
                throw new IllegalArgumentException("patch == null");
            }

            this.fileName = fileName;
            this.patch = patch;
        }

        public String getFileName() {
            return fileName;
        }

        public Patch getPatch() {
            return patch;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("Element[");
            sb.append("fileName='").append(fileName).append('\'');
            sb.append(", patch=").append(patch);
            sb.append(']');
            return sb.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Element)) {
                return false;
            }

            Element element = (Element) o;

            if (!fileName.equals(element.fileName)) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            return fileName.hashCode();
        }
    }

    private final Iterable<PatchBundle.Element> elements;
    private final String contents;

    PatchBundle(Iterable<Element> elements, String contents) {
        this.contents = contents;
        if (elements == null) {
            throw new IllegalArgumentException("elements == null");
        }

        this.elements = elements;
    }

    /**
     * Returns an unmodifiable iterator over the elements contained in this patch bundle.
     */
    @Override
    public Iterator<Element> iterator() {
        return new Iterator<Element>() {
            Iterator<Element> wrapped = elements.iterator();

            @Override
            public boolean hasNext() {
                return wrapped.hasNext();
            }

            @Override
            public Element next() {
                return wrapped.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    public String getContents() {
        return contents;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PatchBundle)) {
            return false;
        }

        PatchBundle elements1 = (PatchBundle) o;

        if (!elements.equals(elements1.elements)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder bld = new StringBuilder("PatchBundle[");
        Iterator<Element> it = iterator();

        if (it.hasNext()) {
            bld.append(it.next());

            while(it.hasNext()) {
                bld.append(", ").append(it.next());
            }
        }

        return bld.append("]").toString();
    }
}
