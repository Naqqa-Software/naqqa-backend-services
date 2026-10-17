package com.naqqa.auth.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.oidc.ClientAuthMethod;
import com.naqqa.sso.oidc.ClientRegistration;
import com.naqqa.sso.oidc.ClientStore;
import com.naqqa.sso.oidc.ClientType;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

public class MongoClientStore implements ClientStore {

    public static final String COLLECTION = "sso_oidc_clients";

    private final MongoTemplate template;
    private final Map<String, ClientRegistration> configured;

    public MongoClientStore(MongoTemplate template, Collection<ClientRegistration> configured) {
        this.template = template;
        Map<String, ClientRegistration> m = new LinkedHashMap<>();
        for (ClientRegistration c : configured) {
            if (m.put(c.clientId(), c) != null) {
                throw new IllegalStateException("duplicate OIDC client " + c.clientId());
            }
        }
        this.configured = Map.copyOf(m);
        template.indexOps(COLLECTION).ensureIndex(new Index().on("tenantId", Sort.Direction.ASC).named("tenantId"));
    }

    public boolean isConfigured(String clientId) {
        return configured.containsKey(clientId);
    }

    @Override
    public Optional<ClientRegistration> find(String clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        ClientRegistration c = configured.get(clientId);
        if (c != null) {
            return Optional.of(c);
        }
        Document d = template.findById(clientId, Document.class, COLLECTION);
        if (d == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(fromDocument(d));
        } catch (SsoException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(ClientRegistration client) {
        if (configured.containsKey(client.clientId())) {
            throw new SsoException("oidc.client_configured");
        }
        template.save(toDocument(client), COLLECTION);
    }

    public boolean create(ClientRegistration client) {
        if (configured.containsKey(client.clientId())) {
            return false;
        }
        try {
            template.insert(toDocument(client), COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean delete(String clientId) {
        if (clientId == null || configured.containsKey(clientId)) {
            return false;
        }
        return template.remove(new Query(Criteria.where("_id").is(clientId)), COLLECTION).getDeletedCount() > 0;
    }

    public List<ClientRegistration> listConfigured(String tenantId) {
        return configured.values().stream().filter(c -> c.tenantId().equals(tenantId)).toList();
    }

    public List<ClientRegistration> listManaged(String tenantId) {
        List<ClientRegistration> out = new ArrayList<>();
        for (Document d : template.find(new Query(Criteria.where("tenantId").is(tenantId)), Document.class, COLLECTION)) {
            try {
                out.add(fromDocument(d));
            } catch (SsoException | IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    static Document toDocument(ClientRegistration c) {
        return new Document("_id", c.clientId())
                .append("tenantId", c.tenantId())
                .append("clientName", c.clientName())
                .append("type", c.type().name())
                .append("authMethod", c.authMethod().value())
                .append("secretHash", c.secretHash())
                .append("jwks", c.jwks())
                .append("redirectUris", new ArrayList<>(c.redirectUris()))
                .append("postLogoutRedirectUris", new ArrayList<>(c.postLogoutRedirectUris()))
                .append("grantTypes", new ArrayList<>(new TreeSet<>(c.grantTypes())))
                .append("scopes", new ArrayList<>(new TreeSet<>(c.scopes())))
                .append("accessTokenTtl", c.accessTokenTtl() == null ? null : c.accessTokenTtl().getSeconds())
                .append("idTokenTtl", c.idTokenTtl() == null ? null : c.idTokenTtl().getSeconds())
                .append("backchannelLogoutUri", c.backchannelLogoutUri())
                .append("firstParty", c.firstParty())
                .append("allowedAcr", new ArrayList<>(new TreeSet<>(c.allowedAcr())))
                .append("minAcr", c.minAcr())
                .append("signingAlg", c.signingAlg().name())
                .append("requirePar", c.requirePar())
                .append("requireDpop", c.requireDpop())
                .append("requireState", c.requireState())
                .append("audience", new ArrayList<>(c.audience()))
                .append("dynamic", c.dynamic());
    }

    private static List<String> list(Document d, String key) {
        List<String> v = d.getList(key, String.class);
        return v == null ? List.of() : v;
    }

    private static Duration seconds(Document d, String key) {
        Number n = d.get(key, Number.class);
        return n == null ? null : Duration.ofSeconds(n.longValue());
    }

    static ClientRegistration fromDocument(Document d) {
        return new ClientRegistration(d.getString("tenantId"), d.getString("_id"), d.getString("clientName"),
                ClientType.valueOf(d.getString("type")), ClientAuthMethod.fromValue(d.getString("authMethod")), d.getString("secretHash"),
                d.getString("jwks"), list(d, "redirectUris"), list(d, "postLogoutRedirectUris"), new LinkedHashSet<>(list(d, "grantTypes")),
                new LinkedHashSet<>(list(d, "scopes")), seconds(d, "accessTokenTtl"), seconds(d, "idTokenTtl"),
                d.getString("backchannelLogoutUri"), Boolean.TRUE.equals(d.getBoolean("firstParty")),
                new LinkedHashSet<>(list(d, "allowedAcr")), d.getString("minAcr"),
                d.getString("signingAlg") == null ? SigningAlgorithm.RS256 : SigningAlgorithm.valueOf(d.getString("signingAlg")),
                Boolean.TRUE.equals(d.getBoolean("requirePar")), Boolean.TRUE.equals(d.getBoolean("requireDpop")),
                Boolean.TRUE.equals(d.getBoolean("requireState")), list(d, "audience"), Boolean.TRUE.equals(d.getBoolean("dynamic")));
    }
}
