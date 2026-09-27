package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

public final class FieldAliasMapper extends FieldMapper {

    public static final String TYPE = "alias";

    private final String path;

    private FieldAliasMapper(String simpleName, String fullPath, JsonObject node, String path) {
        super(simpleName, fullPath, node);
        this.path = path;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        String path = node.getString("path");
        if (path == null) {
            throw new IllegalArgumentException("field alias [" + fullPath + "] must specify a [path]");
        }
        return new FieldAliasMapper(name, fullPath, node, path);
    }

    public String path() {
        return path;
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
    }
}
