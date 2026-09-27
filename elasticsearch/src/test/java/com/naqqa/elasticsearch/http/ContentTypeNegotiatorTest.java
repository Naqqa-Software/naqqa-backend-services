package com.naqqa.elasticsearch.http;

import com.naqqa.elasticsearch.test.Assert;

public final class ContentTypeNegotiatorTest {

    @com.naqqa.elasticsearch.test.Test
    public void parsesVendorMediaTypeWithCompatibleWith() {
        MediaType mediaType = MediaType.parse("application/vnd.elasticsearch+json;compatible-with=8");
        Assert.assertTrue(mediaType.isVendorElasticsearch());
        Assert.assertEquals(Integer.valueOf(8), mediaType.compatibleWithVersion());
        Assert.assertTrue(mediaType.canonical() == XContentType.JSON);
    }

    @com.naqqa.elasticsearch.test.Test
    public void recognizesNdjsonAndOtherMediaTypes() {
        Assert.assertTrue(MediaType.parse("application/x-ndjson").isNdjson());
        Assert.assertTrue(MediaType.parse("application/cbor").canonical() == XContentType.CBOR);
        Assert.assertTrue(MediaType.parse("application/smile").canonical() == XContentType.SMILE);
        Assert.assertTrue(MediaType.parse("application/yaml").canonical() == XContentType.YAML);
    }

    @com.naqqa.elasticsearch.test.Test
    public void negotiatesHighestQualityAcceptableType() {
        MediaType negotiated = ContentTypeNegotiator.negotiateAccept("application/cbor;q=0.5, application/json;q=0.9", XContentType.JSON);
        Assert.assertTrue(negotiated.canonical() == XContentType.JSON);
    }

    @com.naqqa.elasticsearch.test.Test
    public void fallsBackWhenAcceptHeaderMissing() {
        MediaType negotiated = ContentTypeNegotiator.negotiateAccept(null, XContentType.JSON);
        Assert.assertTrue(ContentTypeNegotiator.resolve(negotiated, XContentType.YAML) == XContentType.JSON);
    }
}
