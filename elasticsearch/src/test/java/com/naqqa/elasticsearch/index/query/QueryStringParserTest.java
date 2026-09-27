package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class QueryStringParserTest {

    @Test
    public void fieldColonValue() {
        QueryBuilder q = QueryStringParser.parse("status:active", List.of("_all"), Operator.OR, true);
        Assert.assertTrue(q instanceof TermQueryBuilder);
        TermQueryBuilder t = (TermQueryBuilder) q;
        Assert.assertEquals("status", t.fieldName());
        Assert.assertEquals("active", t.value());
    }

    @Test
    public void andOrPrecedenceSequential() {
        QueryBuilder q = QueryStringParser.parse("a AND b OR c", List.of("f"), Operator.OR, true);
        Assert.assertTrue(q instanceof BoolQueryBuilder);
        BoolQueryBuilder b = (BoolQueryBuilder) q;
        Assert.assertEquals(1, b.must().size());
        Assert.assertEquals(2, b.should().size());
        Assert.assertTrue(b.mustNot().isEmpty());
    }

    @Test
    public void plusMinusPrefixes() {
        QueryBuilder q = QueryStringParser.parse("+a -b c", List.of("f"), Operator.OR, true);
        BoolQueryBuilder b = (BoolQueryBuilder) q;
        Assert.assertEquals(1, b.must().size());
        Assert.assertEquals(1, b.mustNot().size());
        Assert.assertEquals(1, b.should().size());
    }

    @Test
    public void notKeywordAndBang() {
        QueryBuilder q1 = QueryStringParser.parse("NOT a", List.of("f"), Operator.OR, true);
        Assert.assertTrue(q1 instanceof BoolQueryBuilder);
        BoolQueryBuilder b1 = (BoolQueryBuilder) q1;
        Assert.assertEquals(1, b1.mustNot().size());
        Assert.assertTrue(b1.must().isEmpty());
        Assert.assertTrue(b1.should().isEmpty());

        QueryBuilder q2 = QueryStringParser.parse("a !b", List.of("f"), Operator.OR, true);
        BoolQueryBuilder b2 = (BoolQueryBuilder) q2;
        Assert.assertEquals(1, b2.mustNot().size());
        Assert.assertEquals(1, b2.should().size());
    }

    @Test
    public void groupingWithParens() {
        QueryBuilder q = QueryStringParser.parse("field:(a OR b)", List.of("f"), Operator.OR, true);
        Assert.assertTrue(q instanceof BoolQueryBuilder);
        BoolQueryBuilder b = (BoolQueryBuilder) q;
        Assert.assertEquals(2, b.should().size());
        Assert.assertTrue(b.should().get(0) instanceof TermQueryBuilder);
        Assert.assertEquals("field", ((TermQueryBuilder) b.should().get(0)).fieldName());
    }

    @Test
    public void wildcardQuery() {
        QueryBuilder q = QueryStringParser.parse("na*e", List.of("f"), Operator.OR, true);
        Assert.assertTrue(q instanceof WildcardQueryBuilder);
        Assert.assertEquals("na*e", ((WildcardQueryBuilder) q).value());
    }

    @Test
    public void fuzzyQuery() {
        QueryBuilder q = QueryStringParser.parse("roam~2", List.of("f"), Operator.OR, true);
        Assert.assertTrue(q instanceof FuzzyQueryBuilder);
        FuzzyQueryBuilder fq = (FuzzyQueryBuilder) q;
        Assert.assertEquals("roam", fq.value());
        Assert.assertEquals(2, fq.fuzziness().asDistance(""));
    }

    @Test
    public void boostSuffix() {
        QueryBuilder q = QueryStringParser.parse("quick^2.5", List.of("f"), Operator.OR, true);
        Assert.assertTrue(q instanceof TermQueryBuilder);
        Assert.assertEquals(2.5f, ((TermQueryBuilder) q).boost());
    }

    @Test
    public void rangeInclusiveExclusive() {
        QueryBuilder q1 = QueryStringParser.parse("age:[10 TO 20]", List.of("f"), Operator.OR, true);
        RangeQueryBuilder r1 = (RangeQueryBuilder) q1;
        Assert.assertEquals("10", r1.from());
        Assert.assertEquals("20", r1.to());
        Assert.assertTrue(r1.includeLower());
        Assert.assertTrue(r1.includeUpper());

        QueryBuilder q2 = QueryStringParser.parse("age:{10 TO 20}", List.of("f"), Operator.OR, true);
        RangeQueryBuilder r2 = (RangeQueryBuilder) q2;
        Assert.assertFalse(r2.includeLower());
        Assert.assertFalse(r2.includeUpper());
    }

    @Test
    public void quotedPhrase() {
        QueryBuilder q = QueryStringParser.parse("\"quick fox\"", List.of("f"), Operator.OR, true);
        Assert.assertTrue(q instanceof MatchPhraseQueryBuilder);
        Assert.assertEquals("quick fox", ((MatchPhraseQueryBuilder) q).query());
    }

    @Test
    public void multiFieldTermBecomesMultiMatch() {
        QueryBuilder q = QueryStringParser.parse("hello", List.of("title", "body"), Operator.OR, true);
        Assert.assertTrue(q instanceof MultiMatchQueryBuilder);
    }
}
