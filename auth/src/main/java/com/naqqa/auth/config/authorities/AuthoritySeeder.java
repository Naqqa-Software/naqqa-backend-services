package com.naqqa.auth.config.authorities;

import com.naqqa.auth.repository.AuthorityRepository;

public interface AuthoritySeeder {
    void seedAuthorities(AuthorityRepository authorityRepository);
}
