package com.naqqa.auth.config.authorities;

import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.repository.AuthorityRepository;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AuthorityRegistry {

    private final List<IAuthorityProvider> providers;
    private final AuthorityRepository authorityRepository;

    public AuthorityRegistry(List<IAuthorityProvider> providers,
                             AuthorityRepository authorityRepository) {
        this.providers = providers;
        this.authorityRepository = authorityRepository;
    }

    public Set<String> getAllAvailableAuthorities() {
        Set<String> result = new HashSet<>();

        for (IAuthorityProvider provider : providers) {
            List<String> resources = provider.getProtectedResources();
            List<String> baseActions = provider.getBaseActions();

            for (String resource : resources) {
                // base actions
                for (String action : baseActions) {
                    result.add(resource + ":" + action);
                }

                // extra resource-specific actions
                List<String> extraActions = provider.getExtraActionsForResource(resource);
                for (String action : extraActions) {
                    result.add(resource + ":" + action);
                }
            }
        }

        // Add DB authorities (created dynamically)
        List<String> dbAuthorities = authorityRepository.findAll()
                .stream()
                .map(AuthorityEntity::getName)
                .toList();

        result.addAll(dbAuthorities);

        return result;
    }

}
