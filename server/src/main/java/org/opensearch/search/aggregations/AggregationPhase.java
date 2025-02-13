/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

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
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.aggregations;

import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.Query;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.lucene.search.Queries;
import org.opensearch.search.aggregations.bucket.global.GlobalAggregator;
import org.opensearch.search.internal.SearchContext;
import org.opensearch.search.profile.query.CollectorResult;
import org.opensearch.search.profile.query.InternalProfileCollector;
import org.opensearch.search.query.QueryPhaseExecutionException;
import org.opensearch.search.query.ReduceableSearchResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Aggregation phase of a search request, used to collect aggregations
 *
 * @opensearch.internal
 */
public class AggregationPhase {

    @Inject
    public AggregationPhase() {}

    public void preProcess(SearchContext context) {
        if (context.aggregations() != null) {
            List<Aggregator> collectors = new ArrayList<>();
            Aggregator[] aggregators;
            try {
                AggregatorFactories factories = context.aggregations().factories();
                aggregators = factories.createTopLevelAggregators(context);
                for (int i = 0; i < aggregators.length; i++) {
                    if (aggregators[i] instanceof GlobalAggregator == false) {
                        collectors.add(aggregators[i]);
                    }
                }
                context.aggregations().aggregators(aggregators);
                if (!collectors.isEmpty()) {
                    final Collector collector = createCollector(context, collectors);
                    context.queryCollectorManagers().put(AggregationPhase.class, new CollectorManager<Collector, ReduceableSearchResult>() {
                        @Override
                        public Collector newCollector() throws IOException {
                            return collector;
                        }

                        @Override
                        public ReduceableSearchResult reduce(Collection<Collector> collectors) throws IOException {
                            throw new UnsupportedOperationException("The concurrent aggregation over index segments is not supported");
                        }
                    });
                }
            } catch (IOException e) {
                throw new AggregationInitializationException("Could not initialize aggregators", e);
            }
        }
    }

    public void execute(SearchContext context) {
        if (context.aggregations() == null) {
            context.queryResult().aggregations(null);
            return;
        }

        if (context.queryResult().hasAggs()) {
            // no need to compute the aggs twice, they should be computed on a per context basis
            return;
        }

        Aggregator[] aggregators = context.aggregations().aggregators();
        List<Aggregator> globals = new ArrayList<>();
        for (int i = 0; i < aggregators.length; i++) {
            if (aggregators[i] instanceof GlobalAggregator) {
                globals.add(aggregators[i]);
            }
        }

        // optimize the global collector based execution
        if (!globals.isEmpty()) {
            BucketCollector globalsCollector = MultiBucketCollector.wrap(globals);
            Query query = context.buildFilteredQuery(Queries.newMatchAllQuery());

            try {
                final Collector collector;
                if (context.getProfilers() == null) {
                    collector = globalsCollector;
                } else {
                    InternalProfileCollector profileCollector = new InternalProfileCollector(
                        globalsCollector,
                        CollectorResult.REASON_AGGREGATION_GLOBAL,
                        // TODO: report on sub collectors
                        Collections.emptyList()
                    );
                    collector = profileCollector;
                    // start a new profile with this collector
                    context.getProfilers().addQueryProfiler().setCollector(profileCollector);
                }
                globalsCollector.preCollection();
                context.searcher().search(query, collector);
            } catch (Exception e) {
                throw new QueryPhaseExecutionException(context.shardTarget(), "Failed to execute global aggregators", e);
            }
        }

        List<InternalAggregation> aggregations = new ArrayList<>(aggregators.length);
        context.aggregations().resetBucketMultiConsumer();
        for (Aggregator aggregator : context.aggregations().aggregators()) {
            try {
                aggregator.postCollection();
                aggregations.add(aggregator.buildTopLevel());
            } catch (IOException e) {
                throw new AggregationExecutionException("Failed to build aggregation [" + aggregator.name() + "]", e);
            }
        }
        context.queryResult()
            .aggregations(new InternalAggregations(aggregations, context.request().source().aggregations()::buildPipelineTree));

        // disable aggregations so that they don't run on next pages in case of scrolling
        context.aggregations(null);
        context.queryCollectorManagers().remove(AggregationPhase.class);
    }

    private Collector createCollector(SearchContext context, List<Aggregator> collectors) throws IOException {
        Collector collector = MultiBucketCollector.wrap(collectors);
        ((BucketCollector) collector).preCollection();
        if (context.getProfilers() != null) {
            collector = new InternalProfileCollector(
                collector,
                CollectorResult.REASON_AGGREGATION,
                // TODO: report on child aggs as well
                Collections.emptyList()
            );
        }
        return collector;
    }
}
