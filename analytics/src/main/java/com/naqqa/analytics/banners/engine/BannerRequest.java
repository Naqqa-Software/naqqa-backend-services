package com.naqqa.analytics.banners.engine;

import java.time.Instant;

public record BannerRequest(String slot, String lang, String pageType, String categoryId, String companyId, String query,
                            String device, String city, Boolean newVisitor, boolean loggedIn, String vid, String sid,
                            Instant now) {

    public static Builder builder() {
        return new Builder();
    }

    public boolean companyPage() {
        return pageType != null && "company".equalsIgnoreCase(pageType) && companyId != null && !companyId.isBlank();
    }

    public static final class Builder {
        private String slot;
        private String lang;
        private String pageType;
        private String categoryId;
        private String companyId;
        private String query;
        private String device;
        private String city;
        private Boolean newVisitor;
        private boolean loggedIn;
        private String vid;
        private String sid;
        private Instant now = Instant.now();

        public Builder slot(String v) { this.slot = v; return this; }
        public Builder lang(String v) { this.lang = v; return this; }
        public Builder pageType(String v) { this.pageType = v; return this; }
        public Builder categoryId(String v) { this.categoryId = v; return this; }
        public Builder companyId(String v) { this.companyId = v; return this; }
        public Builder query(String v) { this.query = v; return this; }
        public Builder device(String v) { this.device = v; return this; }
        public Builder city(String v) { this.city = v; return this; }
        public Builder newVisitor(Boolean v) { this.newVisitor = v; return this; }
        public Builder loggedIn(boolean v) { this.loggedIn = v; return this; }
        public Builder vid(String v) { this.vid = v; return this; }
        public Builder sid(String v) { this.sid = v; return this; }
        public Builder now(Instant v) { this.now = v; return this; }

        public BannerRequest build() {
            return new BannerRequest(slot, lang, pageType, categoryId, companyId, query, device, city, newVisitor, loggedIn,
                    vid, sid, now);
        }
    }
}
