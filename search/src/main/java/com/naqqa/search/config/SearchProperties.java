package com.naqqa.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "naqqa.search")
public class SearchProperties {

    private boolean enabled = true;
    private String path = "/api/search";
    private boolean registerGlobalDataset = true;

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

    public boolean isRegisterGlobalDataset() {
        return registerGlobalDataset;
    }

    public void setRegisterGlobalDataset(boolean registerGlobalDataset) {
        this.registerGlobalDataset = registerGlobalDataset;
    }
}
