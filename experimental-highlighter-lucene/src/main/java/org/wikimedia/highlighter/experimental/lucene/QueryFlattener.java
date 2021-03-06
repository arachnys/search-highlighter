package org.wikimedia.highlighter.experimental.lucene;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.CommonTermsQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.FilteredQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RegexpQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.search.spans.SpanNearQuery;
import org.apache.lucene.search.spans.SpanNotQuery;
import org.apache.lucene.search.spans.SpanOrQuery;
import org.apache.lucene.search.spans.SpanPositionCheckQuery;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.SpanTermQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automata;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.LevenshteinAutomata;
import org.apache.lucene.util.automaton.Operations;

/**
 * Flattens {@link Query}s similarly to Lucene's FieldQuery.
 */
public class QueryFlattener {
    /**
     * Some queries are inefficient to rebuild multiple times so we store some
     * information about them here and check if we've already seen them.
     */
    private final Set<Object> sentAutomata = new HashSet<Object>();
    private final int maxMultiTermQueryTerms;
    private final boolean phraseAsTerms;
    private final boolean removeHighFrequencyTermsFromCommonTerms;

    /**
     * Default configuration.
     */
    public QueryFlattener() {
        this(1000, false, true);
    }

    public QueryFlattener(int maxMultiTermQueryTerms, boolean phraseAsTerms, boolean removeHighFrequencyTermsFromCommonTerms) {
        this.maxMultiTermQueryTerms = maxMultiTermQueryTerms;
        this.phraseAsTerms = phraseAsTerms;
        this.removeHighFrequencyTermsFromCommonTerms = removeHighFrequencyTermsFromCommonTerms;
    }

    public interface Callback {
        /**
         * Called once per query containing the term.
         *
         * @param term the term
         * @param boost weight of the term
         * @param sourceOverride null if the source of the term is the query
         *            containing it, not null if the term query came from some
         *            rewritten query
         */
        int flattened(BytesRef term, float boost, Object sourceOverride);

        /**
         * Called with each new automaton. QueryFlattener makes an effort to
         * only let the first copy of any duplicate automata through.
         *
         * @param automaton automaton from the query
         * @param boost weight of terms matchign the automaton
         * @param source hashcode of the source. Automata don't have a hashcode
         *            so this will always provide the source.
         */
        void flattened(Automaton automaton, float boost, int source);

        /**
         * Called to mark the start of a phrase.
         */
        void startPhrase(int positionCount, float boost);

        void startPhrasePosition(int termCount);

        void endPhrasePosition();

        /**
         * Called to mark the end of a phrase.
         */
        void endPhrase(String field, int slop, float boost);

		/**
		 * Span query callbacks.
		 */
		void endSpanTermQuery(SpanTermQuery query, int source);

		void startSpanNearQuery(SpanNearQuery query);

		void endSpanNearQuery(SpanNearQuery query);

		void startSpanMultiQuery(SpanMultiTermQueryWrapper<?> query);

		void endSpanMultiQuery(SpanMultiTermQueryWrapper<?> query);

		void startSpanOrQuery(SpanOrQuery query);

		void endSpanOrQuery(SpanOrQuery query);

    }

    public void flatten(Query query, IndexReader reader, Callback callback) {
        flatten(query, 1f, null, reader, callback);
    }

    /**
     * Should phrase queries be returned as terms?
     *
     * @return true mean skip startPhrase and endPhrase and give the terms in a
     *         phrase the weight of the whole phrase
     */
    protected boolean phraseAsTerms() {
        return phraseAsTerms;
    }

    protected void flatten(Query query, float pathBoost, Object sourceOverride, IndexReader reader,
            Callback callback) {
        if (query instanceof TermQuery) {
            flattenQuery((TermQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (query instanceof PhraseQuery) {
            flattenQuery((PhraseQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (query instanceof BooleanQuery) {
            flattenQuery((BooleanQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (query instanceof DisjunctionMaxQuery) {
            flattenQuery((DisjunctionMaxQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (query instanceof ConstantScoreQuery) {
            flattenQuery((ConstantScoreQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (query instanceof FilteredQuery) {
            flattenQuery((FilteredQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (query instanceof MultiPhraseQuery) {
            flattenQuery((MultiPhraseQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (query instanceof SpanQuery
                && flattenSpan((SpanQuery) query, pathBoost, sourceOverride, reader, callback)) {
            // Actually nothing to do here, but it keeps the code lining up to
            // have it.
        } else if (query instanceof FuzzyQuery) {
            flattenQuery((FuzzyQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (query instanceof RegexpQuery) {
            flattenQuery((RegexpQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (query instanceof WildcardQuery) {
            flattenQuery((WildcardQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (query instanceof PrefixQuery) {
            flattenQuery((PrefixQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (query instanceof CommonTermsQuery) {
            flattenQuery((CommonTermsQuery) query, pathBoost, sourceOverride, reader, callback);
        } else if (!flattenUnknown(query, pathBoost, sourceOverride, reader, callback)) {
            Query newRewritten = rewriteQuery(query, pathBoost, sourceOverride, reader);
            if (newRewritten != query) {
                // only rewrite once and then flatten again - the rewritten
                // query could have a special treatment
                flatten(newRewritten, pathBoost, query, reader, callback);
            }
        }
    }

    protected boolean flattenSpan(SpanQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        if (query instanceof SpanTermQuery) {
            flattenQuery((SpanTermQuery) query, pathBoost, sourceOverride, reader, callback);
            return true;
        } else if (query instanceof SpanPositionCheckQuery) {
            flattenQuery((SpanPositionCheckQuery) query, pathBoost, sourceOverride, reader,
                    callback);
            return true;
        } else if (query instanceof SpanNearQuery) {
            flattenQuery((SpanNearQuery) query, pathBoost, sourceOverride, reader, callback);
            return true;
        } else if (query instanceof SpanNotQuery) {
            flattenQuery((SpanNotQuery) query, pathBoost, sourceOverride, reader, callback);
            return true;
        } else if (query instanceof SpanOrQuery) {
			flattenQuery((SpanOrQuery) query, pathBoost, sourceOverride,
					reader, callback);
			return true;
		} else if (query instanceof SpanMultiTermQueryWrapper<?>) {
			flattenQuery((SpanMultiTermQueryWrapper<?>) query, pathBoost,
					sourceOverride, reader, callback);
            return true;
        }
        return false;
    }

    protected boolean flattenUnknown(Query query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        return false;
    }

    protected void flattenQuery(TermQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        callback.flattened(query.getTerm().bytes(), pathBoost * query.getBoost(), sourceOverride);
    }

    protected void flattenQuery(PhraseQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        float boost = pathBoost * query.getBoost();
        Term[] terms = query.getTerms();
        if (terms.length == 0) {
            return;
        }
        if (phraseAsTerms) {
            for (Term term : terms) {
                callback.flattened(term.bytes(), boost, sourceOverride);
            }
        } else {
            callback.startPhrase(terms.length, boost);
            for (Term term : terms) {
                callback.startPhrasePosition(1);
                callback.flattened(term.bytes(), 0, sourceOverride);
                callback.endPhrasePosition();
            }
            callback.endPhrase(terms[0].field(), query.getSlop(), boost);
        }
    }

    protected void flattenQuery(BooleanQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        for (BooleanClause clause : query) {
            if (!clause.isProhibited()) {
                flatten(clause.getQuery(), pathBoost * query.getBoost(), sourceOverride, reader,
                        callback);
            }
        }
    }

    protected void flattenQuery(DisjunctionMaxQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        float boost = pathBoost * query.getBoost();
        for (Query clause : query) {
            flatten(clause, boost, sourceOverride, reader, callback);
        }
    }

    protected void flattenQuery(ConstantScoreQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        if (query.getQuery() != null) {
            flatten(query.getQuery(), pathBoost * query.getBoost(), sourceOverride, reader,
                    callback);
        }
        // TODO maybe flatten filter like Elasticsearch does
    }

    protected void flattenQuery(FilteredQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        if (query.getQuery() != null) {
            flatten(query.getQuery(), pathBoost * query.getBoost(), sourceOverride, reader,
                    callback);
        }
        // TODO maybe flatten filter like Elasticsearch does
    }

    protected void flattenQuery(MultiPhraseQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        // Elasticsearch uses a more complicated method to preserve the phrase
        // queries.
        float boost = pathBoost * query.getBoost();
        List<Term[]> termArrays = query.getTermArrays();

        if (phraseAsTerms) {
            for (Term[] terms : termArrays) {
                for (Term term : terms) {
                    callback.flattened(term.bytes(), boost, sourceOverride);
                }
            }
        } else {
            callback.startPhrase(termArrays.size(), boost);
            String field = null;
            for (Term[] terms : termArrays) {
                callback.startPhrasePosition(terms.length);
                for (Term term : terms) {
                    callback.flattened(term.bytes(), 0, sourceOverride);
                    field = term.field();
                }
                callback.endPhrasePosition();
            }
            // field will be null if there are no terms in the phrase which
            // would be weird
            if (field != null) {
                callback.endPhrase(field, query.getSlop(), boost);
            }
        }
    }

	protected void flattenQuery(SpanTermQuery query, float pathBoost,
			Object sourceOverride, IndexReader reader, Callback callback) {
		int source = callback.flattened(query.getTerm().bytes(), 0,
				sourceOverride); // weight was query.getBoost() * pathBoost
		callback.endSpanTermQuery(query, source);
    }

    protected void flattenQuery(SpanPositionCheckQuery query, float pathBoost,
            Object sourceOverride, IndexReader reader, Callback callback) {
        flattenSpan(query.getMatch(), pathBoost * query.getBoost(), sourceOverride, reader,
                callback);
    }

    protected void flattenQuery(SpanNearQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        pathBoost *= query.getBoost();
		callback.startSpanNearQuery(query);
        for (SpanQuery clause : query.getClauses()) {
            flattenSpan(clause, pathBoost, sourceOverride, reader, callback);
        }
		callback.endSpanNearQuery(query);
    }

	protected void flattenQuery(SpanMultiTermQueryWrapper<?> query,
			float pathBoost, Object sourceOverride, IndexReader reader,
			Callback callback) {
		pathBoost *= query.getBoost();
		callback.startSpanMultiQuery(query);
		flatten(query.getWrappedQuery(), pathBoost, sourceOverride, reader,
                callback);
		callback.endSpanMultiQuery(query);
    }

	protected void flattenQuery(SpanNotQuery query, float pathBoost,
			Object sourceOverride, IndexReader reader, Callback callback) {
		flattenSpan(query.getInclude(), query.getBoost() * pathBoost,
				sourceOverride, reader, callback);
	}

	protected void flattenQuery(SpanOrQuery query, float pathBoost,
			Object sourceOverride, IndexReader reader, Callback callback) {
        pathBoost *= query.getBoost();
		callback.startSpanOrQuery(query);
        for (SpanQuery clause : query.getClauses()) {
            flattenSpan(clause, pathBoost, sourceOverride, reader, callback);
        }
		callback.endSpanOrQuery(query);
    }

    protected void flattenQuery(RegexpQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        // This isn't a great "source" because it contains the term's field but
        // its the best we can do here
        if (!sentAutomata.add(query)) {
            return;
        }
        int source = sourceOverride == null ? query.hashCode() : sourceOverride.hashCode();
        callback.flattened(query.getAutomaton(), pathBoost * query.getBoost(), source);
    }

    protected void flattenQuery(WildcardQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        // Should be safe not to copy this because it is fixed...
        if (!sentAutomata.add(query.getTerm().bytes())) {
            return;
        }
        Object source = sourceOverride == null ? query.getTerm().bytes() : sourceOverride;
        callback.flattened(query.getAutomaton(), pathBoost * query.getBoost(), source.hashCode());
    }

    protected void flattenQuery(PrefixQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        flattenPrefixQuery(query.getPrefix().bytes(), pathBoost * query.getBoost(), sourceOverride,
                callback);
    }

    protected void flattenPrefixQuery(BytesRef bytes, float boost, Object sourceOverride,
            Callback callback) {
        // Should be safe not to copy this because it is fixed...
        if (!sentAutomata.add(bytes)) {
            return;
        }
        Object source = sourceOverride == null ? bytes : sourceOverride;
        Automaton automaton = Automata.makeString(bytes.utf8ToString());
        automaton = Operations.concatenate(automaton, Automata.makeAnyString());
        callback.flattened(automaton, boost, source.hashCode());
    }

    protected void flattenQuery(FuzzyQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        float boost = pathBoost * query.getBoost();
        if (query.getMaxEdits() == 0) {
            callback.flattened(query.getTerm().bytes(), boost, sourceOverride);
        }
        String term = query.getTerm().bytes().utf8ToString();
        if (query.getPrefixLength() >= term.length()) {
            callback.flattened(query.getTerm().bytes(), boost, sourceOverride);
            return;
        }

        FuzzyQueryInfo key = new FuzzyQueryInfo(term, query);
        if (!sentAutomata.add(key)) {
            return;
        }
        // Make an effort to resolve the fuzzy query to an automata
        String fuzzed = term.substring(query.getPrefixLength());
        int editDistance = query.getMaxEdits();
        if (editDistance > LevenshteinAutomata.MAXIMUM_SUPPORTED_DISTANCE) {
            editDistance = LevenshteinAutomata.MAXIMUM_SUPPORTED_DISTANCE;
        }
        LevenshteinAutomata automata = new LevenshteinAutomata(fuzzed, query.getTranspositions());
        Automaton automaton = automata.toAutomaton(editDistance);
        if (query.getPrefixLength() > 0) {
            Automaton prefix = Automata.makeString(term.substring(0, query.getPrefixLength()));
            automaton = Operations.concatenate(prefix, automaton);
        }
        Object source = sourceOverride == null ? key : sourceOverride;
        callback.flattened(automaton, boost, source.hashCode());
    }

    protected void flattenQuery(CommonTermsQuery query, float pathBoost, Object sourceOverride,
            IndexReader reader, Callback callback) {
        Query rewritten = rewriteQuery(query, pathBoost, sourceOverride, reader);
        if (!removeHighFrequencyTermsFromCommonTerms) {
            flatten(rewritten, pathBoost, sourceOverride, reader, callback);
            return;
        }
        /*
         * Try to figure out if the query was rewritten into a list of low and
         * high frequency terms. If it was, remove the high frequency terms.
         *
         * Note that this only works if high frequency terms are set to
         * Occur.SHOULD and low frequency terms are set to Occur.MUST. That is
         * usually the way it is done though.
         */
        if (!(rewritten instanceof BooleanQuery)) {
            // Nope - its a term query or something more exotic
            flatten(rewritten, pathBoost, sourceOverride, reader, callback);
        }
        BooleanQuery bq = (BooleanQuery) rewritten;
        BooleanClause[] clauses = bq.getClauses();
        if (clauses.length != 2) {
            // Nope - its just a list of terms.
            flattenQuery(bq, pathBoost, sourceOverride, reader, callback);
            return;
        }
        if (clauses[0].getOccur() != Occur.SHOULD || clauses[1].getOccur() != Occur.MUST) {
            // Nope - just a two term query
            flattenQuery(bq, pathBoost, sourceOverride, reader, callback);
            return;
        }
        if (!(clauses[0].getQuery() instanceof BooleanQuery && clauses[1].getQuery() instanceof BooleanQuery)) {
            // Nope - terms of the wrong type. not sure how that happened.
            flattenQuery(bq, pathBoost, sourceOverride, reader, callback);
            return;
        }
        BooleanQuery lowFrequency = (BooleanQuery) clauses[1].getQuery();
        flattenQuery(lowFrequency, pathBoost, sourceOverride, reader, callback);
    }

    protected Query rewriteQuery(MultiTermQuery query, float pathBoost, Object sourceOverride, IndexReader reader) {
        query = (MultiTermQuery) query.clone();
        query.setRewriteMethod(new MultiTermQuery.TopTermsScoringBooleanQueryRewrite(
                maxMultiTermQueryTerms));
        return rewritePreparedQuery(query, pathBoost, sourceOverride, reader);
    }

    protected Query rewriteQuery(Query query, float pathBoost, Object sourceOverride, IndexReader reader) {
        if (query instanceof MultiTermQuery) {
            return rewriteQuery((MultiTermQuery) query, pathBoost, sourceOverride, reader);
        }
        return rewritePreparedQuery(query, pathBoost, sourceOverride, reader);
    }

    /**
     * Rewrites a query that's already ready for rewriting.
     */
    protected Query rewritePreparedQuery(Query query, float pathBoost, Object sourceOverride, IndexReader reader) {
        try {
            return query.rewrite(reader);
        } catch (IOException e) {
            throw new WrappedExceptionFromLucene(e);
        }
    }

    private static class FuzzyQueryInfo {
        private final String term;
        private final int maxEdits;
        private final boolean transpositions;
        private final int prefixLength;

        public FuzzyQueryInfo(String term, FuzzyQuery query) {
            this.term = term;
            this.maxEdits = query.getMaxEdits();
            this.transpositions = query.getTranspositions();
            this.prefixLength = query.getPrefixLength();
        }

        // Eclipse made these:
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + maxEdits;
            result = prime * result + prefixLength;
            result = prime * result + ((term == null) ? 0 : term.hashCode());
            result = prime * result + (transpositions ? 1231 : 1237);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            FuzzyQueryInfo other = (FuzzyQueryInfo) obj;
            if (maxEdits != other.maxEdits)
                return false;
            if (prefixLength != other.prefixLength)
                return false;
            if (term == null) {
                if (other.term != null)
                    return false;
            } else if (!term.equals(other.term))
                return false;
            if (transpositions != other.transpositions)
                return false;
            return true;
        }
    }
}
