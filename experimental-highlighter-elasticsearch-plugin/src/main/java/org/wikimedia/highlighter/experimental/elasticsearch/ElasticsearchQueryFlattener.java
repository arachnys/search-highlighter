package org.wikimedia.highlighter.experimental.elasticsearch;

import java.util.List;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.elasticsearch.common.lucene.search.MultiPhrasePrefixQuery;
import org.elasticsearch.common.lucene.search.XFilteredQuery;
import org.elasticsearch.common.lucene.search.function.FiltersFunctionScoreQuery;
import org.elasticsearch.common.lucene.search.function.FunctionScoreQuery;
import org.wikimedia.highlighter.experimental.lucene.QueryFlattener;

public class ElasticsearchQueryFlattener extends QueryFlattener {
    /**
     * Default configuration.
     */
    public ElasticsearchQueryFlattener() {
        super();
    }

    public ElasticsearchQueryFlattener(int maxMultiTermQueryTerms, boolean phraseAsTerms, boolean removeHighFrequencyTermsFromCommonTerms) {
        super(maxMultiTermQueryTerms, phraseAsTerms, removeHighFrequencyTermsFromCommonTerms);
    }

    @Override
    protected boolean flattenUnknown(Query query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        if (query instanceof XFilteredQuery) {
            flattenQuery((XFilteredQuery) query, pathBoost, sourceOverride, reader, callback);
            return true;
        }
        if (query instanceof MultiPhrasePrefixQuery) {
            flattenQuery((MultiPhrasePrefixQuery) query, pathBoost, sourceOverride, reader,
                    callback);
            return true;
        }
        if (query instanceof FunctionScoreQuery) {
            flattenQuery((FunctionScoreQuery) query, pathBoost, sourceOverride, reader,
                    callback);
            return true;
        }
        if (query instanceof FiltersFunctionScoreQuery) {
            flattenQuery((FiltersFunctionScoreQuery) query, pathBoost, sourceOverride, reader,
                    callback);
            return true;
        }
        return false;
    }

    protected void flattenQuery(XFilteredQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        if (query.getQuery() != null) {
            flatten(query.getQuery(), pathBoost * query.getBoost(), sourceOverride, reader,
                    callback);
        }
        // TODO maybe flatten filter like Elasticsearch does
    }

    protected void flattenQuery(MultiPhrasePrefixQuery query, float pathBoost,
            Object sourceOverride, IndexReader reader, Callback callback) {
        // Note that we don't declare all of these to come from a single source
        // because that will cause each individual term to be devalued in
        // relation to things outside the term query
        List<Term[]> termArrays = query.getTermArrays();
        float boost = pathBoost * query.getBoost();
        if (termArrays.isEmpty()) {
            return;
        }
        int sizeMinus1 = termArrays.size() - 1;
        if (phraseAsTerms()) {
            for (int i = 0; i < sizeMinus1; i++) {
                Term[] termArray = termArrays.get(i);
                for (Term term : termArray) {
                    callback.flattened(term.bytes(), boost, sourceOverride);
                }
            }
            for (Term term : termArrays.get(sizeMinus1)) {
                flattenPrefixQuery(term.bytes(), boost, sourceOverride, callback);
            }
        } else {
            callback.startPhrase(termArrays.size(), boost);
            for (int i = 0; i < sizeMinus1; i++) {
                Term[] termArray = termArrays.get(i);
                callback.startPhrasePosition(termArray.length);
                for (Term term : termArray) {
                    callback.flattened(term.bytes(), 0, sourceOverride);
                }
                callback.endPhrasePosition();
            }
            callback.startPhrasePosition(termArrays.get(sizeMinus1).length);
            for (Term term : termArrays.get(sizeMinus1)) {
                flattenPrefixQuery(term.bytes(), 0, sourceOverride, callback);
            }
            callback.endPhrasePosition();
            callback.endPhrase(query.getField(), query.getSlop(), boost);
        }
    }

    protected void flattenQuery(FunctionScoreQuery query, float pathBoost,
            Object sourceOverride, IndexReader reader, Callback callback) {
        if (query.getSubQuery() != null) {
            flatten(query.getSubQuery(), pathBoost * query.getBoost(), sourceOverride, reader, callback);
        }
    }

    protected void flattenQuery(FiltersFunctionScoreQuery query, float pathBoost,
            Object sourceOverride, IndexReader reader, Callback callback) {
        if (query.getSubQuery() != null) {
            flatten(query.getSubQuery(), pathBoost * query.getBoost(), sourceOverride, reader, callback);
        }
    }
}
