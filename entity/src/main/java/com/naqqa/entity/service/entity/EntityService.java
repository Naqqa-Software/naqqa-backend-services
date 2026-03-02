package com.naqqa.entity.service.auth;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.UserRepository;
import com.naqqa.deedakt.entities.OrganizerEntity;
import com.naqqa.deedakt.repository.OrganizerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizerAuthService {

    private final UserRepository userRepository;
    private final OrganizerRepository organizerRepository;

    public OrganizerEntity registerOrganizer(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        OrganizerEntity organizer = new OrganizerEntity();
        organizer.setUser(user);
        return organizerRepository.save(organizer);
    }
}
