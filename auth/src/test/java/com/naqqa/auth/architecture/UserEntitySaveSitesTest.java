package com.naqqa.auth.architecture;

import com.naqqa.auth.AuthApplication;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.flow.SsoAccounts;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.support.SsoEnabledTestBase;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserEntitySaveSitesTest extends SsoEnabledTestBase {

    static final String REPOSITORY = "com/naqqa/auth/repository/UserRepository";
    static final String ENTITY = "com/naqqa/auth/entity/auth/UserEntity";
    static final Set<String> WRITES = Set.of("save", "saveAll", "insert");
    static final Set<String> TEMPLATES = Set.of("org/springframework/data/mongodb/core/MongoTemplate",
            "org/springframework/data/mongodb/core/MongoOperations", "org/springframework/data/mongodb/core/FluentMongoOperations",
            "org/springframework/data/mongodb/core/ExecutableInsertOperation", "org/springframework/data/mongodb/core/ExecutableUpdateOperation");
    static final Set<String> TEMPLATE_WRITES = Set.of("save", "insert", "insertAll", "bulkInsert", "findAndReplace", "replace", "replaceOne");
    static final Set<String> CREATION_SITES = Set.of("DefaultAuthService#confirmEmail", "DefaultAdminUserService#createUser",
            "DefaultSocialAuthService#createUser", "SsoAccounts#create", "AuthAppConfig#lambda$applicationRunner");

    @Autowired
    SsoAccounts accounts;

    static String site(Bytecode.Ref ref) {
        String owner = ref.owner().substring(ref.owner().lastIndexOf('/') + 1);
        owner = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
        String name = ref.name();
        if (name.startsWith("lambda$")) {
            name = "lambda$" + name.substring(7, name.indexOf('$', 7));
        }
        return owner + "#" + name;
    }

    @Test
    @DisplayName("SSO-T-auth-768 F-CONF-18 the only full-document saves of UserEntity left are account creation and the startup seed: each builds a new entity without an id, so Spring Data inserts it and a colliding id is refused instead of overwriting a stored account; no code that handles UserEntity writes a whole document through the Mongo template (F-CONF-32)")
    void onlyCreationSavesTheWholeUser() {
        Bytecode code = Bytecode.of(AuthApplication.class, "com/naqqa/auth");
        Set<String> sites = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        for (Bytecode.Info m : code.methods()) {
            boolean touchesUser = m.types.contains(ENTITY) || m.strings.contains("users")
                    || m.calls.stream().anyMatch(c -> c.owner().equals(ENTITY) || c.desc().contains("L" + ENTITY + ";"));
            boolean templateWrite = m.calls.stream().anyMatch(c -> TEMPLATES.contains(c.owner()) && TEMPLATE_WRITES.contains(c.name())
                    && (!c.desc().substring(0, c.desc().indexOf(')')).contains("Ljava/lang/String;") || m.strings.contains("users")));
            if (touchesUser && templateWrite) {
                problems.add(site(m.ref) + " writes a whole document through the Mongo template where it handles UserEntity; use UserFieldOperations");
            }
            boolean writes = m.calls.stream().anyMatch(c -> c.owner().equals(REPOSITORY) && WRITES.contains(c.name()));
            if (!writes) {
                continue;
            }
            String site = site(m.ref);
            sites.add(site);
            boolean creates = m.calls.stream().anyMatch(c -> c.owner().equals(ENTITY) && (c.name().equals("builder") || c.name().equals("<init>")));
            boolean setsId = m.calls.stream().anyMatch(c -> c.owner().equals(ENTITY) && c.name().equals("setId"));
            if (!creates || setsId) {
                problems.add(site + " creates=" + creates + " setsId=" + setsId);
            }
        }
        assertEquals(CREATION_SITES, sites);
        assertTrue(problems.isEmpty(), String.join("\n", problems));

        UserEntity existing = user(uniqueEmail("save-sites-existing"));
        Document stored = mongo.getCollection("users").find(new Document("_id", existing.getId())).first();
        Document seq = mongo.getCollection("database_sequences").find(new Document("_id", "users_seq")).first();
        long current = ((Number) seq.get("seq")).longValue();
        mongo.updateFirst(new Query(Criteria.where("_id").is("users_seq")), new Update().set("seq", existing.getId() - 1), "database_sequences");
        try {
            SsoApiException conflict = assertThrows(SsoApiException.class,
                    () -> accounts.create(uniqueEmail("save-sites-new"), "New", List.of(), true, true));
            assertEquals("ACCOUNT_EXISTS", conflict.getCode());
            mongo.updateFirst(new Query(Criteria.where("_id").is("users_seq")), new Update().set("seq", existing.getId() - 1), "database_sequences");
            assertThrows(DuplicateKeyException.class, () -> userRepository.save(UserEntity.builder().email(uniqueEmail("save-sites-raw"))
                    .fullName("Raw").roles(Set.of(role("USER", "profile:read"))).build()));
        } finally {
            mongo.updateFirst(new Query(Criteria.where("_id").is("users_seq")), new Update().max("seq", current), "database_sequences");
        }
        assertEquals(stored, mongo.getCollection("users").find(new Document("_id", existing.getId())).first());
    }
}
