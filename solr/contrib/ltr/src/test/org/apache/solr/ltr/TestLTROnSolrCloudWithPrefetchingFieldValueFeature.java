/* * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.ltr;

import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.embedded.JettyConfig;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.ltr.feature.PrefetchingFieldValueFeature;
import org.apache.solr.ltr.model.LinearModel;
import org.apache.solr.util.RestTestHarness;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.AfterClass;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;


public class TestLTROnSolrCloudWithPrefetchingFieldValueFeature extends TestRerankBase {

  private MiniSolrCloudCluster solrCluster;
  String solrconfig = "solrconfig-ltr.xml";
  String schema = "schema.xml";

  SortedMap<ServletHolder,String> extraServlets = null;

  private static final String STORED_FEATURE_STORE_NAME = "stored-feature-store";
  private static final String[] STORED_FIELD_NAMES = new String[]{"storedIntField", "storedLongField",
      "storedFloatField", "storedDoubleField", "storedStrNumField", "storedStrBoolField"};
  private static final String[] STORED_FEATURE_NAMES = new String[]{"storedIntFieldFeature", "storedLongFieldFeature",
      "storedFloatFieldFeature", "storedDoubleFieldFeature", "storedStrNumFieldFeature", "storedStrBoolFieldFeature"};
  private static final String STORED_MODEL_WEIGHTS = "{\"weights\":{\"storedIntFieldFeature\":0.1,\"storedLongFieldFeature\":0.1," +
      "\"storedFloatFieldFeature\":0.1,\"storedDoubleFieldFeature\":0.1," +
      "\"storedStrNumFieldFeature\":0.1,\"storedStrBoolFieldFeature\":0.1}}";

  private static final String DV_FEATURE_STORE_NAME = "dv-feature-store";
  private static final String[] DV_FIELD_NAMES = new String[]{"dvIntPopularity", "dvLongPopularity",
      "dvFloatPopularity", "dvDoublePopularity"};
  private static final String[] DV_FEATURE_NAMES = new String[]{"dvIntPopularityFeature", "dvLongPopularityFeature",
      "dvFloatPopularityFeature", "dvDoublePopularityFeature"};
  private static final String DV_MODEL_WEIGHTS = "{\"weights\":{\"dvIntPopularityFeature\":1.0,\"dvLongPopularityFeature\":1.0," +
      "\"dvFloatPopularityFeature\":1.0,\"dvDoublePopularityFeature\":1.0}}";

  @Override
  public void setUp() throws Exception {
    super.setUp();
    extraServlets = setupTestInit(solrconfig, schema, true);
    System.setProperty("enable.update.log", "true");

    int numberOfShards = random().nextInt(4) + 1;
    int numberOfReplicas = random().nextInt(2) + 1;

    System.out.println("numberOfShards " + numberOfShards);
    System.out.println("numberOfReplicas " + numberOfReplicas);

    int numberOfNodes = numberOfShards * numberOfReplicas;

    setupSolrCluster(numberOfShards, numberOfReplicas, numberOfNodes);
  }

  @Override
  public void tearDown() throws Exception {
    restTestHarness.close();
    restTestHarness = null;
    solrCluster.shutdown();
    super.tearDown();
  }

  @Test
  public void testRanking() throws Exception {
    setUpStoredFieldModelAndFeatures();
    // just a basic sanity check that we can work with the PrefetchingFieldValueFeature
    final SolrQuery query = new SolrQuery("{!func}sub(8,field(popularity))");
    query.setRequestHandler("/query");
    query.add("fl", "*,score");
    query.add("rows", "4");

    // Normal term match
    assertJQ("/query" + query.toQueryString(), "/response/numFound/==8");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='1'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='2'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='3'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[3]/id=='4'");

    query.add("rq", "{!ltr model=stored-fields-model reRankDocs=8}");

    assertJQ("/query" + query.toQueryString(), "/response/numFound/==8");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='8'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='7'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='6'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[3]/id=='5'");

    query.setQuery("*:*");
    query.remove("rows");
    query.add("rows", "8");
    query.remove("rq");
    query.add("rq", "{!ltr model=stored-fields-model reRankDocs=8}");

    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='8'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='7'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='6'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[3]/id=='5'");
  }

  @Test
  public void testDelegationToFieldValueFeature() throws Exception {
    setUpDocValueModelAndFeatures();
    assertU(adoc("id", "21", "popularity", "21", "title", "docValues"));
    assertU(adoc("id", "22", "popularity", "22", "title", "docValues"));
    assertU(adoc("id", "23", "popularity", "23", "title", "docValues"));
    assertU(adoc("id", "24", "popularity", "24", "title", "docValues"));
    assertU(commit());

    // only use fields that are not stored but have docValues
    // the PrefetchingFieldValueWeight should delegate the work to a FieldValueFeatureScorer
    final SolrQuery query = new SolrQuery("{!func}sub(24,field(popularity))");
    query.setRequestHandler("/query");
    query.add("fl", "*,score");
    query.add("fq", "title:docValues");
    query.add("rows", "4");

    // Normal term match
    assertJQ("/query" + query.toQueryString(), "/response/numFound/==4");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='21'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='22'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='23'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[3]/id=='24'");

    // check that the PrefetchingFieldValueFeature delegates the work for docValue fields (reRanking works)
    query.add("rq", "{!ltr model=doc-value-model reRankDocs=4}");

    assertJQ("/query" + query.toQueryString(), "/response/numFound/==4");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[0]/id=='24'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[1]/id=='23'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[2]/id=='22'");
    assertJQ("/query" + query.toQueryString(), "/response/docs/[3]/id=='21'");
  }

  private void setupSolrCluster(int numShards, int numReplicas, int numServers) throws Exception {
    JettyConfig jc = buildJettyConfig("/solr");
    jc = JettyConfig.builder(jc).withServlets(extraServlets).build();
    solrCluster = new MiniSolrCloudCluster(numServers, tmpSolrHome.toPath(), jc);
    File configDir = tmpSolrHome.toPath().resolve("collection1/conf").toFile();
    solrCluster.uploadConfigSet(configDir.toPath(), "conf1");

    solrCluster.getSolrClient().setDefaultCollection(COLLECTION);

    createCollection(COLLECTION, "conf1", numShards, numReplicas);
    indexDocuments(COLLECTION);
    for (JettySolrRunner solrRunner : solrCluster.getJettySolrRunners()) {
      if (!solrRunner.getCoreContainer().getCores().isEmpty()){
        String coreName = solrRunner.getCoreContainer().getCores().iterator().next().getName();
        restTestHarness = new RestTestHarness(() -> solrRunner.getBaseUrl().toString() + "/" + coreName);
        break;
      }
    }
  }

  private void createCollection(String name, String config, int numShards, int numReplicas)
      throws Exception {
    CollectionAdminResponse response;
    CollectionAdminRequest.Create create =
        CollectionAdminRequest.createCollection(name, config, numShards, numReplicas);
    response = create.process(solrCluster.getSolrClient());

    if (response.getStatus() != 0 || response.getErrorMessages() != null) {
      fail("Could not create collection. Response" + response.toString());
    }
    solrCluster.waitForActiveCollection(name, numShards, numShards * numReplicas);
  }

  void indexDocument(String collection, String id, String title, String description, int popularity)
    throws Exception{
    SolrInputDocument doc = new SolrInputDocument();
    doc.setField("id", id);
    doc.setField("title", title);
    doc.setField("description", description);
    doc.setField("popularity", popularity);
    // check that empty values will be read as default
    if (popularity != 1) {
      doc.setField("storedIntField", popularity);
      doc.setField("storedLongField", popularity);
      doc.setField("storedFloatField", ((float) popularity) / 10);
      doc.setField("storedDoubleField", ((double) popularity) / 10);
      doc.setField("storedStrNumField", popularity % 2 == 0 ? "F" : "T");
      doc.setField("storedStrBoolField", popularity % 2 == 0 ? "T" : "F");
    }
    solrCluster.getSolrClient().add(collection, doc);
  }

  private void indexDocuments(final String collection)
       throws Exception {
    final int collectionSize = 8;
    // put documents in random order to check that advanceExact is working correctly
    List<Integer> docIds = IntStream.rangeClosed(1, collectionSize).boxed().collect(toList());
    Collections.shuffle(docIds, random());

    int docCounter = 1;
    for (int docId : docIds) {
      final int popularity = docId;
      indexDocument(collection, String.valueOf(docId), "a1", "bloom", popularity);
      // maybe commit in the middle in order to check that everything works fine for multi-segment case
      if (docCounter == collectionSize / 2 && random().nextBoolean()) {
        solrCluster.getSolrClient().commit(collection);
      }
      docCounter++;
    }
    solrCluster.getSolrClient().commit(collection, true, true);
  }

  private void setUpStoredFieldModelAndFeatures() throws Exception {
    for (int i = 0; i < STORED_FEATURE_NAMES.length; i++) {
      loadFeature(
          STORED_FEATURE_NAMES[i],
          PrefetchingFieldValueFeature.class.getName(),
          STORED_FEATURE_STORE_NAME,
          "{\"field\":\"" + STORED_FIELD_NAMES[i] + "\"}"
      );
    }
    loadModel(
        "stored-fields-model",
        LinearModel.class.getName(),
        STORED_FEATURE_NAMES,
        STORED_FEATURE_STORE_NAME,
        STORED_MODEL_WEIGHTS
    );
    reloadCollection(COLLECTION);
  }

  public void setUpDocValueModelAndFeatures() throws Exception {
    for (int i = 0; i < DV_FEATURE_NAMES.length; i++) {
      loadFeature(
          DV_FEATURE_NAMES[i],
          PrefetchingFieldValueFeature.class.getName(),
          DV_FEATURE_STORE_NAME,
          "{\"field\":\"" + DV_FIELD_NAMES[i] + "\"}"
      );
    }
    loadModel("doc-value-model",
        LinearModel.class.getName(),
        DV_FEATURE_NAMES,
        DV_FEATURE_STORE_NAME,
        DV_MODEL_WEIGHTS);
    reloadCollection(COLLECTION);
  }

  private void reloadCollection(String collection) throws Exception {
    CollectionAdminRequest.Reload reloadRequest = CollectionAdminRequest.reloadCollection(collection);
    CollectionAdminResponse response = reloadRequest.process(solrCluster.getSolrClient());
    assertEquals(0, response.getStatus());
    assertTrue(response.isSuccess());
  }

  @AfterClass
  public static void after() throws Exception {
    if (null != tmpSolrHome) {
      FileUtils.deleteDirectory(tmpSolrHome);
      tmpSolrHome = null;
    }
    System.clearProperty("managed.schema.mutable");
  }
}