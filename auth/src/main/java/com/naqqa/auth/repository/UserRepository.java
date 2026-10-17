package com.naqqa.auth.repository;

import com.naqqa.auth.entity.auth.UserEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public interface UserRepository extends MongoRepository<UserEntity, Long>, UserFieldOperations {
    Optional<UserEntity> findByEmail(String email);
    Optional<UserEntity> findByGoogleId(String googleId);
    Optional<UserEntity> findByFacebookId(String facebookId);
    Optional<UserEntity> findByAppleId(String appleId);
    boolean existsByEmail(String email);
    List<UserEntity> findAllByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);

    @Query(value = "{ 'email' : { '$eq' : ?0, '$type' : 'string' } }", sort = "{ '_id' : 1 }", collation = "{ 'locale' : 'en', 'strength' : 2 }")
    List<UserEntity> findAllWithEmailAnyCase(String email);

    @Query(value = "{ 'email' : { '$eq' : ?0, '$type' : 'string' } }", exists = true, collation = "{ 'locale' : 'en', 'strength' : 2 }")
    boolean existsWithEmailAnyCase(String email);

    Optional<UserEntity> findByPhoneNumber(String phoneNumber);

    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    default Optional<UserEntity> findByEmailAnyCase(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        List<UserEntity> any = findAllWithEmailAnyCase(email.trim());
        return any.isEmpty() ? Optional.empty() : Optional.of(any.get(0));
    }

    default boolean emailTaken(String email) {
        return email != null && !email.isBlank() && existsWithEmailAnyCase(email.trim());
    }
}
