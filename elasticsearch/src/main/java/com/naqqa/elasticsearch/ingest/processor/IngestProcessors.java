package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

public final class IngestProcessors {

    private IngestProcessors() {
    }

    public static void registerAll(ProcessorRegistry registry) {
        registry.register(SetProcessor.TYPE, new SetProcessor.Factory());
        registry.register(RemoveProcessor.TYPE, new RemoveProcessor.Factory());
        registry.register(RenameProcessor.TYPE, new RenameProcessor.Factory());
        registry.register(AppendProcessor.TYPE, new AppendProcessor.Factory());
        registry.register(ConvertProcessor.TYPE, new ConvertProcessor.Factory());
        registry.register(DateProcessor.TYPE, new DateProcessor.Factory());
        registry.register(DateIndexNameProcessor.TYPE, new DateIndexNameProcessor.Factory());
        registry.register(GrokProcessor.TYPE, new GrokProcessor.Factory());
        registry.register(DissectProcessor.TYPE, new DissectProcessor.Factory());
        registry.register(SplitProcessor.TYPE, new SplitProcessor.Factory());
        registry.register(JoinProcessor.TYPE, new JoinProcessor.Factory());
        registry.register("lowercase", StringTransformProcessor.factory("lowercase", String::toLowerCase));
        registry.register("uppercase", StringTransformProcessor.factory("uppercase", String::toUpperCase));
        registry.register("trim", StringTransformProcessor.factory("trim", String::trim));
        registry.register(GsubProcessor.TYPE, new GsubProcessor.Factory());
        registry.register(JsonProcessor.TYPE, new JsonProcessor.Factory());
        registry.register(KvProcessor.TYPE, new KvProcessor.Factory());
        registry.register(CsvProcessor.TYPE, new CsvProcessor.Factory());
        registry.register(ScriptProcessor.TYPE, new ScriptProcessor.Factory());
        registry.register(PipelineProcessor.TYPE, new PipelineProcessor.Factory());
        registry.register(DropProcessor.TYPE, new DropProcessor.Factory());
        registry.register(FailProcessor.TYPE, new FailProcessor.Factory());
        registry.register(ForeachProcessor.TYPE, new ForeachProcessor.Factory());
        registry.register(SortProcessor.TYPE, new SortProcessor.Factory());
        registry.register(DotExpanderProcessor.TYPE, new DotExpanderProcessor.Factory());
        registry.register(HtmlStripProcessor.TYPE, HtmlStripProcessor.factory());
        registry.register(UrlDecodeProcessor.TYPE, UrlDecodeProcessor.factory());
        registry.register(UriPartsProcessor.TYPE, new UriPartsProcessor.Factory());
        registry.register(UserAgentProcessor.TYPE, new UserAgentProcessor.Factory());
        registry.register(FingerprintProcessor.TYPE, new FingerprintProcessor.Factory());
        registry.register(CommunityIdProcessor.TYPE, new CommunityIdProcessor.Factory());
        registry.register(NetworkDirectionProcessor.TYPE, new NetworkDirectionProcessor.Factory());
        registry.register(RegisteredDomainProcessor.TYPE, new RegisteredDomainProcessor.Factory());
        registry.register(GeoIpProcessor.TYPE, new GeoIpProcessor.Factory());
        registry.register(EnrichProcessor.TYPE, new EnrichProcessor.Factory());
    }
}
