package com.naqqa.auth.sso.saml;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.CookieUtils;
import com.naqqa.auth.security.NaqqaAuthenticationToken;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoErrorBody;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.Saml;
import com.naqqa.sso.saml.SamlPostMessage;
import com.naqqa.sso.saml.idp.AuthnRequestInfo;
import com.naqqa.sso.saml.idp.LogoutRequestInfo;
import com.naqqa.sso.saml.idp.SamlIdp;
import com.naqqa.sso.saml.idp.SamlSubject;
import com.naqqa.sso.session.Session;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@RestController
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "saml.enabled"}, havingValue = "true")
public class SamlIdpController {

    public static final String PENDING_KIND = "saml-pending";
    public static final String PENDING_COOKIE = "sso_saml_pending";
    public static final String STATUS_NO_AUTHN_CONTEXT = "urn:oasis:names:tc:SAML:2.0:status:NoAuthnContext";
    public static final String AUTO_SUBMIT = "document.forms[0].submit()";
    static final String SCRIPT_HASH = "sha256-" + java.util.Base64.getEncoder().encodeToString(Hashes.sha256(Bytes.utf8(AUTO_SUBMIT)));

    private final SamlIdp idp;
    private final SsoSessionManager sessions;
    private final AssuranceEngine assurance;
    private final SsoTicketStore tickets;
    private final UserRepository userRepository;
    private final SsoAudit audit;
    private final SsoProperties properties;
    private final String loginUrl;

    public SamlIdpController(SamlIdp idp, SsoSessionManager sessions, AssuranceEngine assurance, SsoTicketStore tickets,
                             UserRepository userRepository, SsoAudit audit, SsoProperties properties,
                             @Value("${frontend.url:}") String frontendUrl) {
        this.idp = idp;
        this.sessions = sessions;
        this.assurance = assurance;
        this.tickets = tickets;
        this.userRepository = userRepository;
        this.audit = audit;
        this.properties = properties;
        String configured = properties.getSaml().getIdp().getLoginUrl();
        this.loginUrl = configured != null && !configured.isBlank() ? configured : SamlIdpConfiguration.trimSlash(frontendUrl) + "/login";
    }

    record Current(AuthPrincipal principal, Session session, UserEntity user) {
    }

    static int requiredLevel(String classRef) {
        if (Saml.AC_PASSWORD_PROTECTED.equals(classRef)) {
            return 1;
        }
        if (Saml.AC_MFA.equals(classRef)) {
            return 2;
        }
        return -1;
    }

    static String acrKey(String classRef) {
        return Saml.AC_MFA.equals(classRef) ? "mfa" : "pwd";
    }

    @GetMapping(value = "/saml2/idp/metadata", produces = "application/samlmetadata+xml")
    public ResponseEntity<String> metadata() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/samlmetadata+xml")).body(idp.metadata());
    }

    @GetMapping("/saml2/idp/sso")
    public ResponseEntity<?> ssoRedirect(HttpServletRequest request, HttpServletResponse response) {
        AuthnRequestInfo info;
        try {
            info = idp.parseAuthnRequestRedirect(request.getQueryString());
        } catch (SsoException e) {
            return reject(e, null);
        }
        return decide(info, null, request, response);
    }

    @PostMapping("/saml2/idp/sso")
    public ResponseEntity<?> ssoPost(@RequestParam(name = "SAMLRequest", required = false) String samlRequest,
                                     @RequestParam(name = "RelayState", required = false) String relayState,
                                     HttpServletRequest request, HttpServletResponse response) {
        if (samlRequest == null) {
            return reject(new SsoException("SAML_MALFORMED"), null);
        }
        AuthnRequestInfo info;
        try {
            info = idp.parseAuthnRequestPost(samlRequest, relayState);
        } catch (SsoException e) {
            return reject(e, null);
        }
        return decide(info, null, request, response);
    }

    @GetMapping("/saml2/idp/continue")
    public ResponseEntity<?> resume(@RequestParam("ticket") String ticketId, HttpServletRequest request, HttpServletResponse response) {
        SsoTicketStore.Ticket ticket = tickets.peek(PENDING_KIND, ticketId).orElse(null);
        if (ticket == null) {
            throw SsoApiException.badRequest("SAML_TICKET_INVALID", "The sign-in request expired");
        }
        String cookie = SsoWeb.cookie(request, PENDING_COOKIE);
        String expected = ticket.data().get("browser");
        if (cookie == null || expected == null || !Bytes.constantTimeEquals(expected, hash(cookie))) {
            throw SsoApiException.forbidden("SAML_TICKET_FORBIDDEN", "The sign-in request belongs to another browser");
        }
        Current current = current();
        if (current == null) {
            throw SsoApiException.unauthorized("UNAUTHORIZED", "Authentication required");
        }
        if (ticket.userId() != null && !ticket.userId().equals(String.valueOf(current.user().getId()))) {
            throw SsoApiException.forbidden("SAML_TICKET_FORBIDDEN", "The sign-in request belongs to another user");
        }
        return decide(fromTicket(ticket), ticket, request, response);
    }

    private ResponseEntity<?> decide(AuthnRequestInfo info, SsoTicketStore.Ticket ticket, HttpServletRequest request,
                                     HttpServletResponse response) {
        List<String> requested = info.requestedAuthnContexts();
        if (!requested.isEmpty() && requested.stream().allMatch(c -> requiredLevel(c) < 0)) {
            audit.record("saml.sso", null, info.spEntityId(), "denied", Map.of("reason", "no_authn_context"));
            return error(info, STATUS_NO_AUTHN_CONTEXT, ticket);
        }
        Current current = current();
        boolean fresh = current != null && (!info.forceAuthn() || !current.session().authTime().isBefore(info.issueInstant()));
        if (current == null || !fresh) {
            if (info.isPassive()) {
                audit.record("saml.sso", null, info.spEntityId(), "denied", Map.of("reason", "no_passive"));
                return error(info, Saml.STATUS_NO_PASSIVE, ticket);
            }
            return toLogin(info, ticket, current == null ? null : String.valueOf(current.user().getId()), false, response);
        }
        Collection<String> amr = current.session().amr();
        String classRef = null;
        if (requested.isEmpty()) {
            classRef = AssuranceEngine.reachesLevel(amr, 2) ? Saml.AC_MFA : Saml.AC_PASSWORD_PROTECTED;
        } else {
            for (String c : requested) {
                int needed = requiredLevel(c);
                if (needed >= 0 && AssuranceEngine.reachesLevel(amr, needed)) {
                    classRef = c;
                    break;
                }
            }
        }
        String userId = String.valueOf(current.user().getId());
        if (classRef == null) {
            if (info.isPassive()) {
                audit.record("saml.sso", userId, info.spEntityId(), "denied", Map.of("reason", "no_passive_step_up"));
                return error(info, Saml.STATUS_NO_PASSIVE, ticket);
            }
            return toLogin(info, ticket, userId, true, response);
        }
        UserEntity user = current.user();
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        attributes.put("email", List.of(user.getEmail()));
        if (user.getFullName() != null) {
            attributes.put("fullName", List.of(user.getFullName()));
        }
        List<String> roles = user.getRoles().stream().filter(Objects::nonNull).map(RoleEntity::getName).sorted().toList();
        if (!roles.isEmpty()) {
            attributes.put("roles", roles);
        }
        SamlPostMessage message;
        try {
            message = idp.issueResponse(info, new SamlSubject(userId, user.getEmail(), current.session().authTime(), acrKey(classRef),
                    current.session().idHash(), attributes));
        } catch (SsoException e) {
            audit.record("saml.sso", userId, info.spEntityId(), "denied", Map.of("reason", e.code()));
            return reject(e, ticket);
        }
        if (ticket != null) {
            tickets.remove(PENDING_KIND, ticket.id());
            CookieUtils.setClientCookie(response, PENDING_COOKIE, "", 0, true);
        }
        sessions.touch(current.session().idHash(), info.spEntityId());
        audit.record("saml.sso", userId, info.spEntityId(), "success", Map.of("authnContext", classRef));
        return form(message);
    }

    private ResponseEntity<?> toLogin(AuthnRequestInfo info, SsoTicketStore.Ticket ticket, String userId, boolean stepUp,
                                      HttpServletResponse response) {
        String id = ticket != null ? ticket.id() : null;
        if (id == null) {
            id = Bytes.randomToken(24);
            String browser = Bytes.randomToken(32);
            Map<String, String> data = toData(info);
            data.put("browser", hash(browser));
            tickets.put(PENDING_KIND, id, userId, data, properties.getSaml().getIdp().getPendingTtl());
            CookieUtils.setClientCookie(response, PENDING_COOKIE, browser, (int) properties.getSaml().getIdp().getPendingTtl().getSeconds(), true);
        }
        String location = loginUrl + (loginUrl.contains("?") ? "&" : "?") + "saml=" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                + (stepUp ? "&stepUp=true" : "");
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping("/saml2/idp/slo")
    public ResponseEntity<?> sloRedirect(HttpServletRequest request) {
        LogoutRequestInfo info;
        try {
            info = idp.parseLogoutRequestRedirect(request.getQueryString());
        } catch (SsoException e) {
            return reject(e, null);
        }
        return logout(info);
    }

    @PostMapping("/saml2/idp/slo")
    public ResponseEntity<?> sloPost(@RequestParam(name = "SAMLRequest", required = false) String samlRequest,
                                     @RequestParam(name = "RelayState", required = false) String relayState) {
        if (samlRequest == null) {
            return reject(new SsoException("SAML_MALFORMED"), null);
        }
        LogoutRequestInfo info;
        try {
            info = idp.parseLogoutRequestPost(samlRequest, relayState);
        } catch (SsoException e) {
            return reject(e, null);
        }
        return logout(info);
    }

    private ResponseEntity<?> logout(LogoutRequestInfo info) {
        boolean success = true;
        String userId = null;
        if (Saml.NAMEID_EMAIL.equals(info.nameIdFormat()) || info.nameIdFormat() == null) {
            Optional<UserEntity> user = userRepository.findByEmailAnyCase(info.nameId());
            if (user.isPresent()) {
                userId = String.valueOf(user.get().getId());
                for (Session s : sessions.list(userId)) {
                    if (s.clientIds().contains(info.spEntityId())
                            && (info.sessionIndexes().isEmpty() || info.sessionIndexes().contains(s.idHash()))) {
                        sessions.revoke(userId, s.idHash());
                    }
                }
            }
        } else {
            success = false;
        }
        audit.record("saml.slo", userId, info.spEntityId(), success ? "success" : "partial", Map.of());
        try {
            return form(idp.logoutResponse(info, success));
        } catch (SsoException e) {
            return reject(e, null);
        }
    }

    private ResponseEntity<?> error(AuthnRequestInfo info, String status, SsoTicketStore.Ticket ticket) {
        try {
            SamlPostMessage message = idp.errorResponse(info, status);
            if (ticket != null) {
                tickets.remove(PENDING_KIND, ticket.id());
            }
            return form(message);
        } catch (SsoException e) {
            return reject(e, ticket);
        }
    }

    private ResponseEntity<?> reject(SsoException e, SsoTicketStore.Ticket ticket) {
        if (ticket != null) {
            tickets.remove(PENDING_KIND, ticket.id());
        }
        audit.record("saml.request", null, null, "rejected", Map.of("reason", e.code()));
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(SsoErrorBody.of(SsoErrorBody.codeOf(e.code()), "SAML request rejected"));
    }

    static ResponseEntity<String> form(SamlPostMessage message) {
        String csp = "default-src 'none'; script-src 'unsafe-hashes' '" + SCRIPT_HASH + "'; form-action " + origin(message.destination())
                + "; frame-ancestors 'none'; base-uri 'none'";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .header("Content-Security-Policy", csp)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache")
                .body(message.htmlForm());
    }

    static String origin(String url) {
        URI u = URI.create(url);
        return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    private Current current() {
        NaqqaAuthenticationToken token = SsoWeb.authentication();
        if (token == null || token.getPrincipal().isImpersonated()) {
            return null;
        }
        AuthPrincipal p = token.getPrincipal();
        Session session = sessions.find(p.getSessionId()).orElse(null);
        Long id = p.getUserId();
        if (session == null || id == null || !session.userId().equals(String.valueOf(id)) || assurance.sessionRestricted(session.idHash())) {
            return null;
        }
        UserEntity user = userRepository.findById(id).orElse(null);
        if (user == null || !user.canSignIn()) {
            return null;
        }
        return new Current(p, session, user);
    }

    static String hash(String value) {
        return Bytes.b64url(Hashes.sha256(Bytes.utf8(value)));
    }

    static Map<String, String> toData(AuthnRequestInfo info) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", info.id());
        m.put("sp", info.spEntityId());
        m.put("acs", info.assertionConsumerServiceUrl());
        if (info.relayState() != null) {
            m.put("relayState", info.relayState());
        }
        if (info.nameIdFormat() != null) {
            m.put("nameIdFormat", info.nameIdFormat());
        }
        m.put("forceAuthn", Boolean.toString(info.forceAuthn()));
        m.put("isPassive", Boolean.toString(info.isPassive()));
        m.put("contexts", String.join("\n", info.requestedAuthnContexts()));
        m.put("issueInstant", info.issueInstant().toString());
        m.put("signed", Boolean.toString(info.signed()));
        return m;
    }

    static AuthnRequestInfo fromTicket(SsoTicketStore.Ticket t) {
        Map<String, String> d = t.data();
        String contexts = d.getOrDefault("contexts", "");
        List<String> list = contexts.isEmpty() ? List.of() : new ArrayList<>(Arrays.asList(contexts.split("\n")));
        return new AuthnRequestInfo(d.get("id"), d.get("sp"), d.get("acs"), d.get("relayState"), d.get("nameIdFormat"),
                Boolean.parseBoolean(d.get("forceAuthn")), Boolean.parseBoolean(d.get("isPassive")), list,
                Instant.parse(d.get("issueInstant")), Boolean.parseBoolean(d.get("signed")));
    }
}
