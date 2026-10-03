package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.ChatItemType;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.RetrievalPlan;
import com.naqqa.chatbot.spi.ChatContentProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class FakeContentProvider implements ChatContentProvider {

    final List<RetrievalPlan> plans = new ArrayList<>();
    final Set<String> disabled = new HashSet<>(Set.of("RAFFLE"));
    List<Candidate> results = new ArrayList<>();
    RuntimeException failure;

    @Override
    public List<ChatItemType> itemTypes() {
        return ChatTestSupport.TYPES;
    }

    @Override
    public List<Candidate> retrieve(RetrievalPlan plan) {
        plans.add(plan);
        if (failure != null) {
            throw failure;
        }
        return results;
    }

    @Override
    public boolean isAvailable(String type) {
        return !disabled.contains(type);
    }

    @Override
    public Candidate companyCandidate(CompanyRef company) {
        return new Candidate("COMPANY", company.id(), company.slug(), Candidate.titles(company.name(), company.name()),
                company.logoId(), null, null, null, company.id(), null, null, null, null, null, null, null,
                "/company/" + (company.slug() == null ? company.id() : company.slug()));
    }

    @Override
    public String imageUrl(String imageId) {
        return "http://img/" + imageId;
    }

    RetrievalPlan last() {
        return plans.isEmpty() ? null : plans.get(plans.size() - 1);
    }
}
