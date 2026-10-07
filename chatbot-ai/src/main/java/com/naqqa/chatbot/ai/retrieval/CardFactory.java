package com.naqqa.chatbot.ai.retrieval;

import com.naqqa.chatbot.entities.ChatCard;
import com.naqqa.chatbot.spi.ChatContentProvider;
import com.naqqa.chatbot.spi.ChatEntityResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CardFactory {

    private final ChatContentProvider provider;
    private final ChatEntityResolver directory;

    public CardFactory(ChatContentProvider provider, ChatEntityResolver directory) {
        this.provider = provider;
        this.directory = directory == null ? ChatEntityResolver.NONE : directory;
    }

    public List<ChatCard> cards(List<RankedItem> items, String lang) {
        List<ChatCard> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RankedItem item : items) {
            ChatCard card = card(item, lang);
            if (card != null && seen.add(card.getType() + "|" + com.naqqa.chatbot.ai.TextNormalizer.fold(card.getTitle()).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                    + "|" + card.getPrice() + "|" + card.getCompanyId())) {
                out.add(card);
            }
        }
        return out;
    }

    public ChatCard card(RankedItem item, String lang) {
        if (item == null) {
            return null;
        }
        Candidate c = item.candidate();
        String title = c.title(lang);
        if (title == null || c.id() == null) {
            return null;
        }
        boolean isCompany = provider != null && provider.companyType().equals(c.type());
        CompanyRef company = null;
        try {
            company = c.companyId() == null ? null : directory.company(c.companyId());
        } catch (RuntimeException ignored) {
        }
        String companyName = isCompany ? null : company == null ? null : company.name();
        String companyLogo = isCompany || company == null ? null : image(company.logoId());
        return ChatCard.builder()
                .type(c.type())
                .id(c.id())
                .slug(c.slug())
                .title(title.length() > 140 ? title.substring(0, 139) + "…" : title)
                .image(image(c.imageId()))
                .companyId(isCompany ? c.id() : c.companyId())
                .price(c.price())
                .originalPrice(c.originalPrice())
                .discount(c.discount())
                .company(companyName)
                .companyLogo(companyLogo)
                .validTo(c.validTo() == null ? null : c.validTo().toString())
                .path(c.path())
                .sponsored(item.sponsored())
                .build();
    }

    private String image(String id) {
        if (id == null || id.isBlank() || provider == null) {
            return null;
        }
        try {
            return provider.imageUrl(id);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
