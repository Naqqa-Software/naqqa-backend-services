package com.naqqa.registry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "naqqa.registry")
public class RegistryProperties {

    private boolean enabled = true;
    private String path = "/api/registry";
    private String storageDir = "registry-storage";
    private String publicUrl;
    private String tokenPepper;
    private Duration defaultTokenTtl = Duration.ofDays(365);
    private Duration maxTokenTtl = Duration.ofDays(730);
    private Map<String, List<String>> packageSets = new LinkedHashMap<>();
    private boolean includeDependencies = true;
    private Duration catalogRefresh = Duration.ofSeconds(5);
    private String readAuthority = "registry.tokens:read";
    private String manageAuthority = "registry.tokens:manage";
    private boolean allowEncodedSlash = true;
    private String collectionPrefix = "naqqa_registry_";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }

    public String getTokenPepper() {
        return tokenPepper;
    }

    public void setTokenPepper(String tokenPepper) {
        this.tokenPepper = tokenPepper;
    }

    public Duration getDefaultTokenTtl() {
        return defaultTokenTtl;
    }

    public void setDefaultTokenTtl(Duration defaultTokenTtl) {
        this.defaultTokenTtl = defaultTokenTtl;
    }

    public Duration getMaxTokenTtl() {
        return maxTokenTtl;
    }

    public void setMaxTokenTtl(Duration maxTokenTtl) {
        this.maxTokenTtl = maxTokenTtl;
    }

    public Map<String, List<String>> getPackageSets() {
        return packageSets;
    }

    public void setPackageSets(Map<String, List<String>> packageSets) {
        this.packageSets = packageSets;
    }

    public boolean isIncludeDependencies() {
        return includeDependencies;
    }

    public void setIncludeDependencies(boolean includeDependencies) {
        this.includeDependencies = includeDependencies;
    }

    public Duration getCatalogRefresh() {
        return catalogRefresh;
    }

    public void setCatalogRefresh(Duration catalogRefresh) {
        this.catalogRefresh = catalogRefresh;
    }

    public String getReadAuthority() {
        return readAuthority;
    }

    public void setReadAuthority(String readAuthority) {
        this.readAuthority = readAuthority;
    }

    public String getManageAuthority() {
        return manageAuthority;
    }

    public void setManageAuthority(String manageAuthority) {
        this.manageAuthority = manageAuthority;
    }

    public boolean isAllowEncodedSlash() {
        return allowEncodedSlash;
    }

    public void setAllowEncodedSlash(boolean allowEncodedSlash) {
        this.allowEncodedSlash = allowEncodedSlash;
    }

    public String getCollectionPrefix() {
        return collectionPrefix;
    }

    public void setCollectionPrefix(String collectionPrefix) {
        this.collectionPrefix = collectionPrefix;
    }
}
