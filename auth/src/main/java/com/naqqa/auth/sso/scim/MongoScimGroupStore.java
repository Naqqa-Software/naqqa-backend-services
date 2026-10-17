package com.naqqa.auth.sso.scim;

import com.naqqa.sso.scim.ScimGroupStore;
import com.naqqa.sso.scim.ScimResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "scim.enabled"}, havingValue = "true")
public class MongoScimGroupStore extends MongoScimStore implements ScimGroupStore {

    public static final String COLLECTION = "sso_scim_groups";

    public MongoScimGroupStore(MongoTemplate template) {
        super(template, COLLECTION, "displayName", "displayNameLower");
        template.indexOps(COLLECTION).ensureIndex(new Index().on("tenantId", Sort.Direction.ASC)
                .on("attributes.members.value", Sort.Direction.ASC).named("tenant_members"));
    }

    @Override
    public Optional<ScimResource> findByDisplayName(String tenantId, String displayName) {
        return byUnique(tenantId, displayName);
    }
}
