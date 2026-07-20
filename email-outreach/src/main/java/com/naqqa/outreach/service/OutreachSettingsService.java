package com.naqqa.outreach.service;

import com.naqqa.outreach.entity.OutreachSettingsEntity;
import com.naqqa.outreach.repository.OutreachSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Reads/writes the singleton {@code outreach_settings} document (the sending on/off switch). */
@Service
@RequiredArgsConstructor
public class OutreachSettingsService {

    private final OutreachSettingsRepository repo;

    public OutreachSettingsEntity get() {
        return repo.findById(OutreachSettingsEntity.SINGLETON).orElseGet(OutreachSettingsEntity::new);
    }

    public boolean isSendingActive() {
        return get().isSendingActive();
    }

    public boolean isExtractionActive() {
        return get().isExtractionActive();
    }

    public OutreachSettingsEntity setSendingActive(boolean active) {
        OutreachSettingsEntity s = get();
        s.setSendingActive(active);
        s.setUpdatedAt(Instant.now());
        return repo.save(s);
    }

    public OutreachSettingsEntity setExtractionActive(boolean active) {
        OutreachSettingsEntity s = get();
        s.setExtractionActive(active);
        s.setUpdatedAt(Instant.now());
        return repo.save(s);
    }

    /** True while Apollo enrichment is auto-paused (e.g. after running out of credits). */
    public boolean isApolloPaused() {
        Instant until = get().getApolloPausedUntil();
        return until != null && until.isAfter(Instant.now());
    }

    /** Suspend Apollo enrichment for {@code hours} (e.g. 24h when credits are exhausted). */
    public OutreachSettingsEntity pauseApolloFor(long hours) {
        OutreachSettingsEntity s = get();
        s.setApolloPausedUntil(Instant.now().plusSeconds(hours * 3600));
        s.setUpdatedAt(Instant.now());
        return repo.save(s);
    }
}
