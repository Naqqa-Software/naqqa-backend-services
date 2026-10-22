package com.naqqa.sso.authz;

public sealed interface Condition permits Condition.TenantMatch, Condition.OwnerMatch, Condition.AttributeEquals {
    boolean test(Principal principal, Resource resource);

    record TenantMatch() implements Condition {
        @Override
        public boolean test(Principal principal, Resource resource) {
            return principal.tenantId() != null && principal.tenantId().equals(resource.tenantId());
        }
    }

    record OwnerMatch() implements Condition {
        @Override
        public boolean test(Principal principal, Resource resource) {
            return resource.ownerId() != null && resource.ownerId().equals(principal.userId());
        }
    }

    record AttributeEquals(Source source, String key, String expected) implements Condition {
        public enum Source {
            SUBJECT, RESOURCE
        }

        @Override
        public boolean test(Principal principal, Resource resource) {
            String actual = source == Source.SUBJECT ? principal.attributes().get(key) : resource.attributes().get(key);
            return actual != null && actual.equals(expected);
        }
    }
}
