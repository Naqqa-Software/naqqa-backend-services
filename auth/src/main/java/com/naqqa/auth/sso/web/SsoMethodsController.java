package com.naqqa.auth.sso.web;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SecondFactors;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("${auth.api.base-path:/api/auth}")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoMethodsController {

    private final SsoClientPolicy clientPolicy;
    private final SsoProperties properties;

    @GetMapping("/methods")
    public Map<String, Object> methods(HttpServletRequest request) {
        SsoClientPolicy.Policy policy = clientPolicy.forRequest(request);
        Map<String, Object> out = new LinkedHashMap<>(policy.describe());
        out.put("hotp", AssuranceEngine.enabled(policy, SecondFactors.TOTP) && properties.getHotp().isEnabled());
        return out;
    }
}
