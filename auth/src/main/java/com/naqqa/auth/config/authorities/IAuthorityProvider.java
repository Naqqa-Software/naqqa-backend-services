package com.naqqa.auth.config.authorities;

import java.util.List;

/**
 * Contract for the consuming application to provide the list of
 * protected resources and actions.
 */
public interface IAuthorityProvider {

    /**
     * Returns a list of all domain resources (e.g., "course", "blog", "user").
     */
    List<String> getProtectedResources();

    /**
     * Returns a list of base actions (e.g., "create", "read_all", "delete_own").
     */
    List<String> getBaseActions();

    // Resource-specific extra actions
    List<String> getExtraActionsForResource(String resource);
}