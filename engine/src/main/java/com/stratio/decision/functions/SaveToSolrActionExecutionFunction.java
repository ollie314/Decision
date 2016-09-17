/**
 * Copyright (C) 2014 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.decision.functions;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.spark.api.java.JavaPairRDD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import com.google.common.collect.Iterables;
import com.stratio.decision.commons.constants.StreamAction;
import com.stratio.decision.commons.messages.ColumnNameTypeValue;
import com.stratio.decision.commons.messages.StratioStreamingMessage;
import com.stratio.decision.service.SolrOperationsService;
import com.stratio.decision.utils.RetryStrategy;

import scala.Tuple2;

public class SaveToSolrActionExecutionFunction extends BaseActionExecutionFunction {

    private static final long serialVersionUID = 3522740757019463301L;

    private static final Logger log = LoggerFactory.getLogger(SaveToSolrActionExecutionFunction.class);

    private Map<String, SolrClient> solrClients = new HashMap<>();
    private List<String> solrCores = new ArrayList<String>();

    private transient SolrOperationsService solrOperationsService;

    private RetryStrategy retryStrategy;

    private final String dataDir;
    private final String solrHost;
    private final String zkHost;
    private final Boolean isCloud;
    private final Integer maxBatchSize;

    public SaveToSolrActionExecutionFunction(String solrHost, String zkHost, Boolean isCloud, String dataDir, Integer
            maxBatchSize) {
        this.solrHost = solrHost;
        this.zkHost = zkHost;
        this.dataDir = dataDir;
        this.isCloud = isCloud;
        this.retryStrategy = new RetryStrategy();
        this.maxBatchSize = maxBatchSize!=null?maxBatchSize:-1;
    }

    public SaveToSolrActionExecutionFunction(String solrHost, String zkHost, Boolean isCloud, String dataDir, Integer
            maxBatchSize, SolrOperationsService solrOperationsService) {
        this.solrHost = solrHost;
        this.zkHost = zkHost;
        this.dataDir = dataDir;
        this.isCloud = isCloud;
        this.solrOperationsService = solrOperationsService;
        this.retryStrategy = new RetryStrategy();
        this.maxBatchSize = maxBatchSize!=null?maxBatchSize:-1;
    }

    @Override
    public Boolean check() throws Exception {
        try {
            getSolrOperationsService().getCoreList();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void process(Iterable<StratioStreamingMessage> messages) throws Exception {


        Integer partitionSize = maxBatchSize;

        if (partitionSize <= 0){
            partitionSize = Iterables.size(messages);
        }

        Iterable<List<StratioStreamingMessage>> partitionIterables =  Iterables.partition(messages, partitionSize);

        try {

            for (List<StratioStreamingMessage> messageList : partitionIterables) {

                Map<String, Collection<SolrInputDocument>> elemntsToInsert = new HashMap<String, Collection<SolrInputDocument>>();
                int count = 0;
                for (StratioStreamingMessage stratioStreamingMessage : messageList) {
                    count += 1;
                    SolrInputDocument document = new SolrInputDocument();
                    document.addField("stratio_decision_id", System.nanoTime() + "-" + count);
                    for (ColumnNameTypeValue column : stratioStreamingMessage.getColumns()) {
                        document.addField(column.getColumn(), column.getValue());
                    }
                    checkCore(stratioStreamingMessage);
                    Collection<SolrInputDocument> collection = elemntsToInsert
                            .get(stratioStreamingMessage.getStreamName());
                    if (collection == null) {
                        collection = new HashSet<>();
                    }
                    collection.add(document);
                    elemntsToInsert.put(stratioStreamingMessage.getStreamName(), collection);
                }
                while (retryStrategy.shouldRetry()) {
                    try {
                        for (Map.Entry<String, Collection<SolrInputDocument>> elem : elemntsToInsert.entrySet()) {
                            getSolrclient(elem.getKey()).add(elem.getValue());
                        }
                        break;
                    } catch (SolrException e) {
                        try {
                            log.error("Solr cloud status not yet properly initialized, retrying");
                            retryStrategy.errorOccured();
                        } catch (RuntimeException ex) {
                            log.error("Error while initializing Solr Cloud core ", ex.getMessage());
                        }
                    }
                }
                flushClients();
            }
        } catch (Exception ex) {
            log.error("Error in Solr: " + ex.getMessage());
        }

    }

    private void checkCore(StratioStreamingMessage message) throws IOException, SolrServerException, ParserConfigurationException, TransformerException, SAXException, URISyntaxException, InterruptedException {
        String core = message.getStreamName();
        //check if core exists
        if (solrCores.size() == 0) {
            // Initialize solrcores list
            solrCores = getSolrOperationsService().getCoreList();
        }
        if (!solrCores.contains(core)) {
            // Create Core
            getSolrOperationsService().createCore(message);
            // Update core list
            solrCores = getSolrOperationsService().getCoreList();
        }
    }

    private SolrClient getClient(StratioStreamingMessage message) throws IOException, SolrServerException, URISyntaxException, TransformerException, SAXException, ParserConfigurationException {
        String core = message.getStreamName();
        if (solrClients.containsKey(core)) {
            //we have a client for this core
            return solrClients.get(core);
        } else {
            SolrClient solrClient = getSolrclient(core);
            solrClients.put(core, solrClient);
            return solrClient;
        }
    }

    private void flushClients() throws IOException, SolrServerException, URISyntaxException {
        //Do commit in all Solrclients
        for (String core : solrClients.keySet()) {
            getSolrclient(core).commit();
        }
    }

    private SolrClient getSolrclient(String core) {
        SolrClient solrClient;
        if (solrClients.containsKey(core)) {
            //we have a client for this core
            return solrClients.get(core);
        } else {
            if (isCloud) {
                solrClient = new CloudSolrClient(zkHost);
                ((CloudSolrClient) solrClient).setDefaultCollection(core);
            } else {
                solrClient = new HttpSolrClient("http://" + solrHost + "/solr/" + core);
            }
            solrClients.put(core, solrClient);
        }
        return solrClient;
    }

    private SolrOperationsService getSolrOperationsService() {
        if (solrOperationsService == null) {
            solrOperationsService = (SolrOperationsService) ActionBaseContext.getInstance().getContext().getBean
                    ("solrOperationsService");
        }

        return solrOperationsService;
    }


}
