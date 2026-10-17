package com.naqqa.auth.sso.scim;

import com.naqqa.sso.scim.ScimResource;
import com.naqqa.sso.scim.ScimUserStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "scim.enabled"}, havingValue = "true")
public class MongoScimUserStore extends MongoScimStore implements ScimUserStore {

    public static final String COLLECTION = "sso_scim_users";

    public MongoScimUserStore(MongoTemplate template) {
        super(template, COLLECTION, "userName", "userNameLower");
    }

    @Override
    public Optional<ScimResource> findByUserName(String tenantId, String userName) {
        return byUnique(tenantId, userName);
    }
}
