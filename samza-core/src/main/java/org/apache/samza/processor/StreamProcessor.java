/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.samza.processor;

import org.apache.samza.config.Config;
import org.apache.samza.config.JobCoordinatorConfig;
import org.apache.samza.config.MapConfig;
import org.apache.samza.config.TaskConfigJava;
import org.apache.samza.coordinator.JobCoordinator;
import org.apache.samza.coordinator.JobCoordinatorFactory;
import org.apache.samza.metrics.MetricsReporter;
import org.apache.samza.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * StreamProcessor can be embedded in any application or executed in a distributed environment (aka cluster) as
 * independent processes <br />
 * <p>
 * <b>Usage Example:</b>
 * <pre>
 * StreamProcessor processor = new StreamProcessor(1, config); <br />
 * processor.start();
 * try {
 *  boolean status = processor.awaitStart(TIMEOUT_MS);    // Optional - blocking call
 *  if (!status) {
 *    // Timed out
 *  }
 *  ...
 * } catch (InterruptedException ie) {
 *   ...
 * } finally {
 *   processor.stop();
 * }
 * </pre>
 */
public class StreamProcessor {
  private static final Logger log = LoggerFactory.getLogger(StreamProcessor.class);
  /**
   * processor.id is equivalent to containerId in samza. It is a logical identifier used by Samza for a processor.
   * In a distributed environment, this logical identifier is mapped to a physical identifier of the resource. For
   * example, Yarn provides a "containerId" for every resource it allocates.
   * In an embedded environment, this identifier is provided by the user by directly using the StreamProcessor API.
   * <p>
   * <b>Note:</b>This identifier has to be unique across the instances of StreamProcessors.
   */
  private static final String PROCESSOR_ID = "processor.id";
  private final int processorId;
  private final JobCoordinator jobCoordinator;
  private final SamzaContainerController containerController;

  /**
   * Create an instance of StreamProcessor that encapsulates a JobCoordinator and Samza Container
   * <p>
   * JobCoordinator controls how the various StreamProcessor instances belonging to a job coordinate. It is also
   * responsible generating and updating JobModel.
   * When StreamProcessor starts, it starts the JobCoordinator and brings up a SamzaContainer based on the JobModel.
   * SamzaContainer is executed using an ExecutorService. <br />
   * <p>
   * <b>Note:</b> Lifecycle of the ExecutorService is fully managed by the StreamProcessor, and NOT exposed to the user
   *
   * @param processorId            Unique identifier for a processor within the job. It has the same semantics as
   *                               "containerId" in Samza
   * @param config                 Instance of config object - contains all configuration required for processing
   * @param customMetricsReporters Map of custom MetricReporter instances that are to be injected in the Samza job
   */
  public StreamProcessor(int processorId, Config config, Map<String, MetricsReporter> customMetricsReporters) {
    this(processorId, config, customMetricsReporters, (Object) null);
  }

  private StreamProcessor(int processorId, Config config, Map<String, MetricsReporter> customMetricsReporters,
                          Object taskFactory) {
    this.processorId = processorId;

    Map<String, String> updatedConfigMap = new HashMap<>();
    updatedConfigMap.putAll(config);
    updatedConfigMap.put(PROCESSOR_ID, String.valueOf(processorId));
    Config updatedConfig = new MapConfig(updatedConfigMap);


    this.containerController = new SamzaContainerController(
        taskFactory,
        new TaskConfigJava(updatedConfig).getShutdownMs(),
        customMetricsReporters);

    this.jobCoordinator = Util.
        <JobCoordinatorFactory>getObj(
            new JobCoordinatorConfig(updatedConfig)
                .getJobCoordinatorFactoryClassName())
        .getJobCoordinator(processorId, updatedConfig, this.containerController);
  }

  /**
   * StreamProcessor Lifecycle: start()
   * <ul>
   * <li>Starts the JobCoordinator and fetches the JobModel</li>
   * <li>Starts the container using ContainerModel based on the processorId </li>
   * </ul>
   * When start() returns, it only guarantees that the container is initialized and submitted by the controller to
   * execute
   */
  public void start() {
    jobCoordinator.start();
  }

  /**
   * Method that allows the user to wait for a specified amount of time for the container to initialize and start
   * processing messages
   *
   * @param timeoutMs Maximum time to wait, in milliseconds
   * @return {@code true}, if the container started within the specified wait time and {@code false} if the waiting time
   * elapsed
   * @throws InterruptedException if the current thread is interrupted while waiting for container to start-up
   */
  public boolean awaitStart(long timeoutMs) throws InterruptedException {
    return containerController.awaitStart(timeoutMs); // TODO: Should awaitStart be part of the JC interface, instead of directly using container controller
  }

  /**
   * StreamProcessor Lifecycle: stop()
   * <ul>
   * <li>Stops the SamzaContainer execution</li>
   * <li>Stops the JobCoordinator</li>
   * </ul>
   */
  public void stop() {
    jobCoordinator.stop();
  }
}
