package com.naqqa.outreach.service;

import com.naqqa.outreach.entity.OutreachAccountStateEntity;
import com.naqqa.outreach.entity.OutreachProfileEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/** Warm-up daily cap + bounce-multiplier, shared by the initial-send engine and the follow-up dispatcher. */
@Service
@RequiredArgsConstructor
public class DailyLimitService {

    private final BounceTracker bounce;

    public void resetIfNewDay(OutreachAccountStateEntity state) {
        String today = OutreachTime.todayKey();
        if (!today.equals(state.getCounterDate())) {
            state.setCounterDate(today);
            state.setSentToday(0);
            state.setFollowupsSentToday(0);
            bounce.save(state);
        }
    }

    public int dailyLimit(OutreachProfileEntity p) {
        if (p.getWarmupSchedule() != null && !p.getWarmupSchedule().isEmpty() && p.getWarmupStartDate() != null) {
            int dayIndex = OutreachTime.workingDaysBetween(p.getWarmupStartDate(), LocalDate.now()) - 1;
            if (dayIndex < 0) {
                return 0;
            }
            List<Integer> sch = p.getWarmupSchedule();
            return dayIndex < sch.size() ? sch.get(dayIndex) : sch.get(sch.size() - 1);
        }
        return p.getDailyLimit() == null ? 0 : p.getDailyLimit();
    }

    /** Effective cap after warm-up + bounce rules; 0 means "don't send" (limit 0, stop, or pause). */
    public int effectiveCap(OutreachProfileEntity p, OutreachAccountStateEntity state) {
        int base = dailyLimit(p);
        if (base <= 0) {
            return 0;
        }
        BounceTracker.BounceRules r = bounce.checkBounceRules(state);
        if ("stop".equals(r.action()) || "pause".equals(r.action())) {
            return 0;
        }
        return Math.max(1, (int) Math.floor(base * r.limitMultiplier()));
    }
}
