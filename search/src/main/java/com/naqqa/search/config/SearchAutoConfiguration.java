package com.naqqa.search.config;

import com.naqqa.elasticsearch.os.GlobalDocs;
import com.naqqa.elasticsearch.os.SearchDataset;
import com.naqqa.elasticsearch.os.SearchDatasetRegistry;
import com.naqqa.search.service.SearchService;
import com.naqqa.search.web.SearchController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(SearchProperties.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "naqqa.search", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SearchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SearchDatasetRegistry naqqaSearchRegistry(SearchProperties properties,
                                                     ObjectProvider<SearchDataset> datasets) {
        SearchDatasetRegistry registry = new SearchDatasetRegistry();
        if (properties.isRegisterGlobalDataset()) {
            registry.register(GlobalDocs.dataset());
        }
        datasets.orderedStream().forEach(registry::register);
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public SearchService naqqaSearchService(SearchDatasetRegistry registry) {
        return new SearchService(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SearchController naqqaSearchController(SearchService service) {
        return new SearchController(service);
    }
}
