// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.android;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;

/**
 * Represents a container for the {@link ResourceContainer}s for a given library. This is
 * abstraction simplifies the process of managing and exporting the direct and transitive resource
 * dependencies of an android rule, as well as providing type safety.
 *
 * <p>The transitive and direct dependencies are not guaranteed to be disjoint. If a 
 * library is included in both the transitive and direct dependencies, it will appear twice. This
 * requires consumers to manage duplicated resources gracefully.
 */
@Immutable
public final class ResourceDependencies {
  /**
   * Contains all the transitive resources that are not generated by the direct ancestors of the
   * current rule.
   */
  private final NestedSet<ResourceContainer> transitiveResources;
  /**
   * Contains all the direct dependencies of the current target. Since a given direct dependency can
   * act as a "forwarding" library, collecting all the direct resource from it's dependencies 
   * and providing them as "direct" dependencies to maintain merge order, this uses a NestedSet to
   * properly maintain ordering and ease of merging.
   */
  private final NestedSet<ResourceContainer> directResources;
  /**
   * Whether the resources of the current rule should be treated as neverlink.
   */
  private final boolean neverlink;

  public static ResourceDependencies fromRuleResources(RuleContext ruleContext, boolean neverlink) {
    if (!hasResourceAttribute(ruleContext)) {
      return empty();
    }

    NestedSetBuilder<ResourceContainer> transitiveDependencies = NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<ResourceContainer> directDependencies = NestedSetBuilder.naiveLinkOrder();
    extractFromAttributes(
        ImmutableList.of("resources"), ruleContext, transitiveDependencies, directDependencies);
    return new ResourceDependencies(neverlink,
        transitiveDependencies.build(), directDependencies.build());
  }

  public static ResourceDependencies fromRuleDeps(RuleContext ruleContext, boolean neverlink) {
    NestedSetBuilder<ResourceContainer> transitiveDependencies = NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<ResourceContainer> directDependencies = NestedSetBuilder.naiveLinkOrder();
    extractFromAttributes(AndroidCommon.TRANSITIVE_ATTRIBUTES, ruleContext, transitiveDependencies,
        directDependencies);
    return new ResourceDependencies(neverlink,
        transitiveDependencies.build(), directDependencies.build());
  }

  public static ResourceDependencies fromRuleResourceAndDeps(RuleContext ruleContext,
      boolean neverlink) {
    NestedSetBuilder<ResourceContainer> transitiveDependencies = NestedSetBuilder.naiveLinkOrder();
    NestedSetBuilder<ResourceContainer> directDependencies = NestedSetBuilder.naiveLinkOrder();
    if (hasResourceAttribute(ruleContext)) {
      extractFromAttributes(
          ImmutableList.of("resources"), ruleContext, transitiveDependencies, directDependencies);
    }
    if (directDependencies.isEmpty()) {
      // There are no resources, so this library will forward the direct and transitive dependencies
      // without changes.
      extractFromAttributes(AndroidCommon.TRANSITIVE_ATTRIBUTES, ruleContext,
          transitiveDependencies, directDependencies);
    } else {
      // There are resources, so the direct dependencies and the transitive will be merged into
      // the transitive dependencies. This maintains the relationship of the resources being
      // directly on the rule.
      extractFromAttributes(AndroidCommon.TRANSITIVE_ATTRIBUTES, ruleContext,
          transitiveDependencies, transitiveDependencies);
    }
    return new ResourceDependencies(neverlink,
        transitiveDependencies.build(), directDependencies.build());
  }

  private static void extractFromAttributes(Iterable<String> attributeNames,
      RuleContext ruleContext, NestedSetBuilder<ResourceContainer> builderForTransitive,
      NestedSetBuilder<ResourceContainer> builderForDirect) {
    AttributeMap attributes = ruleContext.attributes();
    for (String attr : attributeNames) {
      if (!attributes.has(attr, BuildType.LABEL_LIST) && !attributes.has(attr, BuildType.LABEL)) {
        continue;
      }
      for (AndroidResourcesProvider resources :
          ruleContext.getPrerequisites(attr, Mode.TARGET, AndroidResourcesProvider.class)) {
        builderForTransitive.addTransitive(resources.getTransitiveAndroidResources());
        builderForDirect.addTransitive(resources.getDirectAndroidResources());
      }
    }
  }

  /**
   * Check for the existence of a "resources" attribute.
   *
   * <p>The existence of the resources attribute is not guaranteed on for all android rules, so it
   * is necessary to check for it.
   *
   * @param ruleContext The context to check.
   * @return True if the ruleContext has resources, otherwise, false.
   */
  private static boolean hasResourceAttribute(RuleContext ruleContext) {
    return ruleContext.attributes().has("resources", BuildType.LABEL);
  }

  @Override
  public String toString() {
    return "ResourceDependencies [transitiveResources=" + transitiveResources + ", directResources="
        + directResources + "]";
  }

  /**
   * Creates an empty ResourceDependencies instance. This is used when an AndroidResources rule
   * is the only resource dependency. The most common case is the AndroidTest rule.
   */
  public static ResourceDependencies empty() {
    return new ResourceDependencies(false,
        NestedSetBuilder.<ResourceContainer>emptySet(Order.NAIVE_LINK_ORDER),
        NestedSetBuilder.<ResourceContainer>emptySet(Order.NAIVE_LINK_ORDER));
  }

  private ResourceDependencies(
      boolean neverlink,
      NestedSet<ResourceContainer> transitiveResources,
      NestedSet<ResourceContainer> directResources) {
    this.neverlink = neverlink;
    this.transitiveResources = transitiveResources;
    this.directResources = directResources;
  }

  /**
   * Creates a new AndroidResourcesProvider with the supplied ResourceContainer as the direct dep.
   *
   * <p>When a library produces a new resource container the AndroidResourcesProvider should use
   * that container as a the direct dependency for that library. This makes the consuming rule
   * to identify the new container and merge appropriately. The  previous direct dependencies are
   * then added to the transitive dependencies.
   *
   * @param label The label of the library exporting this provider.
   * @param newDirectResource The new direct dependency for AndroidResourcesProvider
   * @return A provider with the current resources and label.
   */
  public AndroidResourcesProvider toProvider(Label label, ResourceContainer newDirectResource) {
    if (neverlink) {
      return ResourceDependencies.empty().toProvider(label);
    }
    return AndroidResourcesProvider.create(
        label,
        NestedSetBuilder.<ResourceContainer>naiveLinkOrder()
            .addTransitive(transitiveResources)
            .addTransitive(directResources)
            .build(),
        NestedSetBuilder.<ResourceContainer>naiveLinkOrder().add(newDirectResource).build());
  }

  /**
   * Create a new AndroidResourcesProvider from the dependencies of this library.
   *
   * <p>When a library doesn't export resources it should simply forward the current transitive and
   * direct resources to the consuming rule. This allows the consuming rule to make decisions about
   * the resource merging as if this library didn't exist.
   *
   * @param label The label of the library exporting this provider.
   * @return A provider with the current resources and label.
   */
  public AndroidResourcesProvider toProvider(Label label) {
    if (neverlink) {
      return ResourceDependencies.empty().toProvider(label);
    }
    return AndroidResourcesProvider.create(label, transitiveResources, directResources);
  }

  /**
   * Provides an NestedSet of the direct and transitive resources.
   */
  public Iterable<ResourceContainer> getResources() {
    return NestedSetBuilder.<ResourceContainer>naiveLinkOrder()
        .addTransitive(directResources)
        .addTransitive(transitiveResources)
        .build();
  }

  public NestedSet<ResourceContainer> getTransitiveResources() {
    return transitiveResources;
  }

  public NestedSet<ResourceContainer> getDirectResources() {
    return directResources;
  }
}
