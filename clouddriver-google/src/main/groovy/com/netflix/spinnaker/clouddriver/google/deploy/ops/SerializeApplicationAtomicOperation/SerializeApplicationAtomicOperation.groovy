/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.google.deploy.ops.SerializeApplicationAtomicOperation

import com.google.api.services.compute.model.*
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.google.deploy.description.SerializeApplicationDescription.SerializeApplicationDescription
import com.netflix.spinnaker.clouddriver.google.deploy.exception.GoogleResourceIllegalStateException
import com.netflix.spinnaker.clouddriver.google.model.GoogleCluster
import com.netflix.spinnaker.clouddriver.google.model.GoogleHealthCheck
import com.netflix.spinnaker.clouddriver.google.model.GoogleSecurityGroup
import com.netflix.spinnaker.clouddriver.google.model.GoogleServerGroup
import com.netflix.spinnaker.clouddriver.google.model.loadbalancing.GoogleLoadBalancerView
import com.netflix.spinnaker.clouddriver.google.provider.view.GoogleClusterProvider
import com.netflix.spinnaker.clouddriver.google.provider.view.GoogleLoadBalancerProvider
import com.netflix.spinnaker.clouddriver.google.provider.view.GoogleSecurityGroupProvider
import com.netflix.spinnaker.clouddriver.google.security.GoogleNamedAccountCredentials
import com.netflix.spinnaker.clouddriver.model.securitygroups.Rule
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsRepository
import groovy.json.JsonBuilder
import org.springframework.beans.factory.annotation.Autowired

class SerializeApplicationAtomicOperation implements AtomicOperation<Void> {

  private static final String BASE_PHASE = "SERIALIZE_APPLICATION"

  private static Task getTask() {
    TaskRepository.threadLocalTask.get()
  }

  private final SerializeApplicationDescription description
  private GoogleNamedAccountCredentials credentials
  private final String applicationName
  private final String accountName
  private String project


  private List applicationTags
  private int numInstanceGroupManagers
  private int numInstanceTemplates
  private int numTargetPools
  private int numForwardingRules
  private int numHealthChecks
  private int numAutoscalers
  private int numFirewalls

  @Autowired
  GoogleClusterProvider googleClusterProvider

  @Autowired
  GoogleLoadBalancerProvider googleLoadBalancerProvider

  @Autowired
  GoogleSecurityGroupProvider googleSecurityGroupProvider

  @Autowired
  AccountCredentialsRepository accountCredentialsRepository

  SerializeApplicationAtomicOperation(SerializeApplicationDescription description) {
    this.description = description
    this.applicationName = description.applicationName
    this.accountName = description.accountName
    this.applicationTags = []
  }


  /* curl -X POST -H "Content-Type: application/json" -d '[ { "serializeApplication": { "applicationName": "codelab", "credentials": "my-google-account" }} ]' localhost:7002/gce/ops */
  @Override
  Void operate(List priorOutputs) {
    //TODO(nwwebb) add project to resources
    //TODO(nwwebb) static ip addresses
    this.credentials = accountCredentialsRepository.getOne(this.accountName) as GoogleNamedAccountCredentials
    this.project = credentials.project

    def resourceMap = [:]
    def json = new JsonBuilder()
    initializeResourceMap(resourceMap)

    task.updateStatus BASE_PHASE, "Serializing server groups for the application ${this.applicationName} in account ${this.accountName}"
    googleClusterProvider.getClusters(applicationName, accountName).each { GoogleCluster.View cluster ->
      cluster.serverGroups.each { GoogleServerGroup.View serverGroup ->
        addServerGroupToResourceMap(serverGroup, resourceMap)
      }
    }

    task.updateStatus BASE_PHASE, "Serializing load balancers for the application ${this.applicationName} in account ${this.accountName}"
    googleLoadBalancerProvider.getApplicationLoadBalancers(applicationName).each { GoogleLoadBalancerView loadBalancer ->
      if (loadBalancer.account == this.accountName) {
        addLoadBalancerToResourceMap(loadBalancer, resourceMap)
      }
    }

    task.updateStatus BASE_PHASE, "Serializing security groups for application ${this.applicationName} in account ${this.accountName}"
    googleSecurityGroupProvider.getAll(true).each {GoogleSecurityGroup securityGroup ->
      if (securityGroup.accountName == this.accountName && securityGroup.targetTags && !Collections.disjoint(securityGroup.targetTags, applicationTags)) {
        addSecurityGroupToResourceMap(securityGroup, resourceMap)
      }
    }

    cleanUpResourceMap(resourceMap)
    json(resourceMap)
    //TODO(nwwebb) this temporarily prints the serialization, in the future this will be exported to a GCS bucket
    println json.toPrettyString()

    return null
  }

  private Void initializeResourceMap(Map resourceMap) {
    resourceMap.google_compute_instance_group_manager = [:]
    resourceMap.google_compute_instance_template = [:]
    resourceMap.google_compute_target_pool = [:]
    resourceMap.google_compute_forwarding_rule = [:]
    resourceMap.google_compute_http_health_check = [:]
    resourceMap.google_compute_autoscaler = [:]
    resourceMap.google_compute_firewall = [:]

    return null
  }

  /*
   * Need to do this or terraform complains
   */
  private Void cleanUpResourceMap(Map resourceMap) {
    if (numInstanceGroupManagers == 0) {
      resourceMap.remove("google_compute_instance_group_manager")
    }
    if (numInstanceTemplates == 0) {
      resourceMap.remove("google_compute_instance_template")
    }
    if (numTargetPools == 0) {
      resourceMap.remove("google_compute_target_pool")
    }
    if (numForwardingRules == 0) {
      resourceMap.remove("google_compute_forwarding_rule")
    }
    if (numHealthChecks == 0) {
      resourceMap.remove("google_compute_http_health_check")
    }
    if (numAutoscalers == 0) {
      resourceMap.remove("google_compute_autoscaler")
    }
    if (numFirewalls == 0) {
      resourceMap.remove("google_compute_firewall")
    }
    return null
  }

  /*
   * Adds the server group into a resource map that is in Terraform format. Returns false if the server group is invalid.
   */
  private Void addServerGroupToResourceMap(GoogleServerGroup.View serverGroup, Map resourceMap) {

    def serverGroupMap = [:]
    if (serverGroup.name) {
      serverGroupMap.name = serverGroup.name
      serverGroupMap.base_instance_name = serverGroup.name
    } else {
      throw new GoogleResourceIllegalStateException("Required server group name not found")
    }
    if (serverGroup.zone) {
      serverGroupMap.zone = serverGroup.zone
    } else {
      throw new GoogleResourceIllegalStateException("Required zone not found for server group: $serverGroup.name")
    }

    if (serverGroup.imageSummary.image) {
      def instanceTemplateMap = serverGroup.imageSummary.image
      InstanceTemplate instanceTemplate = convertMapToInstanceTemplate(instanceTemplateMap)
      addInstanceTemplateToResourceMap(instanceTemplate, resourceMap)
      serverGroupMap.instance_template = "\${google_compute_instance_template.${instanceTemplate.name}.self_link}"
    } else {
      throw new GoogleResourceIllegalStateException("Required instance template not found for server group: $serverGroup.name")
    }

    serverGroupMap.project = this.project

    if (serverGroup.loadBalancers && !serverGroup.loadBalancers.isEmpty()) {
      serverGroupMap.target_pools = []
      serverGroup.loadBalancers.each {String loadBalancer ->
        serverGroupMap.target_pools.add("\${google_compute_target_pool.${loadBalancer}.self_link}")
      }
    }
    //TODO(nwwebb) see if you can get application scope security groups using serverGroup.securityGroups rather than tags
    // only one of these should be specified or they will conflict
    serverGroup.autoscalingPolicy
    if (serverGroup.autoscalingPolicy) {
      addAutoscalerToResourceMap(serverGroup.name, serverGroup.zone, serverGroup.autoscalingPolicy, resourceMap)
    } else if (serverGroup.instanceCounts?.total) {
      serverGroupMap.target_size = serverGroup.instanceCounts.total
    }

    numInstanceGroupManagers++
    resourceMap.google_compute_instance_group_manager[serverGroup.name] = serverGroupMap

    return null
  }

  private Void addInstanceTemplateToResourceMap(InstanceTemplate instanceTemplate, Map resourceMap) {

    def instanceTemplateMap = [:]
    if (instanceTemplate.name) {
      instanceTemplateMap.name = instanceTemplate.name
    }  else {
      throw new GoogleResourceIllegalStateException("Required instance template name not found")
    }
    if (instanceTemplate.properties?.machineType ) {
      instanceTemplateMap.machine_type = instanceTemplate.properties.machineType
    } else {
      throw new GoogleResourceIllegalStateException("Required machine type not found for instance template: $instanceTemplate.name")
    }
    if (instanceTemplate.properties.canIpForward != null) {
      instanceTemplateMap.can_ip_forward = instanceTemplate.properties.canIpForward
    }
    if (instanceTemplate.description) {
      instanceTemplateMap.description = instanceTemplate.description
    }
    if (instanceTemplate.properties.description) {
      instanceTemplateMap.instance_description = instanceTemplate.properties.description
    }
    if (instanceTemplate.properties.tags?.items) {
      instanceTemplateMap.tags = instanceTemplate.properties.tags.items
      applicationTags.addAll(instanceTemplate.properties.tags.items)
    }
    if (!addDisksToInstanceTemplateMap(instanceTemplate.properties.disks as List<AttachedDisk>, instanceTemplateMap)) {
      throw new GoogleResourceIllegalStateException("No properly formatted disks found for instance template: $instanceTemplate.name")
    }
    if (instanceTemplate.properties.networkInterfaces && !instanceTemplate.properties.networkInterfaces.isEmpty()) {
      addNetworkInterfacesToInstanceTemplateMap(instanceTemplate.properties.networkInterfaces as List<NetworkInterface>, instanceTemplateMap)
    }
    if (instanceTemplate.properties.scheduling) {
      addSchedulingToInstanceTemplateMap(instanceTemplate.properties.scheduling as Scheduling, instanceTemplateMap)
    }
    if (instanceTemplate.properties.serviceAccounts != null && !instanceTemplate.properties.serviceAccounts.isEmpty()) {
      instanceTemplateMap.service_account = [:]
      List<ServiceAccount> serviceAccounts = instanceTemplate.properties.serviceAccounts as List<ServiceAccount>
      instanceTemplateMap.service_account.scopes = serviceAccounts[0].getScopes()
    }
    if (instanceTemplate.properties.metadata) {
      instanceTemplateMap.metadata = [:]
      instanceTemplate.getProperties().getMetadata().getItems()
      ArrayList items = instanceTemplate.properties.metadata.items as ArrayList
      items.each {Map item ->
        instanceTemplateMap.metadata[item.key] = item.value
      }
    }
    numInstanceTemplates++
    resourceMap.google_compute_instance_template[instanceTemplate.name as String] = instanceTemplateMap

    return null
  }

  private Void addSchedulingToInstanceTemplateMap(Scheduling scheduling, Map instanceTemplateMap) {
    instanceTemplateMap.scheduling = [:]
    if (scheduling.automaticRestart != null) {
      instanceTemplateMap.scheduling.automatic_restart = scheduling.automaticRestart
    }
    if (scheduling.onHostMaintenance) {
      instanceTemplateMap.scheduling.on_host_maintenance = scheduling.onHostMaintenance
    }
    if (scheduling.preemptible != null) {
      instanceTemplateMap.scheduling.preemptible = scheduling.preemptible
    }
    return null
  }

  private Void addNetworkInterfacesToInstanceTemplateMap(List<NetworkInterface> networkInterfaces, Map instanceTemplateMap) {
    instanceTemplateMap.network_interface = []

    networkInterfaces.each { NetworkInterface networkInterface ->
      def networkInterfaceMap = [:]
      if (networkInterface.network) {
        networkInterfaceMap.network = networkInterface.network.split("/").last()
      }
      if (networkInterface.subnetwork) {
        networkInterfaceMap.subnetwork = networkInterface.subnetwork.split("/").last()
      }
      //TODO(nwwebb) deal with possible static ip here, empty map means ephemeral will be used
      //TODO(nwwebb) check how the model differs when there is no external ip for an instance (empty list or null list)
      if (networkInterface.accessConfigs instanceof List) {
        networkInterfaceMap.access_configs = []
      }
      instanceTemplateMap.network_interface << networkInterfaceMap
    }
    return null
  }

  private Boolean addDisksToInstanceTemplateMap(List<AttachedDisk> disks, Map instanceTemplateMap) {
    instanceTemplateMap.disk = []
    def numDisks = 0

    disks.each { AttachedDisk disk ->
      def diskMap = [:]
      if (!disk.source && !disk.initializeParams?.sourceImage) {
        //required
        return false
      }
      if (disk.autoDelete != null) {
        diskMap.auto_delete = disk.autoDelete
      }
      if (disk.boot != null) {
        diskMap.boot = disk.boot
      }
      if (disk.deviceName) {
        diskMap.device_name = disk.deviceName
      }
      if (disk.initializeParams?.diskName) {
        diskMap.name = disk.initializeParams.diskName
      }
      if (disk.initializeParams?.sourceImage) {
        diskMap.source_image = disk.initializeParams.sourceImage
      }
      if (disk.source) {
        diskMap.source = disk.source
      }
      if (disk.interface) {
        diskMap.interface = disk.interface
      }
      if (disk.mode) {
        diskMap.mode = disk.mode
      }
      if (disk.initializeParams?.diskType) {
        diskMap.disk_type = disk.initializeParams?.diskType
      }
      if (disk.initializeParams?.diskSizeGb) {
        diskMap.disk_size_gb = disk.initializeParams?.diskSizeGb
      }
      if (disk.type) {
        diskMap.type = disk.type
      }
      instanceTemplateMap.disk << diskMap
      numDisks++
    }
    return numDisks > 0
  }

  private Void addAutoscalerToResourceMap(String targetName, String targetZone, AutoscalingPolicy autoscalingPolicy, Map resourceMap) {
    def autoscalerMap = [:]

    autoscalerMap.name = targetName
    autoscalerMap.target = "\${google_compute_instance_group_manager.${targetName}.self_link}"
    autoscalerMap.zone = targetZone

    // Autoscaling policy
    autoscalerMap.autoscaling_policy = [:]
    if (autoscalingPolicy.maxNumReplicas) {
      autoscalerMap.autoscaling_policy.max_replicas = autoscalingPolicy.maxNumReplicas
    } else {
      throw new GoogleResourceIllegalStateException("Required maximum number of replicas not found for autoscaler: ${autoscalerMap.name}")
    }
    if (autoscalingPolicy.minNumReplicas) {
      autoscalerMap.autoscaling_policy.min_replicas = autoscalingPolicy.minNumReplicas
    } else {
      throw new GoogleResourceIllegalStateException("Required minimum number of replicas not found for autoscaler: ${autoscalerMap.name}")
    }
    if (autoscalingPolicy.coolDownPeriodSec) {
      autoscalerMap.autoscaling_policy.cooldown_period = autoscalingPolicy.coolDownPeriodSec
    }
    //TODO(nwwebb) terraform only lets you select one policy even though gce lets you select multiple
    if (autoscalingPolicy.cpuUtilization?.utilizationTarget) {
      autoscalerMap.autoscaling_policy.cpu_utilization = [:]
      autoscalerMap.autoscaling_policy.cpu_utilization.target = autoscalingPolicy.cpuUtilization.utilizationTarget
    }
    if (autoscalingPolicy.customMetricUtilizations) {
      autoscalerMap.autoscaling_policy.metric = []
      autoscalingPolicy.customMetricUtilizations.each {Map metric ->
        def metricMap = [:]
        if (metric.metric) {
          metricMap.name = metric.metric
        } else {
          return
        }
        if (metric.utilizationTarget) {
          metricMap.target = metric.utilizationTarget
        } else {
          return
        }
        //TODO(nwwebb) gce doesn't match terraform types
        switch(metric.utilizationTargetType) {
          case "GAUGE":
            metricMap.type = "gauge"
            break
          case "DELTA PER SECOND":
            metricMap.type = "delta"
            break
          case "DELTA PER MINUTE":
            metricMap.type = "cumulative"
            break
          default:
            metricMap.type = "gauge"
        }
        autoscalerMap.autoscaling_policy.metric.add(metricMap)
      }
    }
    if (autoscalingPolicy.loadBalancingUtilization?.utilizationTarget) {
      autoscalerMap.autoscaling_policy.load_balancing_utilization = [:]
      autoscalerMap.autoscaling_policy.load_balancing_utilization.target = autoscalingPolicy.loadBalancingUtilization.utilizationTarget
    }
    numAutoscalers++
    resourceMap.google_compute_autoscaler[targetName] = autoscalerMap
    return null
  }

  private Void addLoadBalancerToResourceMap(GoogleLoadBalancerView loadBalancer, Map resourceMap) {

    def targetPoolMap = [:]
    def forwardingRuleMap = [:]
    if (loadBalancer.name) {
      forwardingRuleMap.name = loadBalancer.name
    } else {
      throw new GoogleResourceIllegalStateException("Required name not found for load balancer")
    }
    if (loadBalancer.ipProtocol) {
      forwardingRuleMap.ip_protocol = loadBalancer.ipProtocol
    }
    if (loadBalancer.portRange) {
      forwardingRuleMap.port_range = loadBalancer.portRange
    }
    if (loadBalancer.region) {
      forwardingRuleMap.region = loadBalancer.region
      targetPoolMap.region = loadBalancer.region
    }

    //TODO(nwwebb) we only want to set the ipAddress if it is static
//    if (loadBalancer.ipAddress) {
//      forwardingRuleMap.ip_address = loadBalancer.ipAddress
//    }

    if (loadBalancer.targetPool) {
      targetPoolMap.name = loadBalancer.targetPool.split("/").last()
    } else {
      throw new GoogleResourceIllegalStateException("Required target pool name not found for load balancer: $loadBalancer.name")
    }

    forwardingRuleMap.target = "\${google_compute_target_pool.${loadBalancer.name}.self_link}"

    if (loadBalancer.healthCheck) {
      addHealthCheckToResourceMap(loadBalancer.healthCheck, resourceMap)
      targetPoolMap.health_checks = [loadBalancer.healthCheck.name]
    }
    numTargetPools++
    numForwardingRules++
    resourceMap.google_compute_target_pool[loadBalancer.name] = targetPoolMap
    resourceMap.google_compute_forwarding_rule[loadBalancer.name] = forwardingRuleMap

    return null
  }

  private Void addHealthCheckToResourceMap(GoogleHealthCheck.View healthCheck, Map resourceMap) {
    def healthCheckMap = [:]

    if (healthCheck.name) {
      healthCheckMap.name = healthCheck.name
    } else {
      throw new GoogleResourceIllegalStateException("Required health check name not found")
    }
    if (healthCheck.interval) {
      healthCheckMap.check_interval_sec = healthCheck.interval
    }

    if (healthCheck.healthyThreshold) {
      healthCheckMap.healthy_threshold = healthCheck.healthyThreshold
    }
    if (healthCheck.port) {
      healthCheckMap.port = healthCheck.port
    }
    if (healthCheck.requestPath) {
      healthCheckMap.request_path = healthCheck.requestPath
    }
    if (healthCheck.timeout) {
      healthCheckMap.timeout_sec = healthCheck.timeout
    }
    if (healthCheck.unhealthyThreshold) {
      healthCheckMap.unhealthy_threshold = healthCheck.unhealthyThreshold
    }

    numHealthChecks++
    resourceMap.google_compute_http_health_check[healthCheck.name] = healthCheckMap
    return null
  }

  private Void addSecurityGroupToResourceMap(GoogleSecurityGroup securityGroup, Map resourceMap) {

    def firewallMap = [:]
    if (securityGroup.name) {
      firewallMap.name = securityGroup.name
    } else {
      throw new GoogleResourceIllegalStateException("Required security group name not found for a resource within the scope of serialization")
    }
    if (securityGroup.network) {
      firewallMap.network = securityGroup.network
    } else {
      throw new GoogleResourceIllegalStateException("Required network name not found for security group: ${securityGroup.network}")
    }
    if (securityGroup.inboundRules && !securityGroup.inboundRules.isEmpty()) {
      firewallMap.allow = []
      securityGroup.inboundRules.each { Rule rule ->
        def allow = [:]
        allow.protocol = rule.protocol
        if (rule.portRanges && !rule.portRanges.isEmpty()) {
          allow.ports = []
          rule.portRanges.each {Rule.PortRange range ->
            range.startPort == range.endPort ? allow.ports << "$range.startPort" : allow.ports << "$range.startPort-$range.endPort"
          }
        }
        firewallMap.allow << allow
      }
    } else {
      throw new GoogleResourceIllegalStateException("At least one rule is required for security group: ${securityGroup.network}")
    }
    //TODO(nwwebb) does spinnaker let you create outbound rules for a security group
    //TODO(nwwebb) source_ranges not surfaced in model
    //TODO(nwwebb) source_tags not surfaced in model
    if (securityGroup.targetTags && !securityGroup.targetTags.isEmpty()) {
      firewallMap.target_tags = securityGroup.targetTags
    }

    numFirewalls++
    resourceMap.google_compute_firewall[securityGroup.name] = firewallMap
    return null

  }

  /*
   * Object mapper doesn't work for converting the instance template.
   */
  private InstanceTemplate convertMapToInstanceTemplate(Map instanceTemplateMap) {
    InstanceTemplate instanceTemplate = new InstanceTemplate()

    instanceTemplate.creationTimestamp = instanceTemplateMap.creationTimestamp as String
    instanceTemplate.description = instanceTemplateMap.description as String
    instanceTemplate.id = instanceTemplateMap.id as BigInteger
    instanceTemplate.kind = instanceTemplateMap.kind as String
    instanceTemplate.name = instanceTemplateMap.name as String
    instanceTemplate.selfLink = instanceTemplateMap.selfLink as String
    instanceTemplate.sourceInstance = instanceTemplateMap.sourceInstance as String

    if (instanceTemplateMap.properties) {
      instanceTemplate.properties = convertMapToInstanceProperties(instanceTemplateMap.properties as Map)
    }
    return instanceTemplate
  }

  private InstanceProperties convertMapToInstanceProperties(Map instancePropertiesMap) {

    InstanceProperties instanceProperties = new InstanceProperties()

    instanceProperties.canIpForward = instancePropertiesMap.canIpForward as Boolean
    instanceProperties.machineType = instancePropertiesMap.machineType as String
    instanceProperties.description = instancePropertiesMap.description as String

    if (instancePropertiesMap.disks) {
      instanceProperties.disks = new ArrayList<AttachedDisk>()
      List<Map> diskMapList = instancePropertiesMap.disks as List<Map>
      diskMapList.each { Map diskMap->
        instanceProperties.disks.add(convertMapToAttachedDisk(diskMap))
      }
    }
    if (instancePropertiesMap.metadata) {
      instanceProperties.metadata = new Metadata()
      instanceProperties.metadata.items = instancePropertiesMap.metadata.items as List<Metadata.Items>
    }
    if (instancePropertiesMap.scheduling) {
      instanceProperties.scheduling = convertMapToScheduling(instancePropertiesMap.scheduling as Map)
    }
    if (instancePropertiesMap.networkInterfaces) {
      instanceProperties.networkInterfaces = new ArrayList<NetworkInterface>()
      List<Map> networkInterfaceMaps = instancePropertiesMap.networkInterfaces as List<Map>
      networkInterfaceMaps.each {Map networkInterfaceMap ->
        instanceProperties.networkInterfaces.add(convertMapToNetworkInterface(networkInterfaceMap))
      }
    }
    if (instancePropertiesMap.tags && instancePropertiesMap.tags.items) {
      instanceProperties.tags = new Tags()
      instanceProperties.tags.items = instancePropertiesMap.tags.items as List<String>
    }
    if (instancePropertiesMap.serviceAccounts) {
      instanceProperties.serviceAccounts = new ArrayList<ServiceAccount>()
      List<Map> serviceAccountMaps = instancePropertiesMap.serviceAccounts as List<Map>
      serviceAccountMaps.each {Map serviceAccountMap ->
        instanceProperties.serviceAccounts.add(convertMapToServiceAccount(serviceAccountMap))
      }
    }
    return instanceProperties
  }

  private AttachedDisk convertMapToAttachedDisk(Map diskMap) {

    AttachedDisk disk = new AttachedDisk()

    disk.autoDelete = diskMap.autoDelete as Boolean
    disk.boot = diskMap.boot as Boolean
    disk.deviceName = diskMap.deviceName as String
    disk.source = diskMap.source as String
    disk.interface = diskMap.interface as String
    disk.mode =diskMap.mode as String
    disk.type = diskMap.type as String

    if (diskMap.initializeParams) {
      disk.initializeParams = new AttachedDiskInitializeParams()
      disk.initializeParams.diskName = diskMap.initializeParams.diskName as String
      disk.initializeParams.sourceImage = diskMap.initializeParams.sourceImage as String
      disk.initializeParams.diskType = diskMap.initializeParams.diskType as String
      disk.initializeParams.diskSizeGb = diskMap.initializeParams.diskSizeGb as Long
    }
    return disk
  }

  private Scheduling convertMapToScheduling(Map schedulingMap) {

    Scheduling scheduling = new Scheduling()

    scheduling.automaticRestart = schedulingMap.automaticRestart as Boolean
    scheduling.onHostMaintenance = schedulingMap.onHostMaintenance as String
    scheduling.preemptible = schedulingMap.preemptible as Boolean
    return scheduling
  }

  private NetworkInterface convertMapToNetworkInterface(Map networkInterfaceMap) {

    NetworkInterface networkInterface = new NetworkInterface()

    networkInterface.accessConfigs = new ArrayList<AccessConfig>()
    networkInterface.network = networkInterfaceMap.network as String
    networkInterface.subnetwork = networkInterfaceMap.subnetwork as String
    return networkInterface
  }

  private ServiceAccount convertMapToServiceAccount(Map serviceAccountMap) {

    ServiceAccount serviceAccount = new ServiceAccount()

    serviceAccount.scopes = serviceAccountMap.scopes as List<String>
    return serviceAccount
  }

}
