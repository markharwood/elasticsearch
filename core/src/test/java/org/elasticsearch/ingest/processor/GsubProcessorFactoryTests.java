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

package org.elasticsearch.ingest.processor;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.ingest.core.AbstractProcessorFactory;
import org.elasticsearch.ingest.core.Processor;
import org.elasticsearch.test.ESTestCase;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.equalTo;

public class GsubProcessorFactoryTests extends ESTestCase {

    public void testCreate() throws Exception {
        GsubProcessor.Factory factory = new GsubProcessor.Factory();
        Map<String, Object> config = new HashMap<>();
        config.put("field", "field1");
        config.put("pattern", "\\.");
        config.put("replacement", "-");
        String processorTag = randomAsciiOfLength(10);
        config.put(AbstractProcessorFactory.TAG_KEY, processorTag);
        GsubProcessor gsubProcessor = factory.create(config);
        assertThat(gsubProcessor.getTag(), equalTo(processorTag));
        assertThat(gsubProcessor.getField(), equalTo("field1"));
        assertThat(gsubProcessor.getPattern().toString(), equalTo("\\."));
        assertThat(gsubProcessor.getReplacement(), equalTo("-"));
    }

    public void testCreateNoFieldPresent() throws Exception {
        GsubProcessor.Factory factory = new GsubProcessor.Factory();
        Map<String, Object> config = new HashMap<>();
        config.put("pattern", "\\.");
        config.put("replacement", "-");
        try {
            factory.create(config);
            fail("factory create should have failed");
        } catch(ElasticsearchParseException e) {
            assertThat(e.getMessage(), equalTo("[field] required property is missing"));
        }
    }

    public void testCreateNoPatternPresent() throws Exception {
        GsubProcessor.Factory factory = new GsubProcessor.Factory();
        Map<String, Object> config = new HashMap<>();
        config.put("field", "field1");
        config.put("replacement", "-");
        try {
            factory.create(config);
            fail("factory create should have failed");
        } catch(ElasticsearchParseException e) {
            assertThat(e.getMessage(), equalTo("[pattern] required property is missing"));
        }
    }

    public void testCreateNoReplacementPresent() throws Exception {
        GsubProcessor.Factory factory = new GsubProcessor.Factory();
        Map<String, Object> config = new HashMap<>();
        config.put("field", "field1");
        config.put("pattern", "\\.");
        try {
            factory.create(config);
            fail("factory create should have failed");
        } catch(ElasticsearchParseException e) {
            assertThat(e.getMessage(), equalTo("[replacement] required property is missing"));
        }
    }
}
