/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.bucket.significant;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ParseFieldRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.search.aggregations.AbstractAggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationInitializationException;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories.Builder;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.SignificanceHeuristic;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.SignificanceHeuristicParser;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregator;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregator.BucketCountThresholds;
import org.elasticsearch.search.aggregations.bucket.terms.support.IncludeExclude;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Objects;

public class SignificantTextAggregationBuilder extends AbstractAggregationBuilder<SignificantTextAggregationBuilder> {
    public static final String NAME = "significant_text";

    static final ParseField FIELD_NAME = new ParseField("field");
    static final ParseField FILTER_DUPLICATE_TEXT_FIELD_NAME = new ParseField(
            "filter_duplicate_text");

    static final TermsAggregator.BucketCountThresholds DEFAULT_BUCKET_COUNT_THRESHOLDS = 
            SignificantTermsAggregationBuilder.DEFAULT_BUCKET_COUNT_THRESHOLDS;
    static final SignificanceHeuristic DEFAULT_SIGNIFICANCE_HEURISTIC = SignificantTermsAggregationBuilder.DEFAULT_SIGNIFICANCE_HEURISTIC;

    private String fieldName = null;
    private boolean filterDuplicateText = false;
    private IncludeExclude includeExclude = null;
    private QueryBuilder filterBuilder = null;
    private TermsAggregator.BucketCountThresholds bucketCountThresholds = new BucketCountThresholds(
            DEFAULT_BUCKET_COUNT_THRESHOLDS);
    private SignificanceHeuristic significanceHeuristic = DEFAULT_SIGNIFICANCE_HEURISTIC;

    public static Aggregator.Parser getParser(
            ParseFieldRegistry<SignificanceHeuristicParser> significanceHeuristicParserRegistry) {
        ObjectParser<SignificantTextAggregationBuilder, QueryParseContext> parser = new ObjectParser<>(
                SignificantTextAggregationBuilder.NAME);

        parser.declareInt(SignificantTextAggregationBuilder::shardSize,
                TermsAggregationBuilder.SHARD_SIZE_FIELD_NAME);

        parser.declareLong(SignificantTextAggregationBuilder::minDocCount,
                TermsAggregationBuilder.MIN_DOC_COUNT_FIELD_NAME);

        parser.declareLong(SignificantTextAggregationBuilder::shardMinDocCount,
                TermsAggregationBuilder.SHARD_MIN_DOC_COUNT_FIELD_NAME);

        parser.declareInt(SignificantTextAggregationBuilder::size,
                TermsAggregationBuilder.REQUIRED_SIZE_FIELD_NAME);

        parser.declareString(SignificantTextAggregationBuilder::fieldName, FIELD_NAME);

        parser.declareBoolean(SignificantTextAggregationBuilder::filterDuplicateText,
                FILTER_DUPLICATE_TEXT_FIELD_NAME);

        parser.declareObject(SignificantTextAggregationBuilder::backgroundFilter,
                (p, context) -> context.parseInnerQueryBuilder(),
                SignificantTermsAggregationBuilder.BACKGROUND_FILTER);

        parser.declareField((b, v) -> b.includeExclude(IncludeExclude.merge(v, b.includeExclude())),
                IncludeExclude::parseInclude, IncludeExclude.INCLUDE_FIELD,
                ObjectParser.ValueType.OBJECT_ARRAY_OR_STRING);

        parser.declareField((b, v) -> b.includeExclude(IncludeExclude.merge(b.includeExclude(), v)),
                IncludeExclude::parseExclude, IncludeExclude.EXCLUDE_FIELD,
                ObjectParser.ValueType.STRING_ARRAY);

        for (String name : significanceHeuristicParserRegistry.getNames()) {
            parser.declareObject(SignificantTextAggregationBuilder::significanceHeuristic,
                    (p, context) -> {
                        SignificanceHeuristicParser significanceHeuristicParser = significanceHeuristicParserRegistry
                                .lookupReturningNullIfNotFound(name);
                        return significanceHeuristicParser.parse(context);
                    }, new ParseField(name));
        }
        return new Aggregator.Parser() {
            @Override
            public AggregationBuilder parse(String aggregationName, QueryParseContext context)
                    throws IOException {
                return parser.parse(context.parser(),
                        new SignificantTextAggregationBuilder(aggregationName, null), context);
            }
        };
    }

    protected TermsAggregator.BucketCountThresholds getBucketCountThresholds() {
        return new TermsAggregator.BucketCountThresholds(bucketCountThresholds);
    }

    public TermsAggregator.BucketCountThresholds bucketCountThresholds() {
        return bucketCountThresholds;
    }
    
    
    @Override
    public SignificantTextAggregationBuilder subAggregations(Builder subFactories) {
        throw new AggregationInitializationException("Aggregator [" + name + "] of type ["
                + getType() + "] cannot accept sub-aggregations");
    }    

    @Override
    public SignificantTextAggregationBuilder subAggregation(AggregationBuilder aggregation) {
        throw new AggregationInitializationException("Aggregator [" + name + "] of type ["
                + getType() + "] cannot accept sub-aggregations");
    }    
    
    public SignificantTextAggregationBuilder bucketCountThresholds(
            TermsAggregator.BucketCountThresholds bucketCountThresholds) {
        if (bucketCountThresholds == null) {
            throw new IllegalArgumentException(
                    "[bucketCountThresholds] must not be null: [" + name + "]");
        }
        this.bucketCountThresholds = bucketCountThresholds;
        return this;
    }

    /**
     * Sets the size - indicating how many term buckets should be returned
     * (defaults to 10)
     */
    public SignificantTextAggregationBuilder size(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException(
                    "[size] must be greater than 0. Found [" + size + "] in [" + name + "]");
        }
        bucketCountThresholds.setRequiredSize(size);
        return this;
    }

    /**
     * Sets the shard_size - indicating the number of term buckets each shard
     * will return to the coordinating node (the node that coordinates the
     * search execution). The higher the shard size is, the more accurate the
     * results are.
     */
    public SignificantTextAggregationBuilder shardSize(int shardSize) {
        if (shardSize <= 0) {
            throw new IllegalArgumentException("[shardSize] must be greater than  0. Found ["
                    + shardSize + "] in [" + name + "]");
        }
        bucketCountThresholds.setShardSize(shardSize);
        return this;
    }

    /**
     * Sets the name of the text field that will be the subject of this
     * aggregation.
     */
    public SignificantTextAggregationBuilder fieldName(String fieldName) {
        this.fieldName = fieldName;
        return this;
    }

    /**
     * Control if duplicate paragraphs of text should try be filtered from the
     * statistical text analysis. Can improve results but slows down analysis.
     * Default is false.
     */
    public SignificantTextAggregationBuilder filterDuplicateText(boolean filterDuplicateText) {
        this.filterDuplicateText = filterDuplicateText;
        return this;
    }

    /**
     * Set the minimum document count terms should have in order to appear in
     * the response.
     */
    public SignificantTextAggregationBuilder minDocCount(long minDocCount) {
        if (minDocCount < 0) {
            throw new IllegalArgumentException(
                    "[minDocCount] must be greater than or equal to 0. Found [" + minDocCount
                            + "] in [" + name + "]");
        }
        bucketCountThresholds.setMinDocCount(minDocCount);
        return this;
    }

    /**
     * Set the minimum document count terms should have on the shard in order to
     * appear in the response.
     */
    public SignificantTextAggregationBuilder shardMinDocCount(long shardMinDocCount) {
        if (shardMinDocCount < 0) {
            throw new IllegalArgumentException(
                    "[shardMinDocCount] must be greater than or equal to 0. Found ["
                            + shardMinDocCount + "] in [" + name + "]");
        }
        bucketCountThresholds.setShardMinDocCount(shardMinDocCount);
        return this;
    }

    public SignificantTextAggregationBuilder backgroundFilter(QueryBuilder backgroundFilter) {
        if (backgroundFilter == null) {
            throw new IllegalArgumentException(
                    "[backgroundFilter] must not be null: [" + name + "]");
        }
        this.filterBuilder = backgroundFilter;
        return this;
    }

    public QueryBuilder backgroundFilter() {
        return filterBuilder;
    }

    /**
     * Set terms to include and exclude from the aggregation results
     */
    public SignificantTextAggregationBuilder includeExclude(IncludeExclude includeExclude) {
        this.includeExclude = includeExclude;
        return this;
    }

    /**
     * Get terms to include and exclude from the aggregation results
     */
    public IncludeExclude includeExclude() {
        return includeExclude;
    }

    public SignificantTextAggregationBuilder significanceHeuristic(
            SignificanceHeuristic significanceHeuristic) {
        if (significanceHeuristic == null) {
            throw new IllegalArgumentException(
                    "[significanceHeuristic] must not be null: [" + name + "]");
        }
        this.significanceHeuristic = significanceHeuristic;
        return this;
    }

    public SignificanceHeuristic significanceHeuristic() {
        return significanceHeuristic;
    }

    /**
     * @param name
     *            the name of this aggregation
     * @param fieldName
     *            the name of the text field that will be the subject of this
     *            aggregation
     * 
     */
    public SignificantTextAggregationBuilder(String name, String fieldName) {
        super(name);
        this.fieldName = fieldName;
    }

    /**
     * Read from a stream.
     */
    public SignificantTextAggregationBuilder(StreamInput in) throws IOException {
        super(in);
        fieldName = in.readString();
        filterDuplicateText = in.readBoolean();
        bucketCountThresholds = new BucketCountThresholds(in);
        filterBuilder = in.readOptionalNamedWriteable(QueryBuilder.class);
        includeExclude = in.readOptionalWriteable(IncludeExclude::new);
        significanceHeuristic = in.readNamedWriteable(SignificanceHeuristic.class);
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(fieldName);
        out.writeBoolean(filterDuplicateText);
        bucketCountThresholds.writeTo(out);
        out.writeOptionalNamedWriteable(filterBuilder);
        out.writeOptionalWriteable(includeExclude);
        out.writeNamedWriteable(significanceHeuristic);
    }

    @Override
    protected AggregatorFactory<?> doBuild(SearchContext context, AggregatorFactory<?> parent,
            Builder subFactoriesBuilder) throws IOException {
        SignificanceHeuristic executionHeuristic = this.significanceHeuristic.rewrite(context);
        return new SignificantTextAggregatorFactory(name, includeExclude, filterBuilder,
                bucketCountThresholds, executionHeuristic, context, parent, subFactoriesBuilder,
                fieldName, filterDuplicateText, metaData);
    }

    @Override
    protected XContentBuilder internalXContent(XContentBuilder builder, Params params)
            throws IOException {
        builder.startObject();
        bucketCountThresholds.toXContent(builder, params);
        if (fieldName != null) {
            builder.field(FIELD_NAME.getPreferredName(), fieldName);
        }
        if (filterDuplicateText) {
            builder.field(FILTER_DUPLICATE_TEXT_FIELD_NAME.getPreferredName(), filterDuplicateText);
        }
        if (filterBuilder != null) {
            builder.field(SignificantTermsAggregationBuilder.BACKGROUND_FILTER.getPreferredName(),
                    filterBuilder);
        }
        if (includeExclude != null) {
            includeExclude.toXContent(builder, params);
        }
        significanceHeuristic.toXContent(builder, params);
        
        builder.endObject();
        return builder;
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(bucketCountThresholds, fieldName, filterDuplicateText, filterBuilder,
                includeExclude, significanceHeuristic);
    }

    @Override
    protected boolean doEquals(Object obj) {
        SignificantTextAggregationBuilder other = (SignificantTextAggregationBuilder) obj;
        return Objects.equals(bucketCountThresholds, other.bucketCountThresholds)
                && Objects.equals(fieldName, other.fieldName)
                && filterDuplicateText == other.filterDuplicateText
                && Objects.equals(filterBuilder, other.filterBuilder)
                && Objects.equals(includeExclude, other.includeExclude)
                && Objects.equals(significanceHeuristic, other.significanceHeuristic);
    }

    @Override
    public String getType() {
        return NAME;
    }
}
