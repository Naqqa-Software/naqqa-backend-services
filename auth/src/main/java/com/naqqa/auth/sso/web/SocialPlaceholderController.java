package com.naqqa.auth.sso.web;

import com.naqqa.auth.sso.support.SsoErrorBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@ConditionalOnExpression("!('${naqqa.sso.enabled:false}' == 'true' && '${naqqa.sso.social.enabled:false}' == 'true')")
public class SocialPlaceholderController {

    @GetMapping({"/oauth2/authorization/{provider}", "/login/oauth2/code/{provider}"})
    public ResponseEntity<Map<String, Object>> notConfigured(@PathVariable String provider) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(SsoErrorBody.of("NOT_CONFIGURED", "Social login is not configured"));
    }
}
