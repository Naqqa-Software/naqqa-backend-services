package com.naqqa.auth.config.authorities;

import java.util.List;

public interface IAuthorityProvider {

    List<String> getProtectedResources();

    List<String> getBaseActions();

    List<String> getExtraActionsForResource(String resource);
}