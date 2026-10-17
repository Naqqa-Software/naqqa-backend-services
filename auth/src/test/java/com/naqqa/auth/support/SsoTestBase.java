package com.naqqa.auth.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.AuthorityRepository;
import com.naqqa.auth.repository.RoleRepository;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.service.security.PasswordService;
import com.naqqa.sso.mfa.Base32;
import com.naqqa.sso.mfa.Totp;
import com.naqqa.sso.mfa.TotpConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(TestSupportConfiguration.class)
public abstract class SsoTestBase {

    public static final String BASE = "/api/auth";
    public static final String PASSWORD = "Corr3ct-Horse-Battery!";
    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected RoleRepository roleRepository;
    @Autowired
    protected AuthorityRepository authorityRepository;
    @Autowired
    protected PasswordService passwordService;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected CapturingTransport transport;
    @Autowired
    protected MongoTemplate mongo;

    protected static String uniqueEmail(String tag) {
        return tag + "-" + SEQ.incrementAndGet() + "-" + ThreadLocalRandom.current().nextInt(1_000_000) + "@example.test";
    }

    protected static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(1, 250) + "." + r.nextInt(1, 250) + "." + r.nextInt(1, 250);
    }

    protected Browser browser() {
        return new Browser(mvc, randomIp());
    }

    protected AuthorityEntity authority(String name) {
        return authorityRepository.findAll().stream().filter(a -> a.getName().equals(name)).findFirst().orElseGet(() -> {
            AuthorityEntity a = new AuthorityEntity();
            a.setName(name);
            return authorityRepository.save(a);
        });
    }

    protected synchronized RoleEntity role(String name, String... authorities) {
        RoleEntity role = roleRepository.findByName(name).orElseGet(() -> {
            RoleEntity r = new RoleEntity();
            r.setName(name);
            r.setAuthorities(new HashSet<>());
            return roleRepository.save(r);
        });
        boolean changed = false;
        Set<AuthorityEntity> set = role.getAuthorities() == null ? new HashSet<>() : new HashSet<>(role.getAuthorities());
        for (String a : authorities) {
            if (set.stream().noneMatch(x -> x.getName().equals(a))) {
                set.add(authority(a));
                changed = true;
            }
        }
        if (changed) {
            role.setAuthorities(set);
            role = roleRepository.save(role);
        }
        return role;
    }

    protected UserEntity createUser(String email, String encodedPassword, RoleEntity... roles) {
        Set<RoleEntity> set = new HashSet<>();
        for (RoleEntity r : roles) {
            set.add(r);
        }
        if (set.isEmpty()) {
            set.add(role("USER", "profile:read"));
        }
        UserEntity user = UserEntity.builder()
                .email(email)
                .fullName("Test " + email.substring(0, Math.min(8, email.length())))
                .password(encodedPassword)
                .roles(set)
                .emailVerified(true)
                .build();
        return userRepository.save(user);
    }

    protected void defaultTenantAdmin(UserEntity user) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("default")), new Update().addToSet("admins", String.valueOf(user.getId())),
                "sso_tenants");
    }

    protected UserEntity user(String email) {
        return createUser(email, passwordService.hash(PASSWORD));
    }

    protected UserEntity bcryptUser(String email) {
        return createUser(email, new BCryptPasswordEncoder().encode(PASSWORD));
    }

    protected MvcResult login(Browser b, String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return b.post(BASE + "/login", body);
    }

    protected Browser loggedIn(String email) {
        Browser b = browser();
        MvcResult r = login(b, email, PASSWORD);
        assertEquals(200, Browser.status(r), r.getResponse().getStatus() + " " + content(r));
        return b;
    }

    protected static String content(MvcResult r) {
        try {
            return r.getResponse().getContentAsString();
        } catch (Exception e) {
            return "";
        }
    }

    protected String totpCode(String secret) {
        return Totp.generate(Base32.decode(secret), clock.instant().getEpochSecond(), TotpConfig.defaults());
    }

    protected void nextTotpStep() {
        clock.advance(Duration.ofSeconds(31));
    }

    protected static JsonNode json(MvcResult r) {
        return Browser.json(r);
    }

    protected static int status(MvcResult r) {
        return Browser.status(r);
    }
}
