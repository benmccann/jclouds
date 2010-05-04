/**
 *
 * Copyright (C) 2009 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */
package org.jclouds.aws.ec2.compute.config;

import static org.jclouds.aws.ec2.options.DescribeImagesOptions.Builder.ownedBy;
import static org.jclouds.aws.ec2.reference.EC2Constants.PROPERTY_EC2_AMI_OWNERS;
import static org.jclouds.compute.domain.OsFamily.UBUNTU;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.jclouds.aws.domain.Region;
import org.jclouds.aws.ec2.EC2;
import org.jclouds.aws.ec2.EC2AsyncClient;
import org.jclouds.aws.ec2.EC2Client;
import org.jclouds.aws.ec2.compute.EC2ComputeService;
import org.jclouds.aws.ec2.compute.domain.EC2Size;
import org.jclouds.aws.ec2.compute.domain.PortsRegionTag;
import org.jclouds.aws.ec2.compute.domain.RegionTag;
import org.jclouds.aws.ec2.compute.functions.CreateNewKeyPair;
import org.jclouds.aws.ec2.compute.functions.CreateSecurityGroupIfNeeded;
import org.jclouds.aws.ec2.compute.functions.ImageParser;
import org.jclouds.aws.ec2.compute.functions.RunningInstanceToNodeMetadata;
import org.jclouds.aws.ec2.compute.strategy.EC2DestroyNodeStrategy;
import org.jclouds.aws.ec2.compute.strategy.EC2RunNodesAndAddToSetStrategy;
import org.jclouds.aws.ec2.config.EC2ContextModule;
import org.jclouds.aws.ec2.domain.KeyPair;
import org.jclouds.aws.ec2.domain.RunningInstance;
import org.jclouds.aws.ec2.functions.RunningInstanceToStorageMappingUnix;
import org.jclouds.aws.ec2.services.InstanceClient;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.domain.Architecture;
import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.Image;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Size;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.internal.ComputeServiceContextImpl;
import org.jclouds.compute.internal.TemplateBuilderImpl;
import org.jclouds.compute.options.GetNodesOptions;
import org.jclouds.compute.predicates.ScriptStatusReturnsZero;
import org.jclouds.compute.predicates.ScriptStatusReturnsZero.CommandUsingClient;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.compute.strategy.DestroyNodeStrategy;
import org.jclouds.compute.strategy.GetNodeMetadataStrategy;
import org.jclouds.compute.strategy.ListNodesStrategy;
import org.jclouds.compute.strategy.RebootNodeStrategy;
import org.jclouds.compute.strategy.RunNodesAndAddToSetStrategy;
import org.jclouds.domain.Location;
import org.jclouds.domain.LocationScope;
import org.jclouds.domain.internal.LocationImpl;
import org.jclouds.logging.Logger;
import org.jclouds.predicates.RetryablePredicate;
import org.jclouds.rest.RestContext;
import org.jclouds.util.Jsr330;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;

/**
 * Configures the {@link ComputeServiceContext}; requires {@link EC2ComputeService} bound.
 * 
 * @author Adrian Cole
 */
public class EC2ComputeServiceContextModule extends EC2ContextModule {

   private final String providerName;

   public EC2ComputeServiceContextModule(String providerName) {
      this.providerName = providerName;
   }

   @Override
   protected void configure() {
      super.configure();
      bind(ComputeService.class).to(EC2ComputeService.class);
      bind(RunNodesAndAddToSetStrategy.class).to(EC2RunNodesAndAddToSetStrategy.class);
      bind(ListNodesStrategy.class).to(EC2ListNodesStrategy.class);
      bind(GetNodeMetadataStrategy.class).to(EC2GetNodeMetadataStrategy.class);
      bind(RebootNodeStrategy.class).to(EC2RebootNodeStrategy.class);
      bind(DestroyNodeStrategy.class).to(EC2DestroyNodeStrategy.class);
      bind(new TypeLiteral<Function<RunningInstance, Map<String, String>>>() {
      }).annotatedWith(Jsr330.named("volumeMapping")).to(RunningInstanceToStorageMappingUnix.class)
               .in(Scopes.SINGLETON);
   }

   @Provides
   TemplateBuilder provideTemplate(TemplateBuilderImpl template) {
      return template.architecture(Architecture.X86_32).osFamily(UBUNTU);
   }

   @Singleton
   public static class EC2ListNodesStrategy implements ListNodesStrategy {
      private final InstanceClient client;
      private final RunningInstanceToNodeMetadata runningInstanceToNodeMetadata;

      @Inject
      protected EC2ListNodesStrategy(InstanceClient client,
               RunningInstanceToNodeMetadata runningInstanceToNodeMetadata) {
         this.client = client;
         this.runningInstanceToNodeMetadata = runningInstanceToNodeMetadata;
      }

      @Override
      public Iterable<? extends ComputeMetadata> execute(GetNodesOptions options) {
         Set<NodeMetadata> nodes = Sets.newHashSet();
         for (String region : ImmutableSet.of(Region.US_EAST_1, Region.US_WEST_1, Region.EU_WEST_1)) {
            Iterables.addAll(nodes, Iterables.transform(Iterables.concat(client
                     .describeInstancesInRegion(region)), runningInstanceToNodeMetadata));
         }
         return nodes;
      }

   }

   @Singleton
   public static class GetRegionFromNodeOrDefault implements Function<ComputeMetadata, String> {
      public String apply(ComputeMetadata node) {
         Location location = node.getLocation();
         String region = location.getScope() == LocationScope.REGION ? location.getId() : location
                  .getParent().getId();
         return region;
      }
   }

   @Singleton
   public static class EC2GetNodeMetadataStrategy implements GetNodeMetadataStrategy {

      private final InstanceClient client;
      private final RunningInstanceToNodeMetadata runningInstanceToNodeMetadata;
      private final GetRegionFromNodeOrDefault getRegionFromNodeOrDefault;

      @Inject
      protected EC2GetNodeMetadataStrategy(InstanceClient client,
               GetRegionFromNodeOrDefault getRegionFromNodeOrDefault,
               RunningInstanceToNodeMetadata runningInstanceToNodeMetadata) {
         this.client = client;
         this.getRegionFromNodeOrDefault = getRegionFromNodeOrDefault;
         this.runningInstanceToNodeMetadata = runningInstanceToNodeMetadata;
      }

      @Override
      public NodeMetadata execute(ComputeMetadata node) {
         String region = getRegionFromNodeOrDefault.apply(node);
         RunningInstance runningInstance = Iterables.getOnlyElement(getAllRunningInstancesInRegion(
                  client, region, node.getId()));
         return runningInstanceToNodeMetadata.apply(runningInstance);
      }

   }

   public static Iterable<RunningInstance> getAllRunningInstancesInRegion(InstanceClient client,
            String region, String id) {
      return Iterables.concat(client.describeInstancesInRegion(region, id));
   }

   @Singleton
   public static class EC2RebootNodeStrategy implements RebootNodeStrategy {
      private final InstanceClient client;
      private final GetRegionFromNodeOrDefault getRegionFromNodeOrDefault;

      @Inject
      protected EC2RebootNodeStrategy(InstanceClient client,
               GetRegionFromNodeOrDefault getRegionFromNodeOrDefault) {
         this.client = client;
         this.getRegionFromNodeOrDefault = getRegionFromNodeOrDefault;
      }

      @Override
      public boolean execute(ComputeMetadata node) {
         String region = getRegionFromNodeOrDefault.apply(node);
         client.rebootInstancesInRegion(region, node.getId());
         return true;
      }

   }

   @Provides
   @Singleton
   @Named("NOT_RUNNING")
   protected Predicate<CommandUsingClient> runScriptRunning(ScriptStatusReturnsZero stateRunning) {
      return new RetryablePredicate<CommandUsingClient>(Predicates.not(stateRunning), 600, 3,
               TimeUnit.SECONDS);
   }

   @Provides
   @Singleton
   protected final Map<RegionTag, KeyPair> credentialsMap(CreateNewKeyPair in) {
      // doesn't seem to clear when someone issues remove(key)
      // return new MapMaker().makeComputingMap(in);
      return Maps.newLinkedHashMap();
   }

   @Provides
   @Singleton
   protected final Map<PortsRegionTag, String> securityGroupMap(CreateSecurityGroupIfNeeded in) {
      // doesn't seem to clear when someone issues remove(key)
      // return new MapMaker().makeComputingMap(in);
      return Maps.newLinkedHashMap();
   }

   @Provides
   @Singleton
   ComputeServiceContext provideContext(ComputeService computeService,
            RestContext<EC2AsyncClient, EC2Client> context) {
      return new ComputeServiceContextImpl<EC2AsyncClient, EC2Client>(computeService, context);
   }

   @Provides
   @Singleton
   Function<ComputeMetadata, String> indexer() {
      return new Function<ComputeMetadata, String>() {
         @Override
         public String apply(ComputeMetadata from) {
            return from.getId();
         }
      };
   }

   @Provides
   @Singleton
   Set<? extends Size> provideSizes() {
      return ImmutableSet.of(EC2Size.C1_MEDIUM, EC2Size.C1_XLARGE, EC2Size.M1_LARGE,
               EC2Size.M1_SMALL, EC2Size.M1_XLARGE, EC2Size.M2_XLARGE, EC2Size.M2_2XLARGE,
               EC2Size.M2_4XLARGE);
   }

   @Provides
   @Singleton
   Set<? extends Location> provideLocations(Map<String, String> availabilityZoneToRegionMap) {
      Location ec2 = new LocationImpl(LocationScope.PROVIDER, providerName, providerName, null);
      Set<Location> locations = Sets.newLinkedHashSet();
      for (String zone : availabilityZoneToRegionMap.keySet()) {
         Location region = new LocationImpl(LocationScope.REGION, availabilityZoneToRegionMap
                  .get(zone), availabilityZoneToRegionMap.get(zone), ec2);
         locations.add(region);
         locations.add(new LocationImpl(LocationScope.ZONE, zone, zone, region));
      }
      return locations;
   }

   @Provides
   @Singleton
   Location getDefaultLocation(@EC2 final String region, Set<? extends Location> set) {
      return Iterables.find(set, new Predicate<Location>() {

         @Override
         public boolean apply(Location input) {
            return input.getId().equals(region);
         }

      });
   }

   private static class LogHolder {
      @Resource
      @Named(ComputeServiceConstants.COMPUTE_LOGGER)
      protected Logger logger = Logger.NULL;
   }

   @Provides
   @Singleton
   @Named(PROPERTY_EC2_AMI_OWNERS)
   String[] amiOwners(@Named(PROPERTY_EC2_AMI_OWNERS) String amiOwners) {
      return Iterables.toArray(Splitter.on(',').split(amiOwners), String.class);
   }

   @Provides
   @Singleton
   protected Set<? extends Image> provideImages(final EC2Client sync,
            @EC2 Map<String, URI> regionMap, LogHolder holder,
            Function<ComputeMetadata, String> indexer,
            @Named(PROPERTY_EC2_AMI_OWNERS) String[] amiOwners, ImageParser parser)
            throws InterruptedException, ExecutionException, TimeoutException {
      final Set<Image> images = Sets.newHashSet();
      holder.logger.debug(">> providing images");

      for (final String region : regionMap.keySet()) {
         for (final org.jclouds.aws.ec2.domain.Image from : sync.getAMIServices()
                  .describeImagesInRegion(region, ownedBy(amiOwners))) {
            Image image = parser.apply(from);
            if (image != null)
               images.add(image);
            else
               holder.logger.trace("<< image(%s) didn't parse", from.getId());
         }
      }
      holder.logger.debug("<< images(%d)", images.size());
      return images;
   }
}
