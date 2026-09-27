package com.naqqa.elasticsearch.cluster.discovery;

import java.util.List;

public final class StaticSeedHostsProvider implements SeedHostsProvider {

    private final List<String> addresses;

    public StaticSeedHostsProvider(List<String> addresses) {
        this.addresses = List.copyOf(addresses);
    }

    @Override
    public List<String> getSeedAddresses() {
        return addresses;
    }
}
