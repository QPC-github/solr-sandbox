/*
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
package org.apache.solr.crossdc.common;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.apache.solr.crossdc.common.KafkaCrossDcConf.SLOW_SUBMIT_THRESHOLD_MS;

public class KafkaMirroringSink implements RequestMirroringSink, Closeable {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final KafkaCrossDcConf conf;
    private final Producer<String, MirroredSolrRequest> producer;

    public KafkaMirroringSink(final KafkaCrossDcConf conf) {
        // Create Kafka Mirroring Sink
        this.conf = conf;
        this.producer = initProducer();
    }

    @Override
    public void submit(MirroredSolrRequest request) throws MirroringException {
        if (log.isDebugEnabled()) {
            log.debug("About to submit a MirroredSolrRequest");
        }

        final long enqueueStartNanos = System.nanoTime();

        // Create Producer record
        try {

            producer.send(new ProducerRecord<>(conf.get(KafkaCrossDcConf.TOPIC_NAME), request), (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed adding update to CrossDC queue! request=" + request.getSolrRequest(), exception);
                }
            });

            long lastSuccessfulEnqueueNanos = System.nanoTime();
            // Record time since last successful enqueue as 0
            long elapsedTimeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - enqueueStartNanos);
            // Update elapsed time

            if (elapsedTimeMillis > conf.getInt(SLOW_SUBMIT_THRESHOLD_MS)) {
                slowSubmitAction(request, elapsedTimeMillis);
            }
        } catch (Exception e) {
            // We are intentionally catching all exceptions, the expected exception form this function is {@link MirroringException}
            String message = "Unable to enqueue request " + request + ", configured retries is" + conf.getInt(KafkaCrossDcConf.NUM_RETRIES) +
                " and configured max delivery timeout in ms is " + conf.getInt(KafkaCrossDcConf.DELIVERY_TIMEOUT_MS);
            log.error(message, e);
            throw new MirroringException(message, e);
        }
    }

    /**
     * Create and init the producer using {@link this#conf}
     * All producer configs are listed here
     * https://kafka.apache.org/documentation/#producerconfigs
     *
     * @return
     */
    private Producer<String, MirroredSolrRequest> initProducer() {
        // Initialize and return Kafka producer
        Properties kafkaProducerProps = new Properties();

        log.info("Starting CrossDC Producer {}", conf);

        kafkaProducerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, conf.get(KafkaCrossDcConf.BOOTSTRAP_SERVERS));

        kafkaProducerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        String retries = conf.get(KafkaCrossDcConf.NUM_RETRIES);
        if (retries != null) {
            kafkaProducerProps.put(ProducerConfig.RETRIES_CONFIG, Integer.parseInt(retries));
        }
        kafkaProducerProps.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, conf.getInt(KafkaCrossDcConf.RETRY_BACKOFF_MS));
        kafkaProducerProps.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, conf.getInt(KafkaCrossDcConf.MAX_REQUEST_SIZE_BYTES));
        kafkaProducerProps.put(ProducerConfig.BATCH_SIZE_CONFIG, conf.getInt(KafkaCrossDcConf.BATCH_SIZE_BYTES));
        kafkaProducerProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, conf.getInt(KafkaCrossDcConf.BUFFER_MEMORY_BYTES));
        kafkaProducerProps.put(ProducerConfig.LINGER_MS_CONFIG, conf.getInt(KafkaCrossDcConf.LINGER_MS));
        kafkaProducerProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, conf.getInt(KafkaCrossDcConf.REQUEST_TIMEOUT_MS)); // should be less than time that causes consumer to be kicked out
        kafkaProducerProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, conf.get(KafkaCrossDcConf.ENABLE_DATA_COMPRESSION));

        kafkaProducerProps.put("key.serializer", StringSerializer.class.getName());
        kafkaProducerProps.put("value.serializer", MirroredSolrRequestSerializer.class.getName());

        KafkaCrossDcConf.addSecurityProps(conf, kafkaProducerProps);

        kafkaProducerProps.putAll(conf.getAdditionalProperties());

        if (log.isDebugEnabled()) {
            log.debug("Kafka Producer props={}", kafkaProducerProps);
        }

        ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(null);
        Producer<String, MirroredSolrRequest> producer;
        try {
            producer = new KafkaProducer<>(kafkaProducerProps);
        } finally {
            Thread.currentThread().setContextClassLoader(originalContextClassLoader);
        }
        return producer;
    }

    private void slowSubmitAction(Object request, long elapsedTimeMillis) {
        log.warn("Enqueuing the request to Kafka took more than {} millis. enqueueElapsedTime={}",
                conf.get(KafkaCrossDcConf.SLOW_SUBMIT_THRESHOLD_MS),
                elapsedTimeMillis);
    }

    @Override public void close() throws IOException {
        if (producer != null) {
            producer.flush();
            producer.close();
        }
    }
}
