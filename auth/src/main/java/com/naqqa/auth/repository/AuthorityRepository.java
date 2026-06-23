package com.naqqa.auth.repository;

import com.naqqa.auth.entity.authorities.AuthorityEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Set;

public interface AuthorityRepository  extends MongoRepository<AuthorityEntity, Long> {
    List<AuthorityEntity> findAllByNameIn(Set<String> authorityNames);
}
