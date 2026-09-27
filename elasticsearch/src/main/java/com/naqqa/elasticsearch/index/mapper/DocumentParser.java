package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.exception.MapperParsingException;
import com.naqqa.elasticsearch.common.exception.StrictDynamicMappingException;
import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;

import java.util.Map;

final class DocumentParser {

    private DocumentParser() {
    }

    static ParsedDocument parse(DocumentMapper documentMapper, String id, String routing, JsonObject source) {
        Mapping mapping = documentMapper.mapping();
        RootObjectMapper root = mapping.root();
        MappingParserContext parserCtx = documentMapper.parserContext();
        ParseContext context = new ParseContext();
        context.setId(id);
        context.setRouting(routing);

        parseObject(root, "", source, context, parserCtx, root, 1, root.dynamic());

        RoutingFieldMapper routingMapper = (RoutingFieldMapper) mapping.metadataMappers().get(RoutingFieldMapper.NAME);
        routingMapper.createField(context, routing);

        IdFieldMapper idMapper = (IdFieldMapper) mapping.metadataMappers().get(IdFieldMapper.NAME);
        idMapper.createField(context, id);

        IndexFieldMapper indexMapper = (IndexFieldMapper) mapping.metadataMappers().get(IndexFieldMapper.NAME);
        indexMapper.createField(context, documentMapper.indexName());

        TierFieldMapper tierMapper = (TierFieldMapper) mapping.metadataMappers().get(TierFieldMapper.NAME);
        tierMapper.createField(context, documentMapper.tier());

        SourceFieldMapper sourceMapper = (SourceFieldMapper) mapping.metadataMappers().get(SourceFieldMapper.NAME);
        JsonObject filteredSource = sourceMapper.filter(source);
        if (filteredSource != null) {
            sourceMapper.createField(context, filteredSource);
        }

        FieldNamesFieldMapper fieldNamesMapper = (FieldNamesFieldMapper) mapping.metadataMappers().get(FieldNamesFieldMapper.NAME);
        fieldNamesMapper.createField(context, context.fieldNames());

        IgnoredFieldMapper ignoredFieldMapper = (IgnoredFieldMapper) mapping.metadataMappers().get(IgnoredFieldMapper.NAME);
        ignoredFieldMapper.createField(context, context.ignoredFields());

        int nestedLimit = documentMapper.indexSettings().getAsInt("index.mapping.nested_objects.limit", 10000);
        if (context.nestedDocCount() > nestedLimit) {
            throw new IllegalArgumentException("The number of nested documents has exceeded the allowed limit of [" + nestedLimit + "]");
        }

        processCopyTo(mapping, context, parserCtx, root);

        return new ParsedDocument(id, routing, context.currentDocument(), context.nestedDocuments(),
            filteredSource != null ? filteredSource : source, context.dynamicMappingUpdate());
    }

    private static void processCopyTo(Mapping mapping, ParseContext context, MappingParserContext parserCtx, RootObjectMapper root) {
        for (ParseContext.CopyToEntry entry : context.copyToEntries()) {
            FieldMapper target = mapping.fieldMapper(entry.targetPath());
            if (target == null) {
                DynamicFieldsBuilder.Detection det = DynamicFieldsBuilder.detect(entry.value(), root);
                JsonObject node = DynamicFieldsBuilder.buildDefaultMapping(det.dynamicType(), det.dateFormat());
                target = parserCtx.parseField(entry.targetPath(), entry.targetPath(), node, 1);
                root.putMapper(entry.targetPath(), target);
                context.addDynamicMapper(target);
            }
            target.parse(context, entry.value());
        }
    }

    private static void parseObject(ObjectMapper mapper, String path, JsonObject node, ParseContext context,
                                     MappingParserContext parserCtx, RootObjectMapper root, int depth, Dynamic inheritedDynamic) {
        Dynamic effectiveDynamic = mapper.dynamic() != null ? mapper.dynamic() : inheritedDynamic;
        for (Map.Entry<String, JsonValue> e : node) {
            String fieldName = e.getKey();
            JsonValue jv = e.getValue();
            if (jv.isNull()) {
                continue;
            }
            String childFullPath = path.isEmpty() ? fieldName : path + "." + fieldName;
            Mapper childMapper = mapper.getMapper(fieldName);
            if (childMapper == null) {
                childMapper = createDynamicMapper(mapper, root, childFullPath, fieldName, jv, parserCtx, context, depth, effectiveDynamic);
                if (childMapper == null) {
                    continue;
                }
            }
            parseValue(childMapper, childFullPath, jv, context, parserCtx, root, depth, effectiveDynamic);
        }
    }

    private static void parseValue(Mapper childMapper, String childFullPath, JsonValue jv, ParseContext context,
                                    MappingParserContext parserCtx, RootObjectMapper root, int depth, Dynamic inheritedDynamic) {
        if (jv.isArray() && !(childMapper instanceof FieldMapper singleValueField && singleValueField.parsesArrayAsSingleValue())) {
            for (JsonValue item : jv.asArray()) {
                if (item.isNull()) {
                    continue;
                }
                parseValue(childMapper, childFullPath, item, context, parserCtx, root, depth, inheritedDynamic);
            }
            return;
        }
        if (childMapper instanceof NestedObjectMapper nested) {
            if (!jv.isObject()) {
                throw new MapperParsingException("nested field [{}] must be an object", childFullPath);
            }
            context.startNestedDocument();
            parseObject(nested, childFullPath, jv.asObject(), context, parserCtx, root, depth + 1, inheritedDynamic);
            context.currentDocument().add(IndexableField.indexedText(NestedObjectMapper.NESTED_PATH_FIELD,
                java.util.List.of(new IndexedTerm(childFullPath, 0, 0, childFullPath.length())), false));
            context.endNestedDocument();
            return;
        }
        if (childMapper instanceof ObjectMapper obj) {
            if (!jv.isObject()) {
                if (!obj.isEnabled()) {
                    return;
                }
                throw new MapperParsingException("object mapping for [{}] tried to parse field [{}] as object, but found a concrete value", obj.fullPath(), childFullPath);
            }
            if (!obj.isEnabled()) {
                return;
            }
            parseObject(obj, childFullPath, jv.asObject(), context, parserCtx, root, depth + 1, inheritedDynamic);
            return;
        }
        FieldMapper fm = (FieldMapper) childMapper;
        fm.parse(context, jv.toJava());
    }

    private static Mapper createDynamicMapper(ObjectMapper parent, RootObjectMapper root, String fullPath, String fieldName,
                                                JsonValue jv, MappingParserContext parserCtx, ParseContext context, int depth, Dynamic effectiveDynamic) {
        if (effectiveDynamic == Dynamic.FALSE) {
            context.addIgnoredField(fullPath);
            return null;
        }
        if (effectiveDynamic == Dynamic.STRICT) {
            throw new StrictDynamicMappingException(parent.fullPath(), fieldName);
        }
        if (effectiveDynamic == Dynamic.RUNTIME) {
            if (jv.isObject() || jv.isArray()) {
                return createConcreteDynamicMapper(parent, root, fullPath, fieldName, jv, parserCtx, context, depth);
            }
            DynamicFieldsBuilder.Detection det = DynamicFieldsBuilder.detect(jv.toJava(), root);
            String runtimeType = switch (det.dynamicType()) {
                case "string" -> "keyword";
                case "long" -> "long";
                case "double" -> "double";
                case "boolean" -> "boolean";
                case "date" -> "date";
                default -> "keyword";
            };
            JsonObject rtNode = new JsonObject();
            rtNode.put("type", runtimeType);
            RuntimeFieldMapper rfm = RuntimeFieldMapper.parse(fullPath, rtNode);
            root.runtimeFields().put(fullPath, rfm);
            context.addDynamicMapper(rfm);
            return null;
        }
        return createConcreteDynamicMapper(parent, root, fullPath, fieldName, jv, parserCtx, context, depth);
    }

    private static Mapper createConcreteDynamicMapper(ObjectMapper parent, RootObjectMapper root, String fullPath, String fieldName,
                                                        JsonValue jv, MappingParserContext parserCtx, ParseContext context, int depth) {
        if (jv.isObject()) {
            JsonObject objNode = new JsonObject();
            Mapper created = parserCtx.parseMapper(fieldName, fullPath, objNode, depth + 1);
            parent.putMapper(fieldName, created);
            context.addDynamicMapper(created);
            return created;
        }
        JsonValue sample = jv;
        if (jv.isArray()) {
            sample = null;
            for (JsonValue item : jv.asArray()) {
                if (!item.isNull()) {
                    sample = item;
                    break;
                }
            }
            if (sample == null) {
                return null;
            }
            if (sample.isObject()) {
                JsonObject objNode = new JsonObject();
                Mapper created = parserCtx.parseMapper(fieldName, fullPath, objNode, depth + 1);
                parent.putMapper(fieldName, created);
                context.addDynamicMapper(created);
                return created;
            }
        }
        DynamicFieldsBuilder.Detection det = DynamicFieldsBuilder.detect(sample.toJava(), root);
        DynamicTemplate template = findTemplate(root, fullPath, fieldName, det.dynamicType());
        JsonObject fieldNode = template != null ? template.buildMapping(fieldName, det.dynamicType()) : DynamicFieldsBuilder.buildDefaultMapping(det.dynamicType(), det.dateFormat());
        FieldMapper created = parserCtx.parseField(fieldName, fullPath, fieldNode, depth);
        parent.putMapper(fieldName, created);
        context.addDynamicMapper(created);
        return created;
    }

    private static DynamicTemplate findTemplate(RootObjectMapper root, String fullPath, String fieldName, String dynamicType) {
        for (DynamicTemplate t : root.dynamicTemplates()) {
            if (t.matches(fullPath, fieldName, dynamicType)) {
                return t;
            }
        }
        return null;
    }
}
