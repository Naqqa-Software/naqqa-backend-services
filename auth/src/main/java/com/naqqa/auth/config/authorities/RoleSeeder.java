package com.naqqa.auth.config.authorities;

import com.naqqa.auth.repository.AuthorityRepository;
import com.naqqa.auth.repository.RoleRepository;

public interface RoleSeeder {

    void seedRoles(RoleRepository roleRepository,
                   AuthorityRepository authorityRepository);
}

