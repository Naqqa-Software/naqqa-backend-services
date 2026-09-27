package com.naqqa.elasticsearch.ingest;

import java.util.List;
import java.util.Map;

public interface EnrichLookupService {

    List<Map<String, Object>> lookup(String policyName, String matchField, Object matchValue, int maxMatches);

    List<Map<String, Object>> geoMatchLookup(String policyName, String geoField, double lat, double lon, int maxMatches);
}
