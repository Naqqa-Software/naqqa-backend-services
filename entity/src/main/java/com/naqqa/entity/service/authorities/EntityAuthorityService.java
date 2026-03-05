package com.naqqa.entity.service.authorities;

import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.repository.AuthorityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EntityAuthorityService {

    private static final List<String> ACTIONS = List.of("read", "create", "update", "delete");

    private final AuthorityRepository authorityRepository;

    public void ensureAuthoritiesForEntityKey(String entityKey) {
        if (entityKey == null || entityKey.isBlank()) {
            return;
        }
        Set<String> existing = authorityRepository.findAll().stream()
                .map(AuthorityEntity::getName)
                .collect(Collectors.toSet());

        List<AuthorityEntity> toSave = new ArrayList<>();
        for (String action : ACTIONS) {
            String name = entityKey + ":" + action;
            if (existing.contains(name)) {
                continue;
            }
            AuthorityEntity entity = new AuthorityEntity();
            entity.setName(name);
            toSave.add(entity);
        }

        if (!toSave.isEmpty()) {
            authorityRepository.saveAll(toSave);
        }
    }

    public void removeAuthoritiesForEntityKey(String entityKey) {
        if (entityKey == null || entityKey.isBlank()) {
            return;
        }
        Set<String> names = ACTIONS.stream()
                .map(action -> entityKey + ":" + action)
                .collect(Collectors.toSet());

        List<AuthorityEntity> toDelete = authorityRepository.findAll().stream()
                .filter(entity -> names.contains(entity.getName()))
                .toList();

        if (!toDelete.isEmpty()) {
            authorityRepository.deleteAll(toDelete);
        }
    }
}

