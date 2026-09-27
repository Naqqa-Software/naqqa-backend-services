package com.naqqa.elasticsearch.search.bridge.join;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class JoinQueriesTest {

    private static final int MAX_DOC = 6;
    private static final String[] COMMENT = {"great product", "bad product", null, "great service", "great value", null};
    private static final String[] PTYPE = {null, null, "alpha", null, null, "beta"};
    private static final String[] IDS = {"c0", "c1", "p0", "c3", "c4", "p1"};

    private static final boolean[] IS_CHILD = {true, true, false, true, true, false};
    private static final int[] PARENT_OF = {2, 2, -1, 5, 5, -1};

    private static final ParentChildDocMapping MAPPING = new ParentChildDocMapping() {
        @Override
        public boolean isChild(int doc) {
            return IS_CHILD[doc];
        }

        @Override
        public int parentOf(int childDoc) {
            return PARENT_OF[childDoc];
        }
    };

    private static IndexSearcher buildSearcher() throws Exception {
        TestSegments.TextField comment = TestSegments.buildTextField(MAX_DOC, COMMENT);
        TestSegments.TextField ptype = TestSegments.buildTextField(MAX_DOC, PTYPE);
        TestSegments.TextField id = TestSegments.buildTextField(MAX_DOC, IDS);
        SimpleLeafReader reader = SimpleLeafReader.builder(MAX_DOC)
            .field("comment", comment.fieldInfo)
            .terms("comment", comment.terms, comment.docCount)
            .norms("comment", comment.norms)
            .field("ptype", ptype.fieldInfo)
            .terms("ptype", ptype.terms, ptype.docCount)
            .norms("ptype", ptype.norms)
            .field("_id", id.fieldInfo)
            .terms("_id", id.terms, id.docCount)
            .norms("_id", id.norms)
            .build();
        return new IndexSearcher(List.of(reader));
    }

    private static Set<Integer> docIds(TopDocs topDocs) {
        Set<Integer> ids = new TreeSet<>();
        for (ScoreDoc sd : topDocs.scoreDocs()) {
            ids.add(sd.doc);
        }
        return ids;
    }

    @Test
    public void nestedQueryAggregatesChildScoresOntoParent() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Query childQuery = new TermQuery(new Term("comment", "great"));
        NestedQuery nested = new NestedQuery(childQuery, MAPPING, JoinScoreMode.AVG);
        TopDocs topDocs = searcher.search(nested, 10);
        assertEquals(Set.of(2, 5), docIds(topDocs));
    }

    @Test
    public void hasChildMatchesParentWithMatchingChild() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Query childQuery = new TermQuery(new Term("comment", "bad"));
        HasChildQuery hasChild = new HasChildQuery(childQuery, MAPPING, JoinScoreMode.NONE, null, null);
        TopDocs topDocs = searcher.search(hasChild, 10);
        assertEquals(Set.of(2), docIds(topDocs));
    }

    @Test
    public void hasChildRespectsMinChildrenBound() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Query childQuery = new TermQuery(new Term("comment", "great"));
        HasChildQuery hasChild = new HasChildQuery(childQuery, MAPPING, JoinScoreMode.NONE, 2, null);
        TopDocs topDocs = searcher.search(hasChild, 10);
        assertEquals(Set.of(5), docIds(topDocs));
    }

    @Test
    public void hasParentMatchesChildrenOfMatchingParent() throws Exception {
        IndexSearcher searcher = buildSearcher();
        Query parentQuery = new TermQuery(new Term("ptype", "alpha"));
        HasParentQuery hasParent = new HasParentQuery(parentQuery, MAPPING, false);
        TopDocs topDocs = searcher.search(hasParent, 10);
        assertEquals(Set.of(0, 1), docIds(topDocs));
    }

    @Test
    public void parentIdQueryMatchesChildrenOfNamedParent() throws Exception {
        IndexSearcher searcher = buildSearcher();
        ParentIdQuery parentId = new ParentIdQuery("p0", MAPPING);
        TopDocs topDocs = searcher.search(parentId, 10);
        assertEquals(Set.of(0, 1), docIds(topDocs));
    }
}
