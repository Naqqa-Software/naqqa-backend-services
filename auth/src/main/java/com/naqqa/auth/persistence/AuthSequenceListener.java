package com.naqqa.auth.persistence;

import com.naqqa.auth.entity.auth.RefreshTokenEntity;
import com.naqqa.auth.entity.auth.UserDeviceEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.AuthorityEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.entity.authorities.SubRoleEntity;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;

import java.time.Instant;

/**
 * Assigns auto-increment Long ids (and createdAt timestamps) to the auth documents before they
 * are persisted, preserving the JPA identity-generation and {@code @CreationTimestamp} semantics.
 */
public class AuthSequenceListener extends AbstractMongoEventListener<Object> {

    private final SequenceGenerator sequenceGenerator;

    public AuthSequenceListener(SequenceGenerator sequenceGenerator) {
        this.sequenceGenerator = sequenceGenerator;
    }

    @Override
    public void onBeforeConvert(BeforeConvertEvent<Object> event) {
        Object source = event.getSource();
        if (source instanceof UserEntity user) {
            if (user.getId() == null) {
                user.setId(sequenceGenerator.generateSequence("users_seq"));
            }
        } else if (source instanceof RoleEntity role) {
            if (role.getId() == null) {
                role.setId(sequenceGenerator.generateSequence("roles_seq"));
            }
        } else if (source instanceof SubRoleEntity subRole) {
            if (subRole.getId() == null) {
                subRole.setId(sequenceGenerator.generateSequence("sub_roles_seq"));
            }
        } else if (source instanceof AuthorityEntity authority) {
            if (authority.getId() == null) {
                authority.setId(sequenceGenerator.generateSequence("authorities_seq"));
            }
        } else if (source instanceof RefreshTokenEntity token) {
            if (token.getId() == null) {
                token.setId(sequenceGenerator.generateSequence("refresh_tokens_seq"));
            }
        } else if (source instanceof UserDeviceEntity device) {
            if (device.getId() == null) {
                device.setId(sequenceGenerator.generateSequence("user_devices_seq"));
            }
            if (device.getCreatedAt() == null) {
                device.setCreatedAt(Instant.now());
            }
        }
    }
}
