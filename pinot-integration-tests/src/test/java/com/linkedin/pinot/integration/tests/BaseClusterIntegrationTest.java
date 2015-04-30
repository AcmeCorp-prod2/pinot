/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
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
package com.linkedin.pinot.integration.tests;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.util.Utf8;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.pinot.common.utils.StringUtil;
import com.linkedin.pinot.common.utils.TarGzCompressionUtils;
import com.linkedin.pinot.core.indexsegment.generator.SegmentGeneratorConfig;
import com.linkedin.pinot.core.indexsegment.utils.AvroUtils;
import com.linkedin.pinot.core.segment.creator.SegmentIndexCreationDriver;
import com.linkedin.pinot.core.segment.creator.impl.SegmentCreationDriverFactory;
import com.linkedin.pinot.server.util.SegmentTestUtils;
import com.linkedin.pinot.util.TestUtils;


/**
 * TODO Document me!
 *
 * @author jfim
 */
public abstract class BaseClusterIntegrationTest extends ClusterTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(BaseClusterIntegrationTest.class);
  private static final AtomicInteger totalAvroRecordWrittenCount = new AtomicInteger(0);
  private static final boolean BATCH_KAFKA_MESSAGES = true;

  protected Connection _connection;
  protected QueryGenerator _queryGenerator;

  protected abstract int getGeneratedQueryCount();
  protected File queriesFile;

  protected void runNoH2ComparisonQuery(String pqlQuery) throws Exception {
    JSONObject ret = postQuery(pqlQuery);
    ret.put("pql", pqlQuery);
    System.out.println(ret.toString(1));
    Assert.assertEquals(ret.getJSONArray("exceptions").length(), 0);
  }

  protected void runQuery(String pqlQuery, List<String> sqlQueries) throws Exception {
    try {
      Statement statement = _connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

      // Run the query
      JSONObject response = postQuery(pqlQuery);
      JSONArray aggregationResultsArray = response.getJSONArray("aggregationResults");
      JSONObject firstAggregationResult = aggregationResultsArray.getJSONObject(0);
      if (firstAggregationResult.has("value")) {
        LOGGER.info("Trying to execute sql query: " + sqlQueries.get(0));
        statement.execute(sqlQueries.get(0));
        ResultSet rs = statement.getResultSet();
        LOGGER.info("Trying to get result from sql: " + rs);
        // Single value result for the aggregation, compare with the actual value
        String bqlValue = firstAggregationResult.getString("value");

        rs.first();
        String sqlValue = rs.getString(1);

        if (bqlValue != null && sqlValue != null) {
          // Strip decimals
          bqlValue = bqlValue.replaceAll("\\..*", "");
          sqlValue = sqlValue.replaceAll("\\..*", "");

          LOGGER.info("bql value: " + bqlValue);
          LOGGER.info("sql value: " + sqlValue);
          Assert.assertEquals(bqlValue, sqlValue, "Values did not match for query " + pqlQuery);
        } else {
          Assert.assertEquals(bqlValue, sqlValue, "Values did not match for query " + pqlQuery);
        }
      } else if (firstAggregationResult.has("groupByResult")) {
        // Load values from the query result
        for (int aggregationGroupIndex = 0; aggregationGroupIndex < aggregationResultsArray.length(); aggregationGroupIndex++) {
          JSONArray groupByResults = aggregationResultsArray.getJSONObject(aggregationGroupIndex).getJSONArray("groupByResult");
          if (groupByResults.length() != 0) {
            int groupKeyCount = groupByResults.getJSONObject(0).getJSONArray("group").length();

            Map<String, String> actualValues = new HashMap<String, String>();
            for (int resultIndex = 0; resultIndex < groupByResults.length(); ++resultIndex) {
              JSONArray group = groupByResults.getJSONObject(resultIndex).getJSONArray("group");
              String pinotGroupKey = "";
              for (int groupKeyIndex = 0; groupKeyIndex < groupKeyCount; groupKeyIndex++) {
                pinotGroupKey += group.getString(groupKeyIndex) + "\t";
              }

              actualValues.put(pinotGroupKey, Integer.toString((int) Double.parseDouble(groupByResults.getJSONObject(resultIndex).getString("value"))));
            }

            // Grouped result, build correct values and iterate through to compare both
            Map<String, String> correctValues = new HashMap<String, String>();
            LOGGER.info("Trying to execute sql query: " + sqlQueries.get(aggregationGroupIndex));
            statement.execute(sqlQueries.get(aggregationGroupIndex));
            ResultSet rs = statement.getResultSet();
            LOGGER.info("Trying to get result from sql: " + rs);
            rs.beforeFirst();
            while (rs.next()) {
              String h2GroupKey = "";
              for (int groupKeyIndex = 0; groupKeyIndex < groupKeyCount; groupKeyIndex++) {
                h2GroupKey += rs.getString(groupKeyIndex + 1) + "\t";
              }
              correctValues.put(h2GroupKey, rs.getString(groupKeyCount + 1));
            }
            LOGGER.info("Trying to compare result from bql: " + actualValues);
            LOGGER.info("Trying to compare result from sql: " + correctValues);
            Assert.assertEquals(actualValues, correctValues, "Values did not match while running query : " + pqlQuery + ", sql query: " + sqlQueries.get(aggregationGroupIndex));
          } else {
            // No records in group by, check that the result set is empty
            statement.execute(sqlQueries.get(aggregationGroupIndex));
            ResultSet rs = statement.getResultSet();
            Assert.assertTrue(rs.isLast(), "Pinot did not return any results while results were expected for query " + pqlQuery);
          }

        }
      }
    } catch (JSONException exception) {
      Assert.fail("Query did not return valid JSON while running query " + pqlQuery);
    }
    System.out.println();
  }

  public static void createH2SchemaAndInsertAvroFiles(List<File> avroFiles, Connection connection) {
    try {
      File schemaAvroFile = avroFiles.get(0);
      DatumReader<GenericRecord> datumReader = new GenericDatumReader<GenericRecord>();
      DataFileReader<GenericRecord> dataFileReader = new DataFileReader<GenericRecord>(schemaAvroFile, datumReader);

      Schema schema = dataFileReader.getSchema();
      List<Schema.Field> fields = schema.getFields();
      List<String> columnNamesAndTypes = new ArrayList<String>(fields.size());
      for (Schema.Field field : fields) {
        try {
          List<Schema> types = field.schema().getTypes();
          String columnNameAndType;
          if (types.size() == 1) {
            columnNameAndType = field.name() + " " + types.get(0).getName() + " not null";
          } else {
            columnNameAndType = field.name() + " " + types.get(0).getName();
          }

          columnNamesAndTypes.add(columnNameAndType.replace("string", "varchar(128)"));
        } catch (Exception e) {
          // Happens if the field is not a union, skip the field
        }
      }

      connection.prepareCall("create table mytable(" + StringUtil.join(",", columnNamesAndTypes.toArray(new String[columnNamesAndTypes.size()])) + ")").execute();
      long start = System.currentTimeMillis();
      StringBuilder params = new StringBuilder("?");
      for (int i = 0; i < columnNamesAndTypes.size() - 1; i++) {
        params.append(",?");
      }
      PreparedStatement statement =
          connection.prepareStatement("INSERT INTO mytable VALUES (" + params.toString() + ")");

      dataFileReader.close();

      for (File avroFile : avroFiles) {
        datumReader = new GenericDatumReader<GenericRecord>();
        dataFileReader = new DataFileReader<GenericRecord>(avroFile, datumReader);
        GenericRecord record = null;
        while (dataFileReader.hasNext()) {
          record = dataFileReader.next(record);
          for (int i = 0; i < columnNamesAndTypes.size(); i++) {
            Object value = record.get(i);
            if (value instanceof Utf8) {
              value = value.toString();
            }
            statement.setObject(i + 1, value);
          }
          statement.execute();
        }
        dataFileReader.close();
      }
      System.out.println("Insertion took " + (System.currentTimeMillis() - start));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void pushAvroIntoKafka(List<File> avroFiles, String kafkaBroker, String kafkaTopic) {
    Properties properties = new Properties();
    properties.put("metadata.broker.list", kafkaBroker);
    properties.put("serializer.class", "kafka.serializer.DefaultEncoder");
    properties.put("request.required.acks", "1");

    ProducerConfig producerConfig = new ProducerConfig(properties);
    Producer<String, byte[]> producer = new Producer<String, byte[]>(producerConfig);
    for (File avroFile : avroFiles) {
      try {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(65536);
        DataFileStream<GenericRecord> reader = AvroUtils.getAvroReader(avroFile);
        BinaryEncoder binaryEncoder = new EncoderFactory().directBinaryEncoder(outputStream, null);
        GenericDatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<GenericRecord>(reader.getSchema());
        int recordCount = 0;
        List<KeyedMessage<String, byte[]>> messagesToWrite = new ArrayList<KeyedMessage<String, byte[]>>(10000);
        for (GenericRecord genericRecord : reader) {
          outputStream.reset();
          datumWriter.write(genericRecord, binaryEncoder);
          binaryEncoder.flush();

          byte[] bytes = outputStream.toByteArray();
          KeyedMessage<String, byte[]> data = new KeyedMessage<String, byte[]>(kafkaTopic, bytes);

          if (BATCH_KAFKA_MESSAGES) {
            messagesToWrite.add(data);
          } else {
            producer.send(data);
          }
          recordCount += 1;
        }

        if (BATCH_KAFKA_MESSAGES) {
          producer.send(messagesToWrite);
        }

        outputStream.close();
        reader.close();
        LOGGER.info("Finished writing " + recordCount + " records from " + avroFile.getName() + " into Kafka topic "
            + kafkaTopic);
        int totalRecordCount = totalAvroRecordWrittenCount.addAndGet(recordCount);
        LOGGER.info("Total records written so far " + totalRecordCount);
      } catch (Exception e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }
    }
  }

  public static void buildSegmentsFromAvro(final List<File> avroFiles, Executor executor, int baseSegmentIndex,
      final File baseDirectory, final File segmentTarDir, final String resourceName, final String tableName) {
    int segmentCount = avroFiles.size();
    System.out.println("Building " + segmentCount + " segments in parallel");
    for (int i = 1; i <= segmentCount; ++i) {
      final int segmentIndex = i - 1;
      final int segmentNumber = i + baseSegmentIndex;

      executor.execute(new Runnable() {
        @Override
        public void run() {
          try {
            // Build segment
            System.out.println("Starting to build segment " + segmentNumber);
            File outputDir = new File(baseDirectory, "segment-" + segmentNumber);
            final SegmentGeneratorConfig genConfig =
                SegmentTestUtils
                    .getSegmentGenSpecWithSchemAndProjectedColumns(avroFiles.get(segmentIndex), outputDir,
                        TimeUnit.DAYS, resourceName, tableName);

            genConfig.setSegmentNamePostfix(Integer.toString(segmentNumber));

            final SegmentIndexCreationDriver driver = SegmentCreationDriverFactory.get(null);
            driver.init(genConfig);
            driver.build();

            // Tar segment
            String segmentName = outputDir.list()[0];
            TarGzCompressionUtils
                .createTarGzOfDirectory(outputDir.getAbsolutePath() + "/" + segmentName,
                    new File(segmentTarDir, segmentName).getAbsolutePath());
            System.out.println("Completed segment " + segmentNumber + " : " + segmentName);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      });
    }
  }

  public void testMultipleQueries() throws Exception {
    queriesFile = new File(TestUtils.getFileFromResourceUrl(BaseClusterIntegrationTest.class.getClassLoader().getResource(
        "On_Time_On_Time_Performance_2014_100k_subset.test_queries_10K")));

    Scanner scanner = new Scanner(queriesFile);
    scanner.useDelimiter("\n");
    String[] pqls = new String[1000];

    for (int i = 0; i < pqls.length; i++) {
      JSONObject test_case = new JSONObject(scanner.next());
      pqls[i] = test_case.getString("pql");
    }

    for (String query : pqls) {
      try {
        runNoH2ComparisonQuery(query);
      } catch (Exception e) {
        System.out.println("pql is : " + query);
        throw new RuntimeException(e.getMessage());
      }
    }
  }

  @Test
  public void testHardcodedQuerySet() throws Exception {
    for (String query : getHardCodedQuerySet()) {
      try {
        System.out.println(query);
        runQuery(query, Collections.singletonList(query.replace("'myresource.mytable'", "mytable")));
      } catch (Exception e) {
        // TODO: handle exception
      }

    }
  }

  protected String[] getHardCodedQuerySet() {
    String[] queries = new String[] {
        "select count(*) from 'myresource.mytable'",
        "select sum(DepDelay) from 'myresource.mytable'",
        // "select count(DepDelay) from 'myresource.mytable'",
        "select min(DepDelay) from 'myresource.mytable'",
        "select max(DepDelay) from 'myresource.mytable'",
        "select avg(DepDelay) from 'myresource.mytable'",
        "select Carrier, count(*) from 'myresource.mytable' group by Carrier TOP 100",
        "select Carrier, count(*) from 'myresource.mytable' where ArrDelay > 15 group by Carrier TOP 100",
        "select Carrier, count(*) from 'myresource.mytable' where Cancelled = 1 group by Carrier TOP 100",
        "select Carrier, count(*) from 'myresource.mytable' where DepDelay >= 15 group by Carrier TOP 100",
        "select Carrier, count(*) from 'myresource.mytable' where DepDelay < 15 group by Carrier TOP 100",
        "select Carrier, count(*) from 'myresource.mytable' where ArrDelay <= 15 group by Carrier TOP 100",
        "select Carrier, count(*) from 'myresource.mytable' where DepDelay >= 15 or ArrDelay >= 15 group by Carrier TOP 100",
        "select Carrier, count(*) from 'myresource.mytable' where DepDelay < 15 and ArrDelay <= 15 group by Carrier TOP 100",
        "select Carrier, count(*) from 'myresource.mytable' where DepDelay between 5 and 15 group by Carrier TOP 100",
        "select Carrier, count(*) from 'myresource.mytable' where DepDelay in (2, 8, 42) group by Carrier TOP 100",
        "select Carrier, count(*) from 'myresource.mytable' where DepDelay not in (4, 16) group by Carrier TOP 100",
        "select Carrier, count(*) from 'myresource.mytable' where Cancelled <> 1 group by Carrier TOP 100",
        "select Carrier, min(ArrDelay) from 'myresource.mytable' group by Carrier TOP 100",
        "select Carrier, max(ArrDelay) from 'myresource.mytable' group by Carrier TOP 100",
        "select Carrier, sum(ArrDelay) from 'myresource.mytable' group by Carrier TOP 100",
        "select TailNum, avg(ArrDelay) from 'myresource.mytable' group by TailNum TOP 100",
        "select FlightNum, avg(ArrDelay) from 'myresource.mytable' group by FlightNum TOP 100",
        // "select distinctCount(Carrier) from 'myresource.mytable' where TailNum = 'D942DN' TOP 100"
    };
    return queries;
  }

  protected long getCurrentServingNumDocs() {
    try {
      String countQuery = "SELECT count(*) FROM 'myresource.mytable' LIMIT 0";
      JSONObject ret = postQuery(countQuery);
      ret.put("pql", countQuery);
      System.out.println(ret.toString(1));
      return ret.getLong("numDocsScanned");
    } catch (Exception e) {
      return 0;
    }
  }

}
