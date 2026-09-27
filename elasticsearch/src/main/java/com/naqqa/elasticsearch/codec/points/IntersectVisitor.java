package com.naqqa.elasticsearch.codec.points;

import java.io.IOException;

public interface IntersectVisitor {

    Relation compare(byte[][] cellMin, byte[][] cellMax);

    void visit(int docId) throws IOException;

    void visit(int docId, byte[] packedValue) throws IOException;
}
