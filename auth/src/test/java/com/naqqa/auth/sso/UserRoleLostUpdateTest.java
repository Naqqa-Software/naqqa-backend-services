package com.naqqa.auth.sso;

import com.mongodb.DBRef;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import com.naqqa.auth.repository.SubRoleRepository;
import com.naqqa.auth.service.admin.AdminUserService;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.SsoEnabledTestBase;
import com.naqqa.sso.util.Bytes;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertEvent;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(UserRoleLostUpdateTest.ConcurrentWrite.class)
class UserRoleLostUpdateTest extends SsoEnabledTestBase {

    static final AtomicLong TARGET = new AtomicLong(-1);
    static final AtomicReference<Runnable> ON_READ = new AtomicReference<>();

    @TestConfiguration
    static class ConcurrentWrite {
        @Bean
        AbstractMongoEventListener<UserEntity> concurrentUserWrite() {
            return new AbstractMongoEventListener<>() {
                @Override
                public void onAfterConvert(AfterConvertEvent<UserEntity> event) {
                    if (event.getSource() != null && Objects.equals(event.getSource().getId(), TARGET.get())) {
                        Runnable write = ON_READ.getAndSet(null);
                        if (write != null) {
                            write.run();
                        }
                    }
                }
            };
        }
    }

    @Autowired
    SubRoleRepository subRoleRepository;
    @Autowired
    AdminUserService adminUsers;

    @AfterEach
    void disarm() {
        TARGET.set(-1);
        ON_READ.set(null);
    }

    void concurrently(UserEntity target, Bson update) {
        TARGET.set(target.getId());
        ON_READ.set(() -> mongo.getCollection("users").updateOne(Filters.eq("_id", target.getId()), update));
    }

    Set<String> roleNames(Long id) {
        return userRepository.findById(id).orElseThrow().getRoles().stream().filter(Objects::nonNull).map(RoleEntity::getName)
                .collect(Collectors.toSet());
    }

    Set<String> subRoleNames(Long id) {
        return userRepository.findById(id).orElseThrow().getSubRoles().stream().filter(Objects::nonNull).map(SubRoleEntity::getName)
                .collect(Collectors.toSet());
    }

    SubRoleEntity subRole(String name) {
        SubRoleEntity s = new SubRoleEntity();
        s.setName(name);
        s.setAuthorities(new HashSet<>(Set.of(authority("profile:read"))));
        return subRoleRepository.save(s);
    }

    @Test
    @DisplayName("SSO-T-auth-767 F-CONF-22 granting a role or sub-role never restores one another administrator removed while the grant was in flight, and removing one never drops one granted meanwhile")
    void roleChangesKeepConcurrentChanges() {
        String t = Bytes.randomToken(6).replaceAll("[^A-Za-z0-9]", "X").toUpperCase();
        RoleEntity revoked = role("FCONF22_REVOKED_" + t, "profile:read");
        RoleEntity granted = role("FCONF22_GRANTED_" + t, "profile:read");
        RoleEntity other = role("FCONF22_OTHER_" + t, "profile:read");
        UserEntity manager = createUser(uniqueEmail("fconf22-manager"), passwordService.hash(PASSWORD),
                role("FCONF22_MANAGER_" + t, "users:update", "profile:read"));
        UserEntity target = createUser(uniqueEmail("fconf22-target"), passwordService.hash(PASSWORD), role("USER", "profile:read"), revoked);
        Browser m = loggedIn(manager.getEmail());

        concurrently(target, Updates.pull("roles", new DBRef("roles", revoked.getId())));
        MvcResult grant = m.post("/api/admin/users/" + target.getId() + "/roles/" + granted.getName(), null);
        assertEquals(200, status(grant), content(grant));
        assertNull(ON_READ.get(), "the concurrent removal did not run");
        Set<String> roles = roleNames(target.getId());
        assertTrue(roles.contains(granted.getName()), roles.toString());
        assertFalse(roles.contains(revoked.getName()), "a removed role came back: " + roles);

        concurrently(target, Updates.addToSet("roles", new DBRef("roles", other.getId())));
        MvcResult remove = m.delete("/api/admin/users/" + target.getId() + "/roles/" + granted.getName());
        assertEquals(200, status(remove), content(remove));
        assertNull(ON_READ.get(), "the concurrent grant did not run");
        roles = roleNames(target.getId());
        assertFalse(roles.contains(granted.getName()), roles.toString());
        assertTrue(roles.contains(other.getName()), "a granted role was lost: " + roles);

        SubRoleEntity subRevoked = subRole("FCONF22_SUB_REVOKED_" + t);
        SubRoleEntity subGranted = subRole("FCONF22_SUB_GRANTED_" + t);
        mongo.getCollection("users").updateOne(Filters.eq("_id", target.getId()), Updates.addToSet("subRoles", new DBRef("sub_roles", subRevoked.getId())));
        concurrently(target, Updates.pull("subRoles", new DBRef("sub_roles", subRevoked.getId())));
        adminUsers.addSubRole(target.getId(), subGranted.getName());
        assertNull(ON_READ.get(), "the concurrent sub-role removal did not run");
        Set<String> subRoles = subRoleNames(target.getId());
        assertTrue(subRoles.contains(subGranted.getName()), subRoles.toString());
        assertFalse(subRoles.contains(subRevoked.getName()), "a removed sub-role came back: " + subRoles);
    }
}
