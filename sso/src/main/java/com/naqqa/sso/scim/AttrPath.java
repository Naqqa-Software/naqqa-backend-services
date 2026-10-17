package com.naqqa.sso.scim;

public record AttrPath(String urn, AttrDef attr, AttrDef sub, boolean relative) {

    public AttrDef leaf() {
        return sub != null ? sub : attr;
    }
}
