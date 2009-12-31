/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
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

package org.apache.mahout.classifier.bayes.mapreduce.cbayes;

import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DefaultStringifier;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.util.GenericsUtil;
import org.apache.mahout.classifier.bayes.common.BayesParameters;
import org.apache.mahout.classifier.bayes.io.SequenceFileModelReader;
import org.apache.mahout.classifier.bayes.mapreduce.common.BayesJob;
import org.apache.mahout.common.StringTuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/** Create and run the Bayes Trainer. */
public class CBayesThetaNormalizerDriver implements BayesJob {

  private static final Logger log = LoggerFactory.getLogger(CBayesThetaNormalizerDriver.class);

  /**
   * Run the job
   *
   * @param input  the input pathname String
   * @param output the output pathname String
   */
  @Override
  public void runJob(String input, String output, BayesParameters params) throws IOException {
    Configurable client = new JobClient();
    JobConf conf = new JobConf(CBayesThetaNormalizerDriver.class);
    conf.setJobName("Complementary Bayes Theta Normalizer Driver running over input: " +  input);


    conf.setOutputKeyClass(StringTuple.class);
    conf.setOutputValueClass(DoubleWritable.class);
    FileInputFormat.addInputPath(conf, new Path(output + "/trainer-weights/Sigma_j"));
    FileInputFormat.addInputPath(conf, new Path(output + "/trainer-tfIdf/trainer-tfIdf"));
    Path outPath = new Path(output + "/trainer-thetaNormalizer");
    FileOutputFormat.setOutputPath(conf, outPath);
    //conf.setNumMapTasks(100);
    //conf.setNumReduceTasks(1);
    conf.setMapperClass(CBayesThetaNormalizerMapper.class);
    conf.setInputFormat(SequenceFileInputFormat.class);
    conf.setCombinerClass(CBayesThetaNormalizerReducer.class);
    conf.setReducerClass(CBayesThetaNormalizerReducer.class);
    conf.setOutputFormat(SequenceFileOutputFormat.class);
    conf.set("io.serializations",
        "org.apache.hadoop.io.serializer.JavaSerialization,org.apache.hadoop.io.serializer.WritableSerialization");
    // Dont ever forget this. People should keep track of how hadoop conf parameters and make or break a piece of code

    FileSystem dfs = FileSystem.get(outPath.toUri(), conf);
    if (dfs.exists(outPath)) {
      dfs.delete(outPath, true);
    }

    Path Sigma_kFiles = new Path(output + "/trainer-weights/Sigma_k/*");
    Map<String, Double> labelWeightSum = SequenceFileModelReader.readLabelSums(dfs, Sigma_kFiles, conf);
    DefaultStringifier<Map<String, Double>> mapStringifier =
        new DefaultStringifier<Map<String, Double>>(conf, GenericsUtil.getClass(labelWeightSum));
    String labelWeightSumString = mapStringifier.toString(labelWeightSum);

    log.info("Sigma_k for Each Label");
    Map<String, Double> c = mapStringifier.fromString(labelWeightSumString);
    log.info("{}", c);
    conf.set("cnaivebayes.sigma_k", labelWeightSumString);


    Path sigma_kSigma_jFile = new Path(output + "/trainer-weights/Sigma_kSigma_j/*");
    double sigma_jSigma_k = SequenceFileModelReader.readSigma_jSigma_k(dfs, sigma_kSigma_jFile, conf);
    DefaultStringifier<Double> stringifier = new DefaultStringifier<Double>(conf, Double.class);
    String sigma_jSigma_kString = stringifier.toString(sigma_jSigma_k);

    log.info("Sigma_kSigma_j for each Label and for each Features");
    double retSigma_jSigma_k = stringifier.fromString(sigma_jSigma_kString);
    log.info("{}", retSigma_jSigma_k);
    conf.set("cnaivebayes.sigma_jSigma_k", sigma_jSigma_kString);

    Path vocabCountFile = new Path(output + "/trainer-tfIdf/trainer-vocabCount/*");
    double vocabCount = SequenceFileModelReader.readVocabCount(dfs, vocabCountFile, conf);
    String vocabCountString = stringifier.toString(vocabCount);
    
    log.info("Vocabulary Count");
    conf.set("cnaivebayes.vocabCount", vocabCountString);
    double retvocabCount = stringifier.fromString(vocabCountString);
    log.info("{}", retvocabCount);
    conf.set("bayes.parameters", params.toString());
    conf.set("output.table", output);
    client.setConf(conf);

    JobClient.runJob(conf);

  }
}