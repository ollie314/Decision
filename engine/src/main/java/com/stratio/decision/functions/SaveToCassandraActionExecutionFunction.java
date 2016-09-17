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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.spark.api.java.JavaPairRDD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.BatchStatement;
import com.google.common.collect.Iterables;
import com.stratio.decision.commons.constants.STREAMING;
import com.stratio.decision.commons.constants.StreamAction;
import com.stratio.decision.commons.messages.ColumnNameTypeValue;
import com.stratio.decision.commons.messages.StratioStreamingMessage;
import com.stratio.decision.service.SaveToCassandraOperationsService;

import scala.Tuple2;

public class SaveToCassandraActionExecutionFunction extends BaseActionExecutionFunction {

    private static final long serialVersionUID = -3116164624590830333L;

    private static final Logger log = LoggerFactory.getLogger(SaveToCassandraActionExecutionFunction.class);


    private final String cassandraQuorum;
    private final int cassandraPort;
    private final int maxBatchSize;
    private final transient BatchStatement.Type batchType;


    private  transient SaveToCassandraOperationsService cassandraTableOperationsService;

    public SaveToCassandraActionExecutionFunction(String cassandraQuorum, int cassandraPort, int maxBatchSize,
            BatchStatement.Type batchType) {
        this.cassandraQuorum = cassandraQuorum;
        this.cassandraPort = cassandraPort;
        this.maxBatchSize = maxBatchSize;
        this.batchType = batchType;
    }


    public SaveToCassandraActionExecutionFunction(String cassandraQuorum, int cassandraPort, int maxBatchSize,
            BatchStatement.Type batchType, SaveToCassandraOperationsService
            cassandraTableOperationsService) {
       this(cassandraQuorum, cassandraPort, maxBatchSize, batchType);
       this.cassandraTableOperationsService = cassandraTableOperationsService;
    }

    @Override
    public Void call(JavaPairRDD<StreamAction, Iterable<StratioStreamingMessage>> rdd) throws Exception {

        List<Tuple2<StreamAction, Iterable<StratioStreamingMessage>>> rddContent = rdd.collect();
        if (rddContent.size() != 0) {
            process(rddContent.get(0)._2());
        }

        return null;
    }

    @Override
    public Boolean check() throws Exception {

       return getCassandraTableOperationsService().check();
    }

    @Override
    public void process(Iterable<StratioStreamingMessage> messages) throws Exception {

        Integer partitionSize = maxBatchSize;

        if (partitionSize <= 0){
            partitionSize = Iterables.size(messages);
        }

        Iterable<List<StratioStreamingMessage>> partitionIterables =  Iterables.partition(messages, partitionSize);

        try {


            getCassandraTableOperationsService().checkKeyspace();

            for (List<StratioStreamingMessage> messageList : partitionIterables) {

                BatchStatement batch = new BatchStatement(batchType);

                for (StratioStreamingMessage stratioStreamingMessage : messageList) {
                    Set<String> columns = getColumnSet(stratioStreamingMessage.getColumns());
                    if (getCassandraTableOperationsService().getTableNames().get(stratioStreamingMessage.getStreamName())
                            == null) {
                        getCassandraTableOperationsService().createTable(stratioStreamingMessage.getStreamName(),
                                stratioStreamingMessage.getColumns(), TIMESTAMP_FIELD);
                        getCassandraTableOperationsService().refreshTablenames();
                    }
                    if (getCassandraTableOperationsService().getTableNames().get(stratioStreamingMessage.getStreamName()) != columns.hashCode()) {
                        getCassandraTableOperationsService()
                                .alterTable(stratioStreamingMessage.getStreamName(), columns,
                                        stratioStreamingMessage.getColumns());
                        getCassandraTableOperationsService().refreshTablenames();
                    }

                    batch.add(getCassandraTableOperationsService().createInsertStatement(
                            stratioStreamingMessage.getStreamName(), stratioStreamingMessage.getColumns(),
                            TIMESTAMP_FIELD));
                }

                getCassandraTableOperationsService().getSession().execute(batch);
            }

            }catch(Exception e){
                log.error("Error in Cassandra for batch size {}: {}", Iterables.size(partitionIterables), e.getMessage());
            }

    }

    private SaveToCassandraOperationsService getCassandraTableOperationsService() {
        if (cassandraTableOperationsService == null) {
            cassandraTableOperationsService =  (SaveToCassandraOperationsService) ActionBaseContext.getInstance().getContext().getBean
                    ("saveToCassandraOperationsService");

            if (cassandraTableOperationsService.getSession() != null) {
                if (cassandraTableOperationsService.getSession().getCluster().getMetadata().getKeyspace(STREAMING.STREAMING_KEYSPACE_NAME) == null) {
                    cassandraTableOperationsService.createKeyspace(STREAMING.STREAMING_KEYSPACE_NAME);
                }
                cassandraTableOperationsService.refreshTablenames();
            }

        }

        return cassandraTableOperationsService;
    }

    private Set<String> getColumnSet(List<ColumnNameTypeValue> columns) {
        Set<String> columnsSet = new HashSet<>();
        for (ColumnNameTypeValue column : columns) {
            columnsSet.add(column.getColumn());
        }
        columnsSet.add(TIMESTAMP_FIELD);

        return columnsSet;
    }

}
