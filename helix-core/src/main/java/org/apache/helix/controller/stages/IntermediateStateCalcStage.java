package org.apache.helix.controller.stages;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.helix.HelixDefinedState;
import org.apache.helix.HelixException;
import org.apache.helix.HelixManager;
import org.apache.helix.api.config.StateTransitionThrottleConfig;
import org.apache.helix.api.config.StateTransitionThrottleConfig.RebalanceType;
import org.apache.helix.controller.LogUtil;
import org.apache.helix.controller.common.PartitionStateMap;
import org.apache.helix.controller.dataproviders.ResourceControllerDataProvider;
import org.apache.helix.controller.pipeline.AbstractBaseStage;
import org.apache.helix.controller.pipeline.StageException;
import org.apache.helix.model.BuiltInStateModelDefinitions;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.MaintenanceSignal;
import org.apache.helix.model.Message;
import org.apache.helix.model.Partition;
import org.apache.helix.model.Resource;
import org.apache.helix.model.StateModelDefinition;
import org.apache.helix.monitoring.mbeans.ClusterStatusMonitor;
import org.apache.helix.monitoring.mbeans.ResourceMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For partition compute the Intermediate State (instance,state) pair based on the BestPossibleState
 * and CurrentState, with all constraints applied (such as state transition throttling).
 */
public class IntermediateStateCalcStage extends AbstractBaseStage {
  private static final Logger logger =
      LoggerFactory.getLogger(IntermediateStateCalcStage.class.getName());

  @Override
  public void process(ClusterEvent event) throws Exception {
    _eventId = event.getEventId();
    CurrentStateOutput currentStateOutput = event.getAttribute(AttributeName.CURRENT_STATE.name());

    BestPossibleStateOutput bestPossibleStateOutput =
        event.getAttribute(AttributeName.BEST_POSSIBLE_STATE.name());
    Map<String, Resource> resourceToRebalance =
        event.getAttribute(AttributeName.RESOURCES_TO_REBALANCE.name());
    ResourceControllerDataProvider cache =
        event.getAttribute(AttributeName.ControllerDataProvider.name());

    if (currentStateOutput == null || bestPossibleStateOutput == null || resourceToRebalance == null
        || cache == null) {
      throw new StageException(String.format("Missing attributes in event: %s. "
          + "Requires CURRENT_STATE (%s) |BEST_POSSIBLE_STATE (%s) |RESOURCES (%s) |DataCache (%s)",
          event, currentStateOutput, bestPossibleStateOutput, resourceToRebalance, cache));
    }

    IntermediateStateOutput intermediateStateOutput =
        compute(event, resourceToRebalance, currentStateOutput, bestPossibleStateOutput);
    event.addAttribute(AttributeName.INTERMEDIATE_STATE.name(), intermediateStateOutput);

    // Make sure no instance has more replicas/partitions assigned than maxPartitionPerInstance. If
    // it does, pause the rebalance and put the cluster on maintenance mode
    int maxPartitionPerInstance = cache.getClusterConfig().getMaxPartitionsPerInstance();
    if (maxPartitionPerInstance > 0) {
      validateMaxPartitionsPerInstance(event, cache, intermediateStateOutput,
          maxPartitionPerInstance);
    }
  }

  /**
   * Go through each resource, and based on BestPossibleState and CurrentState, compute
   * IntermediateState as close to BestPossibleState while maintaining throttling constraints (for
   * example, ensure that the number of possible pending state transitions does NOT go over the set
   * threshold).
   * @param event
   * @param resourceMap
   * @param currentStateOutput
   * @param bestPossibleStateOutput
   * @return
   */
  private IntermediateStateOutput compute(ClusterEvent event, Map<String, Resource> resourceMap,
      CurrentStateOutput currentStateOutput, BestPossibleStateOutput bestPossibleStateOutput) {
    IntermediateStateOutput output = new IntermediateStateOutput();
    ResourceControllerDataProvider dataCache =
        event.getAttribute(AttributeName.ControllerDataProvider.name());

    StateTransitionThrottleController throttleController = new StateTransitionThrottleController(
        resourceMap.keySet(), dataCache.getClusterConfig(), dataCache.getLiveInstances().keySet());

    // Resource level prioritization based on the numerical (sortable) priority field.
    // If the resource priority field is null/not set, the resource will be treated as lowest
    // priority.
    List<ResourcePriority> prioritizedResourceList = new ArrayList<>();
    for (String resourceName : resourceMap.keySet()) {
      prioritizedResourceList.add(new ResourcePriority(resourceName, Integer.MIN_VALUE));
    }
    // If resourcePriorityField is null at the cluster level, all resources will be considered equal
    // in priority by keeping all priorities at MIN_VALUE
    if (dataCache.getClusterConfig().getResourcePriorityField() != null) {
      String priorityField = dataCache.getClusterConfig().getResourcePriorityField();
      for (ResourcePriority resourcePriority : prioritizedResourceList) {
        String resourceName = resourcePriority.getResourceName();

        // Will take the priority from ResourceConfig first
        // If ResourceConfig does not exist or does not have this field.
        // Try to load it from the resource's IdealState. Otherwise, keep it at the lowest priority
        if (dataCache.getResourceConfig(resourceName) != null
            && dataCache.getResourceConfig(resourceName).getSimpleConfig(priorityField) != null) {
          resourcePriority.setPriority(
              dataCache.getResourceConfig(resourceName).getSimpleConfig(priorityField));
        } else if (dataCache.getIdealState(resourceName) != null && dataCache
            .getIdealState(resourceName).getRecord().getSimpleField(priorityField) != null) {
          resourcePriority.setPriority(
              dataCache.getIdealState(resourceName).getRecord().getSimpleField(priorityField));
        }
      }
      prioritizedResourceList.sort(new ResourcePriorityComparator());
    }

    ClusterStatusMonitor clusterStatusMonitor =
        event.getAttribute(AttributeName.clusterStatusMonitor.name());
    List<String> failedResources = new ArrayList<>();

    // Priority is applied in assignment computation because higher priority by looping in order of
    // decreasing priority
    for (ResourcePriority resourcePriority : prioritizedResourceList) {
      String resourceName = resourcePriority.getResourceName();

      if (!bestPossibleStateOutput.containsResource(resourceName)) {
        LogUtil.logInfo(logger, _eventId, String.format(
            "Skip calculating intermediate state for resource %s because the best possible state is not available.",
            resourceName));
        continue;
      }

      Resource resource = resourceMap.get(resourceName);
      IdealState idealState = dataCache.getIdealState(resourceName);
      if (idealState == null) {
        // If IdealState is null, use an empty one
        LogUtil.logInfo(logger, _eventId,
            String.format(
                "IdealState for resource %s does not exist; resource may not exist anymore",
                resourceName));
        idealState = new IdealState(resourceName);
        idealState.setStateModelDefRef(resource.getStateModelDefRef());
      }

      try {
        output.setState(resourceName,
            computeIntermediatePartitionState(dataCache, clusterStatusMonitor, idealState,
                resourceMap.get(resourceName), currentStateOutput,
                bestPossibleStateOutput.getPartitionStateMap(resourceName),
                bestPossibleStateOutput.getPreferenceLists(resourceName), throttleController));
      } catch (HelixException ex) {
        LogUtil.logInfo(logger, _eventId,
            "Failed to calculate intermediate partition states for resource " + resourceName, ex);
        failedResources.add(resourceName);
      }
    }

    if (clusterStatusMonitor != null) {
      clusterStatusMonitor.setResourceRebalanceStates(failedResources,
          ResourceMonitor.RebalanceStatus.INTERMEDIATE_STATE_CAL_FAILED);
      clusterStatusMonitor.setResourceRebalanceStates(output.resourceSet(),
          ResourceMonitor.RebalanceStatus.NORMAL);
    }

    return output;
  }

  /**
   * Go through every instance in the assignment and check that each instance does NOT have more
   * replicas for partitions assigned to it than maxPartitionsPerInstance. If the assignment
   * violates this, put the cluster on maintenance mode.
   * This logic could be integrated with compute() for IntermediateState calculation but appended
   * separately for visibility and testing. Additionally, performing validation after compute()
   * ensures that we have a full intermediate state mapping complete prior to validation.
   * @param event
   * @param cache
   * @param intermediateStateOutput
   * @param maxPartitionPerInstance
   */
  private void validateMaxPartitionsPerInstance(ClusterEvent event,
      ResourceControllerDataProvider cache, IntermediateStateOutput intermediateStateOutput,
      int maxPartitionPerInstance) {
    Map<String, PartitionStateMap> resourceStatesMap =
        intermediateStateOutput.getResourceStatesMap();
    Map<String, Integer> instancePartitionCounts = new HashMap<>();

    for (String resource : resourceStatesMap.keySet()) {
      IdealState idealState = cache.getIdealState(resource);
      if (idealState != null
          && idealState.getStateModelDefRef().equals(BuiltInStateModelDefinitions.Task.name())) {
        // Ignore task here. Task has its own throttling logic
        continue;
      }

      PartitionStateMap partitionStateMap = resourceStatesMap.get(resource);
      Map<Partition, Map<String, String>> stateMaps = partitionStateMap.getStateMap();
      for (Partition p : stateMaps.keySet()) {
        Map<String, String> stateMap = stateMaps.get(p);
        for (String instance : stateMap.keySet()) {
          // If this replica is in DROPPED state, do not count it in the partition count since it is
          // to be dropped
          String state = stateMap.get(instance);
          if (state.equals(HelixDefinedState.DROPPED.name())) {
            continue;
          }

          if (!instancePartitionCounts.containsKey(instance)) {
            instancePartitionCounts.put(instance, 0);
          }
          int partitionCount = instancePartitionCounts.get(instance); // Number of replicas (from
          // different partitions) held
          // in this instance
          partitionCount++;
          if (partitionCount > maxPartitionPerInstance) {
            HelixManager manager = event.getAttribute(AttributeName.helixmanager.name());
            String errMsg = String.format(
                "Problem: according to this assignment, instance %s contains more "
                    + "replicas/partitions than the maximum number allowed (%d). Pipeline will "
                    + "stop the rebalance and put the cluster %s into maintenance mode",
                instance, maxPartitionPerInstance, cache.getClusterName());
            if (manager != null) {
              if (manager.getHelixDataAccessor()
                  .getProperty(manager.getHelixDataAccessor().keyBuilder().maintenance()) == null) {
                manager.getClusterManagmentTool().autoEnableMaintenanceMode(
                    manager.getClusterName(), true, errMsg,
                    MaintenanceSignal.AutoTriggerReason.MAX_PARTITION_PER_INSTANCE_EXCEEDED);
              }
              LogUtil.logWarn(logger, _eventId, errMsg);
            } else {
              LogUtil.logError(logger, _eventId,
                  "HelixManager is not set/null! Failed to pause this cluster/enable maintenance"
                      + " mode due to an instance being assigned more replicas/partitions than "
                      + "the limit.");
            }

            ClusterStatusMonitor clusterStatusMonitor =
                event.getAttribute(AttributeName.clusterStatusMonitor.name());
            if (clusterStatusMonitor != null) {
              clusterStatusMonitor.setResourceRebalanceStates(Collections.singletonList(resource),
                  ResourceMonitor.RebalanceStatus.INTERMEDIATE_STATE_CAL_FAILED);
            }
            // Throw an exception here so that messages won't be sent out based on this mapping
            throw new HelixException(errMsg);
          }
          instancePartitionCounts.put(instance, partitionCount);
        }
      }
    }
  }

  /**
   * Compute intermediate partition states for a prioritized resource.
   * @param cache
   * @param clusterStatusMonitor
   * @param idealState
   * @param resource
   * @param currentStateOutput
   * @param bestPossiblePartitionStateMap
   * @param preferenceLists
   * @param throttleController
   * @return
   */
  private PartitionStateMap computeIntermediatePartitionState(ResourceControllerDataProvider cache,
      ClusterStatusMonitor clusterStatusMonitor, IdealState idealState, Resource resource,
      CurrentStateOutput currentStateOutput, PartitionStateMap bestPossiblePartitionStateMap,
      Map<String, List<String>> preferenceLists,
      StateTransitionThrottleController throttleController) {
    String resourceName = resource.getResourceName();
    LogUtil.logDebug(logger, _eventId, String.format("Processing resource: %s", resourceName));

    // Throttling is applied only on FULL-AUTO mode
    if (!throttleController.isThrottleEnabled()
        || !IdealState.RebalanceMode.FULL_AUTO.equals(idealState.getRebalanceMode())) {
      return bestPossiblePartitionStateMap;
    }

    String stateModelDefName = idealState.getStateModelDefRef();
    StateModelDefinition stateModelDef = cache.getStateModelDef(stateModelDefName);
    PartitionStateMap intermediatePartitionStateMap = new PartitionStateMap(resourceName);

    Set<Partition> partitionsNeedRecovery = new HashSet<>();
    Set<Partition> partitionsNeedLoadBalance = new HashSet<>();
    Set<Partition> partitionsWithErrorStateReplica = new HashSet<>();
    for (Partition partition : resource.getPartitions()) {
      Map<String, String> currentStateMap =
          currentStateOutput.getCurrentStateMap(resourceName, partition);
      Map<String, String> bestPossibleMap =
          bestPossiblePartitionStateMap.getPartitionMap(partition);
      List<String> preferenceList = preferenceLists.get(partition.getPartitionName());

      RebalanceType rebalanceType = getRebalanceType(cache, bestPossibleMap, preferenceList,
          stateModelDef, currentStateMap, idealState, partition.getPartitionName());

      // TODO: refine getRebalanceType to return more accurate rebalance types. So the following
      // logic doesn't need to check for more details.
      boolean isRebalanceNeeded = false;

      // Check whether partition has any ERROR state replicas
      if (currentStateMap.values().contains(HelixDefinedState.ERROR.name())) {
        partitionsWithErrorStateReplica.add(partition);
      }

      // Number of states required by StateModelDefinition are not satisfied, need recovery
      if (rebalanceType.equals(RebalanceType.RECOVERY_BALANCE)) {
        // Check if recovery is needed for this partition
        if (!currentStateMap.equals(bestPossibleMap)) {
          partitionsNeedRecovery.add(partition);
          isRebalanceNeeded = true;
        }
      } else if (rebalanceType.equals(RebalanceType.LOAD_BALANCE)) {
        // Number of states required by StateModelDefinition are satisfied, but to achieve
        // BestPossibleState, need load balance
        partitionsNeedLoadBalance.add(partition);
        isRebalanceNeeded = true;
      }

      // Currently at BestPossibleState, no further action necessary
      if (!isRebalanceNeeded) {
        Map<String, String> intermediateMap = new HashMap<>(bestPossibleMap);
        intermediatePartitionStateMap.setState(partition, intermediateMap);
      }
    }

    if (!partitionsNeedRecovery.isEmpty()) {
      LogUtil.logInfo(logger, _eventId, String.format(
          "Recovery balance needed for %s partitions: %s", resourceName, partitionsNeedRecovery));
    }
    if (!partitionsNeedLoadBalance.isEmpty()) {
      LogUtil.logInfo(logger, _eventId, String.format("Load balance needed for %s partitions: %s",
          resourceName, partitionsNeedLoadBalance));
    }
    if (!partitionsWithErrorStateReplica.isEmpty()) {
      LogUtil.logInfo(logger, _eventId,
          String.format("Partition currently has an ERROR replica in %s partitions: %s",
              resourceName, partitionsWithErrorStateReplica));
    }

    chargePendingTransition(resource, currentStateOutput, throttleController, cache, preferenceLists, stateModelDef, intermediatePartitionStateMap);

    // Perform recovery balance
    Set<Partition> recoveryThrottledPartitions =
        recoveryRebalance(resource, bestPossiblePartitionStateMap, throttleController,
            intermediatePartitionStateMap, partitionsNeedRecovery, currentStateOutput,
            cache.getStateModelDef(resource.getStateModelDefRef()).getTopState(), cache);

    // Perform load balance upon checking conditions below
    Set<Partition> loadbalanceThrottledPartitions;
    ClusterConfig clusterConfig = cache.getClusterConfig();

    // If the threshold (ErrorOrRecovery) is set, then use it, if not, then check if the old
    // threshold (Error) is set. If the old threshold is set, use it. If not, use the default value
    // for the new one. This is for backward-compatibility
    int threshold = 1; // Default threshold for ErrorOrRecoveryPartitionThresholdForLoadBalance
    int partitionCount = partitionsWithErrorStateReplica.size();
    if (clusterConfig.getErrorOrRecoveryPartitionThresholdForLoadBalance() != -1) {
      // ErrorOrRecovery is set
      threshold = clusterConfig.getErrorOrRecoveryPartitionThresholdForLoadBalance();
      partitionCount += partitionsNeedRecovery.size(); // Only add this count when the threshold is
      // set
    } else {
      if (clusterConfig.getErrorPartitionThresholdForLoadBalance() != 0) {
        // 0 is the default value so the old threshold has been set
        threshold = clusterConfig.getErrorPartitionThresholdForLoadBalance();
      }
    }

    // Perform regular load balance only if the number of partitions in recovery and in error is
    // less than the threshold. Otherwise, only allow downward-transition load balance
    boolean onlyDownwardLoadBalance = partitionCount > threshold;

    loadbalanceThrottledPartitions = loadRebalance(resource, currentStateOutput,
        bestPossiblePartitionStateMap, throttleController, intermediatePartitionStateMap,
        partitionsNeedLoadBalance, currentStateOutput.getCurrentStateMap(resourceName),
        onlyDownwardLoadBalance, stateModelDef, cache);

    if (clusterStatusMonitor != null) {
      clusterStatusMonitor.updateRebalancerStats(resourceName, partitionsNeedRecovery.size(),
          partitionsNeedLoadBalance.size(), recoveryThrottledPartitions.size(),
          loadbalanceThrottledPartitions.size());
    }

    if (logger.isDebugEnabled()) {
      logPartitionMapState(resourceName, new HashSet<>(resource.getPartitions()),
          partitionsNeedRecovery, recoveryThrottledPartitions, partitionsNeedLoadBalance,
          loadbalanceThrottledPartitions, currentStateOutput, bestPossiblePartitionStateMap,
          intermediatePartitionStateMap);
    }

    LogUtil.logDebug(logger, _eventId, String.format("End processing resource: %s", resourceName));
    return intermediatePartitionStateMap;
  }

  /**
   * Determine the message is downward message or not.
   * @param message                  message for load rebalance
   * @param stateModelDefinition     state model definition object for this resource
   * @return                         set of messages allowed for downward state transitions
   */
  private boolean isLoadBalanceDownwardStateTransition(Message message, StateModelDefinition stateModelDefinition) {
    // state model definition is not found
    if (stateModelDefinition == null) {
      return false;
    }

    Map<String, Integer> statePriorityMap = stateModelDefinition.getStatePriorityMap();
    // Compare priority values and return if an upward transition is found
    // Note that lower integer value implies higher priority
    // If the state is not found in statePriorityMap, consider it not strictly downward by
    // default because we can't determine whether it is downward
    if (statePriorityMap.containsKey(message.getFromState()) && statePriorityMap.containsKey(message.getToState())
        && statePriorityMap.get(message.getFromState()) > statePriorityMap.get(message.getToState())) {
      return true;
    }
    return false;
  }

  /**
   * Check and charge all pending transitions for throttling.
   */
  private void chargePendingTransition(Resource resource, CurrentStateOutput currentStateOutput,
      StateTransitionThrottleController throttleController, ResourceControllerDataProvider cache,
      Map<String, List<String>> preferenceLists, StateModelDefinition stateModelDefinition,
      PartitionStateMap intermediatePartitionStateMap) {
    String resourceName = resource.getResourceName();
    // check and charge pending transitions
    for (Partition partition : resource.getPartitions()) {
      // To clarify that custom mode does not apply recovery/load rebalance since user can define different number of
      // replicas for different partitions. Actually, the custom will stopped from resource level checks if this resource
      // is not FULL_AUTO, we will return best possible state and do nothing.
      Map<String, Integer> requiredStates =
          getRequiredStates(resourceName, cache, preferenceLists.get(partition.getPartitionName()));
      // Maps instance to its current state
      Map<String, String> currentStateMap = currentStateOutput.getCurrentStateMap(resourceName, partition);
      // Maps instance to its pending (next) state
      List<Message> pendingMessages =
          new ArrayList<>(currentStateOutput.getPendingMessageMap(resourceName, partition).values());
      Collections.sort(pendingMessages, new MessagePriorityComparator(preferenceLists.get(partition.getPartitionName()),
          stateModelDefinition.getStatePriorityMap()));

      for (Message message : pendingMessages) {
        StateTransitionThrottleConfig.RebalanceType rebalanceType =
            getRebalanceTypePerMessage(requiredStates, message, currentStateMap);
        String currentState = currentStateMap.get(message.getTgtName());
        if (!message.getToState().equals(currentState) && message.getFromState().equals(currentState)
            && !cache.getDisabledInstancesForPartition(resourceName, partition.getPartitionName())
            .contains(message.getTgtName())) {
          throttleController.chargeInstance(rebalanceType, message.getTgtName());
          throttleController.chargeResource(rebalanceType, resourceName);
          throttleController.chargeCluster(rebalanceType);
        }
        intermediatePartitionStateMap.setState(partition, message.getTgtName(), message.getToState());
      }
    }
  }

  /**
   * Thin wrapper for per message throttling with recovery rebalance type. Also populate
   * intermediatePartitionStateMap with generated messages from {@link MessageGenerationPhase}.
   * @param resource                      the resource to throttle
   * @param throttleController            throttle controller object
   * @param messageToThrottle             the message to be throttled
   * @param intermediatePartitionStateMap output result for this stage that intermediate state map
   * @param cache                         cache object for computational metadata from external storage
   * @param messagesThrottled             messages that have already been throttled
   * @param resourceMessageMap            the map for all messages from MessageSelectStage. Remove the message
   *                                      if it has been throttled
   */
  private void recoveryRebalance(Resource resource, Partition partition,
      StateTransitionThrottleController throttleController, Message messageToThrottle,
      PartitionStateMap intermediatePartitionStateMap, ResourceControllerDataProvider cache,
      Set<Message> messagesThrottled, Map<Partition, List<Message>> resourceMessageMap) {
    throttleStateTransitionsForReplica(throttleController, resource.getResourceName(), partition, messageToThrottle,
        messagesThrottled, intermediatePartitionStateMap, RebalanceType.RECOVERY_BALANCE, cache, resourceMessageMap);
  }

  /**
   * Thin wrapper for per message throttling with load rebalance type. Also populate
   * intermediatePartitionStateMap with generated messages from {@link MessageGenerationPhase}.
   * @param resource                      the resource to throttle
   * @param throttleController            throttle controller object
   * @param messageToThrottle             the message to be throttle
   * @param intermediatePartitionStateMap output result for this stage that intermediate state map
   * @param cache                         cache object for computational metadata from external storage
   * @param onlyDownwardLoadBalance       does allow only downward load balance
   * @param stateModelDefinition          state model definition of this resource
   * @param messagesThrottled             messages are already throttled
   * @param resourceMessageMap            the map for all messages from MessageSelectStage. Remove the message
   *                                      if it has been throttled
   */
  private void loadRebalance(Resource resource, Partition partition,
      StateTransitionThrottleController throttleController, Message messageToThrottle,
      PartitionStateMap intermediatePartitionStateMap, ResourceControllerDataProvider cache,
      boolean onlyDownwardLoadBalance, StateModelDefinition stateModelDefinition, Set<Message> messagesThrottled,
      Map<Partition, List<Message>> resourceMessageMap) {
    if (onlyDownwardLoadBalance && isLoadBalanceDownwardStateTransition(messageToThrottle, stateModelDefinition)) {
      // Remove the message already allowed for downward state transitions.
      intermediatePartitionStateMap.setState(partition, messageToThrottle.getTgtName(), messageToThrottle.getToState());
      return;
    }
    throttleStateTransitionsForReplica(throttleController, resource.getResourceName(), partition, messageToThrottle,
        messagesThrottled, intermediatePartitionStateMap, RebalanceType.LOAD_BALANCE, cache, resourceMessageMap);
  }

  /**
   * Check the status for a single message on throttling at every level (cluster, resource, replica) and set
   * intermediatePartitionStateMap accordingly for that replica.
   * @param throttleController                throttle controller object for throttling quota
   * @param resourceName                      the resource for throttling check
   * @param partition                         the partition for throttling check
   * @param messageToThrottle                 the message to be throttled
   * @param messagesThrottled                 the cumulative set of messages that have been throttled already. These
   *                                          messages represent the replicas of this partition that have been throttled.
   * @param intermediatePartitionStateMap     the cumulative partition-state mapping as a result of the throttling step
   *                                          of IntermediateStateCalcStage
   * @param rebalanceType                     the rebalance type to charge quota
   * @param cache                             cached cluster metadata required by the throttle controller
   * @param resourceMessageMap                the map for all messages from MessageSelectStage. Remove the message
   *                                          if it has been throttled.
   */
  private void throttleStateTransitionsForReplica(StateTransitionThrottleController throttleController,
      String resourceName, Partition partition, Message messageToThrottle, Set<Message> messagesThrottled,
      PartitionStateMap intermediatePartitionStateMap, RebalanceType rebalanceType,
      ResourceControllerDataProvider cache, Map<Partition, List<Message>> resourceMessageMap) {
    boolean hasReachedThrottlingLimit = false;
    if (throttleController.shouldThrottleForResource(rebalanceType, resourceName)) {
      hasReachedThrottlingLimit = true;
      if (logger.isDebugEnabled()) {
        LogUtil.logDebug(logger, _eventId, String.format(
            "Throttled because of cluster/resource quota is full for message {%s} on partition {%s} in resource {%s}",
            messageToThrottle.getId(), partition.getPartitionName(), resourceName));
      }
    } else {
      // Since message already generated, we can assume the current state is not null and target state is not null
      if (!cache.getDisabledInstancesForPartition(resourceName, partition.getPartitionName())
          .contains(messageToThrottle.getTgtName())) {
        if (throttleController.shouldThrottleForInstance(rebalanceType, messageToThrottle.getTgtName())) {
          hasReachedThrottlingLimit = true;
          if (logger.isDebugEnabled()) {
            LogUtil.logDebug(logger, _eventId, String.format(
                "Throttled because of instance level quota is full on instance {%s} for message {%s} of partition {%s} in resource {%s}",
                messageToThrottle.getId(), messageToThrottle.getTgtName(), partition.getPartitionName(), resourceName));
          }
        }
      }
    }
    // If there is still room for this replica, proceed to charge at the cluster and resource level and set the
    // intermediate partition-state mapping so that the state transition message can move forward.
    if (!hasReachedThrottlingLimit) {
      throttleController.chargeCluster(rebalanceType);
      throttleController.chargeResource(rebalanceType, resourceName);
      intermediatePartitionStateMap.setState(partition, messageToThrottle.getTgtName(), messageToThrottle.getToState());
    } else {
      // Intermediate Map is based on current state
      // Remove the message from MessageSelection result if it has been throttled since the message will be dispatched
      // by next stage if it is not removed.
      resourceMessageMap.get(partition).remove(messageToThrottle);
      messagesThrottled.add(messageToThrottle);
    }
  }

  /**
   * For a partition, given its preferenceList, bestPossibleState, and currentState, determine which
   * type of rebalance is needed to model IdealState's states defined by the state model definition.
   * @return RebalanceType needed to bring the replicas to idea states
   *         RECOVERY_BALANCE - not all required states (replicas) are available through all
   *         replicas, or the partition is disabled
   *         NONE - current state matches the ideal state
   *         LOAD_BALANCE - although all replicas required exist, Helix needs to optimize the
   *         allocation
   */
  private RebalanceType getRebalanceType(ResourceControllerDataProvider cache,
      Map<String, String> bestPossibleMap, List<String> preferenceList,
      StateModelDefinition stateModelDef, Map<String, String> currentStateMap,
      IdealState idealState, String partitionName) {
    if (preferenceList == null) {
      preferenceList = Collections.emptyList();
    }

    // If there is a minimum active replica number specified in IS, we should respect it.
    // TODO: We should implement the per replica level throttling with generated message
    // Issue: https://github.com/apache/helix/issues/343
    int replica = idealState.getMinActiveReplicas() == -1
        ? idealState.getReplicaCount(preferenceList.size())
        : idealState.getMinActiveReplicas();
    Set<String> activeList = new HashSet<>(preferenceList);
    activeList.retainAll(cache.getEnabledLiveInstances());

    // For each state, check that this partition currently has the required number of that state as
    // required by StateModelDefinition.
    LinkedHashMap<String, Integer> expectedStateCountMap =
        stateModelDef.getStateCountMap(activeList.size(), replica); // StateModelDefinition's counts
    // Current counts without disabled partitions or disabled instances
    Map<String, String> currentStateMapWithoutDisabled = new HashMap<>(currentStateMap);
    currentStateMapWithoutDisabled.keySet().removeAll(
        cache.getDisabledInstancesForPartition(idealState.getResourceName(), partitionName));
    Map<String, Integer> currentStateCounts =
        StateModelDefinition.getStateCounts(currentStateMapWithoutDisabled);

    // Go through each state and compare counts
    for (String state : expectedStateCountMap.keySet()) {
      Integer expectedCount = expectedStateCountMap.get(state);
      Integer currentCount = currentStateCounts.get(state);
      expectedCount = expectedCount == null ? 0 : expectedCount;
      currentCount = currentCount == null ? 0 : currentCount;

      // If counts do not match up, this partition requires recovery
      if (currentCount < expectedCount) {
        // Recovery is not needed in cases where this partition just started, was dropped, or is in
        // error
        if (!state.equals(HelixDefinedState.DROPPED.name())
            && !state.equals(HelixDefinedState.ERROR.name())
            && !state.equals(stateModelDef.getInitialState())) {
          return RebalanceType.RECOVERY_BALANCE;
        }
      }
    }
    // No recovery needed, all expected replicas exist
    // Check if this partition is actually in the BestPossibleState
    if (currentStateMap.equals(bestPossibleMap)) {
      return RebalanceType.NONE; // No further action required
    } else {
      return RebalanceType.LOAD_BALANCE; // Required state counts are satisfied, but in order to
      // achieve BestPossibleState, load balance may be required
      // to shift replicas around
    }
  }

  /**
   * Determine the message rebalance type with message and current states.
   * @param desiredStates         Ideally how may states we needed for guarantee the health of replica
   * @param message               The message to be determined what is the rebalance type
   * @param derivedCurrentStates  Derived from current states with previous messages not be throttled.
   * @return                      Rebalance type. Recovery or load.
   */
  private RebalanceType getRebalanceTypePerMessage(Map<String, Integer> desiredStates, Message message,
      Map<String, String> derivedCurrentStates) {
    Map<String, Integer> desiredStatesSnapshot = new HashMap<>(desiredStates);
    // Looping existing current states to see whether current states fulfilled all the required states.
    for (String state : derivedCurrentStates.values()) {
      if (desiredStatesSnapshot.containsKey(state)) {
        if (desiredStatesSnapshot.get(state) == 1) {
          desiredStatesSnapshot.remove(state);
        } else {
          desiredStatesSnapshot.put(state, desiredStatesSnapshot.get(state) - 1);
        }
      }
    }

    // If the message contains any "required" state changes, then it is considered recovery rebalance.
    // Otherwise, it is load balance.
    return desiredStatesSnapshot.containsKey(message.getToState()) ? RebalanceType.RECOVERY_BALANCE
        : RebalanceType.LOAD_BALANCE;
  }

  private Map<String, Integer> getRequiredStates(String resourceName,
      ResourceControllerDataProvider resourceControllerDataProvider, List<String> preferenceList) {

    // Prepare required inputs: 1) Priority State List 2) required number of replica
    IdealState idealState = resourceControllerDataProvider.getIdealState(resourceName);
    StateModelDefinition stateModelDefinition =
        resourceControllerDataProvider.getStateModelDef(idealState.getStateModelDefRef());
    int requiredNumReplica = idealState.getMinActiveReplicas() == -1
        ? idealState.getReplicaCount(preferenceList.size())
        : idealState.getMinActiveReplicas();

    // Generate a state mapping, state -> required numbers based on the live and enabled instances for this partition
    // preference list
    LinkedHashMap<String, Integer> expectedStateCountMap = stateModelDefinition.getStateCountMap(
        (int) preferenceList.stream()
            .filter(i -> resourceControllerDataProvider.getEnabledLiveInstances().contains(i))
            .count(), requiredNumReplica); // StateModelDefinition's counts

    return expectedStateCountMap;
  }

  /**
   * Log rebalancer metadata for debugging purposes.
   * @param resource
   * @param allPartitions
   * @param recoveryPartitions
   * @param recoveryThrottledPartitions
   * @param loadbalancePartitions
   * @param loadbalanceThrottledPartitions
   * @param currentStateOutput
   * @param bestPossibleStateMap
   * @param intermediateStateMap
   */
  private void logPartitionMapState(String resource, Set<Partition> allPartitions,
      Set<Partition> recoveryPartitions, Set<Partition> recoveryThrottledPartitions,
      Set<Partition> loadbalancePartitions, Set<Partition> loadbalanceThrottledPartitions,
      CurrentStateOutput currentStateOutput, PartitionStateMap bestPossibleStateMap,
      PartitionStateMap intermediateStateMap) {

    if (logger.isDebugEnabled()) {
      LogUtil.logDebug(logger, _eventId,
          String.format("Partitions need recovery: %s\nPartitions get throttled on recovery: %s",
              recoveryPartitions, recoveryThrottledPartitions));
      LogUtil.logDebug(logger, _eventId,
          String.format(
              "Partitions need loadbalance: %s\nPartitions get throttled on load-balance: %s",
              loadbalancePartitions, loadbalanceThrottledPartitions));
    }

    for (Partition partition : allPartitions) {
      if (logger.isDebugEnabled()) {
        LogUtil.logDebug(logger, _eventId, String.format("%s : Best possible map: %s", partition,
            bestPossibleStateMap.getPartitionMap(partition)));
        LogUtil.logDebug(logger, _eventId, String.format("%s : Current State: %s", partition,
            currentStateOutput.getCurrentStateMap(resource, partition)));
        LogUtil.logDebug(logger, _eventId, String.format("%s: Pending state: %s", partition,
            currentStateOutput.getPendingMessageMap(resource, partition)));
        LogUtil.logDebug(logger, _eventId, String.format("%s: Intermediate state: %s", partition,
            intermediateStateMap.getPartitionMap(partition)));
      }
    }
  }

  /**
   * POJO that maps resource name to its priority represented by an integer.
   */
  private static class ResourcePriority {
    private String _resourceName;
    private int _priority;

    ResourcePriority(String resourceName, Integer priority) {
      _resourceName = resourceName;
      _priority = priority;
    }

    public int compareTo(ResourcePriority resourcePriority) {
      return Integer.compare(_priority, resourcePriority._priority);
    }

    public String getResourceName() {
      return _resourceName;
    }

    public void setPriority(String priority) {
      try {
        _priority = Integer.parseInt(priority);
      } catch (Exception e) {
        logger.warn(
            String.format("Invalid priority field %s for resource %s", priority, _resourceName));
      }
    }
  }

  private static class ResourcePriorityComparator implements Comparator<ResourcePriority> {
    @Override
    public int compare(ResourcePriority priority1, ResourcePriority priority2) {
      return priority2.compareTo(priority1);
    }
  }

  private class MessagePriorityComparator implements Comparator<Message> {
    private Map<String, Integer> _preferenceInstanceMap;
    private Map<String, Integer> _statePriorityMap;

    MessagePriorityComparator(List<String> preferenceList, Map<String, Integer> statePriorityMap) {
      // Get instance -> priority map.
      _preferenceInstanceMap = IntStream.range(0, preferenceList.size())
          .boxed()
          .collect(Collectors.toMap(preferenceList::get, index -> index));
      _statePriorityMap = statePriorityMap;
    }

    @Override
    public int compare(Message m1, Message m2) {
      //  Compare rules:
      //     1. Higher target state has higher priority.
      //     2. If target state is same, range it as preference list order.
      //     3. Sort by the name of targeted instances just for deterministic ordering.
      if (m1.getToState().equals(m2.getToState()) && _preferenceInstanceMap.containsKey(m1.getTgtName())
          && _preferenceInstanceMap.containsKey(m2.getTgtName())) {
        return _preferenceInstanceMap.get(m1.getTgtName()).compareTo(_preferenceInstanceMap.get(m2.getTgtName()));
      }
      if (!m1.getToState().equals(m2.getToState())) {
        return _statePriorityMap.get(m1.getToState()).compareTo(_statePriorityMap.get(m2.getToState()));
      }
      return m1.getTgtName().compareTo(m2.getTgtName());
    }
  }

    // Compare partitions according following standard:
  // 1) Partition without top state always is the highest priority.
  // 2) For partition with top-state, the more number of active replica it has, the less priority.
  private class PartitionPriorityComparator implements Comparator<Partition> {
    private Map<Partition, Map<String, String>> _bestPossibleMap;
    private Map<Partition, Map<String, String>> _currentStateMap;
    private String _topState;

    PartitionPriorityComparator(Map<Partition, Map<String, String>> bestPossibleMap,
        Map<Partition, Map<String, String>> currentStateMap, String topState){
      _bestPossibleMap = bestPossibleMap;
      _currentStateMap = currentStateMap;
      _topState = topState;
    }

    @Override
    public int compare(Partition p1, Partition p2) {
      int missTopState1 = getMissTopStateIndex(p1);
      int missTopState2 = getMissTopStateIndex(p2);
      // Highest priority for the partition without top state
      if (missTopState1 != missTopState2) {
        return Integer.compare(missTopState1, missTopState2);
      }
      // Higher priority for the partition with fewer active replicas
      int currentActiveReplicas1 = getCurrentActiveReplicas(p1);
      int currentActiveReplicas2 = getCurrentActiveReplicas(p2);
      if (currentActiveReplicas1 != currentActiveReplicas2) {
        return Integer.compare(currentActiveReplicas1, currentActiveReplicas2);
      }
      // Higher priority for the partition with fewer replicas with states matching with IdealState
      int idealStateMatched1 = getIdealStateMatched(p1);
      int idealStateMatched2 = getIdealStateMatched(p2);
      return Integer.compare(idealStateMatched1, idealStateMatched2);
    }

    private int getMissTopStateIndex(Partition partition) {
      // 0 if no replicas in top-state, 1 if it has at least one replica in top-state.
      if (!_currentStateMap.containsKey(partition)
          || !_currentStateMap.get(partition).values().contains(_topState)) {
        return 0;
      }
      return 1;
    }

    private int getCurrentActiveReplicas(Partition partition) {
      int currentActiveReplicas = 0;
      if (!_currentStateMap.containsKey(partition)) {
        return currentActiveReplicas;
      }
      // Initialize state -> number of this state map
      Map<String, Integer> stateCountMap = new HashMap<>();
      for (String state : _bestPossibleMap.get(partition).values()) {
        if (!stateCountMap.containsKey(state)) {
          stateCountMap.put(state, 0);
        }
        stateCountMap.put(state, stateCountMap.get(state) + 1);
      }
      // Search the state map
      for (String state : _currentStateMap.get(partition).values()) {
        if (stateCountMap.containsKey(state) && stateCountMap.get(state) > 0) {
          currentActiveReplicas++;
          stateCountMap.put(state, stateCountMap.get(state) - 1);
        }
      }
      return currentActiveReplicas;
    }

    private int getIdealStateMatched(Partition partition) {
      int matchedState = 0;
      if (!_currentStateMap.containsKey(partition)) {
        return matchedState;
      }
      for (String instance : _bestPossibleMap.get(partition).keySet()) {
        if (_bestPossibleMap.get(partition).get(instance)
            .equals(_currentStateMap.get(partition).get(instance))) {
          matchedState++;
        }
      }
      return matchedState;
    }
  }

  /**
   * Handle a partition with a pending message so that the partition will not be double-charged or double-assigned during recovery and load balance.
   * @param partition
   * @param partitionsNeedRecovery
   * @param partitionsNeedLoadbalance
   * @param rebalanceType
   */
  private void handlePendingStateTransitionsForThrottling(Partition partition,
      Set<Partition> partitionsNeedRecovery, Set<Partition> partitionsNeedLoadbalance,
      RebalanceType rebalanceType, PartitionStateMap bestPossiblePartitionStateMap,
      PartitionStateMap intermediatePartitionStateMap) {
    // Pass the best possible state directly into intermediatePartitionStateMap
    // This is safe to do so because we already have a pending transition for this partition, implying that the assignment has been made in previous pipeline
    intermediatePartitionStateMap
        .setState(partition, bestPossiblePartitionStateMap.getPartitionMap(partition));
    // Remove the partition's name from the set of partition (names) that need to be charged and assigned to prevent double-processing
    switch (rebalanceType) {
    case RECOVERY_BALANCE:
      partitionsNeedRecovery.remove(partition);
      break;
    case LOAD_BALANCE:
      partitionsNeedLoadbalance.remove(partition);
      break;
    }
  }
}
