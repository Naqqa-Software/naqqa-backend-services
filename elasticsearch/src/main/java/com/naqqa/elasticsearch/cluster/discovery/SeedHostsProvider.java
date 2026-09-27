package com.naqqa.elasticsearch.cluster.discovery;

import java.util.List;

public interface SeedHostsProvider {

    List<String> getSeedAddresses();
}
