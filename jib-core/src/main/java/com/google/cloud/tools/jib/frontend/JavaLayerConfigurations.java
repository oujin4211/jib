/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.frontend;

import com.google.cloud.tools.jib.configuration.ContainerConfiguration;
import com.google.cloud.tools.jib.configuration.LayerConfiguration;
import com.google.cloud.tools.jib.image.LayerEntry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Builds {@link LayerConfiguration}s for a Java application. */
public class JavaLayerConfigurations {

  /** Represents the different types of layers for a Java application. */
  @VisibleForTesting
  enum LayerType {
    DEPENDENCIES(
        "dependencies", JavaEntrypointConstructor.DEFAULT_DEPENDENCIES_PATH_ON_IMAGE, true),
    SNAPSHOT_DEPENDENCIES(
        "snapshot dependencies",
        JavaEntrypointConstructor.DEFAULT_DEPENDENCIES_PATH_ON_IMAGE,
        true),
    RESOURCES("resources", JavaEntrypointConstructor.DEFAULT_RESOURCES_PATH_ON_IMAGE, true),
    CLASSES("classes", JavaEntrypointConstructor.DEFAULT_CLASSES_PATH_ON_IMAGE, true),
    // TODO: remove this once we put files in WAR into the relevant layers (i.e., dependencies,
    // snapshot dependencies, resources, and classes layers). Should copy files in the right
    EXPLODED_WAR("exploded war", "", true),
    EXTRA_FILES("extra files", "/", false);

    private final String name;
    private final Path extractionPath;
    private final boolean appRootRelative;

    /** Initializes with a name for the layer and the layer files' default extraction path root. */
    LayerType(String name, String extractionPath, boolean appRootRelative) {
      this.name = name;
      this.extractionPath = Paths.get(extractionPath);
      this.appRootRelative = appRootRelative;
    }

    @VisibleForTesting
    String getName() {
      return name;
    }

    private Path getExtractionPath() {
      return extractionPath;
    }

    private boolean isAppRootRelative() {
      return appRootRelative;
    }
  }

  /** Builds with each layer's files. */
  public static class Builder {

    private final Map<LayerType, List<Path>> layerFilesMap = new EnumMap<>(LayerType.class);
    private String appRoot = ContainerConfiguration.DEFAULT_APP_ROOT;

    private Builder() {
      for (LayerType layerType : LayerType.values()) {
        layerFilesMap.put(layerType, new ArrayList<>());
      }
    }

    public Builder setAppRoot(String appRoot) {
      this.appRoot = appRoot;
      return this;
    }

    public Builder setDependencyFiles(List<Path> dependencyFiles) {
      layerFilesMap.put(LayerType.DEPENDENCIES, dependencyFiles);
      return this;
    }

    public Builder setSnapshotDependencyFiles(List<Path> snapshotDependencyFiles) {
      layerFilesMap.put(LayerType.SNAPSHOT_DEPENDENCIES, snapshotDependencyFiles);
      return this;
    }

    public Builder setResourceFiles(List<Path> resourceFiles) {
      layerFilesMap.put(LayerType.RESOURCES, resourceFiles);
      return this;
    }

    public Builder setClassFiles(List<Path> classFiles) {
      layerFilesMap.put(LayerType.CLASSES, classFiles);
      return this;
    }

    public Builder setExtraFiles(List<Path> extraFiles) {
      layerFilesMap.put(LayerType.EXTRA_FILES, extraFiles);
      return this;
    }

    // TODO: remove this and put files in WAR into the relevant layers (i.e., dependencies, snapshot
    // dependencies, resources, and classes layers).
    public Builder setExplodedWarFiles(List<Path> explodedWarFiles) {
      layerFilesMap.put(LayerType.EXPLODED_WAR, explodedWarFiles);
      return this;
    }

    public JavaLayerConfigurations build() throws IOException {
      // Windows filenames cannot have "/", so this also blocks Windows-style path.
      Preconditions.checkState(
          appRoot.startsWith("/"), "appRoot should be an absolute path in Unix-style");
      Path appRootPath = Paths.get(appRoot);

      ImmutableMap.Builder<LayerType, LayerConfiguration> layerConfigurationsMap =
          ImmutableMap.builderWithExpectedSize(LayerType.values().length);
      for (LayerType layerType : LayerType.values()) {
        LayerConfiguration.Builder layerConfigurationBuilder =
            LayerConfiguration.builder().setName(layerType.getName());

        // Adds all the layer files recursively.
        List<Path> layerFiles = Preconditions.checkNotNull(layerFilesMap.get(layerType));
        for (Path layerFile : layerFiles) {
          Path toExtract = layerType.getExtractionPath().resolve(layerFile.getFileName());
          Path pathInContainer =
              layerType.isAppRootRelative() ? appRootPath.resolve(toExtract) : toExtract;
          layerConfigurationBuilder.addEntryRecursive(layerFile, pathInContainer);
        }

        layerConfigurationsMap.put(layerType, layerConfigurationBuilder.build());
      }
      return new JavaLayerConfigurations(appRoot, layerConfigurationsMap.build());
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  private final ImmutableMap<LayerType, LayerConfiguration> layerConfigurationMap;
  private final String appRoot;

  private JavaLayerConfigurations(
      String appRoot, ImmutableMap<LayerType, LayerConfiguration> layerConfigurationsMap) {
    this.appRoot = appRoot;
    layerConfigurationMap = layerConfigurationsMap;
  }

  /**
   * Returns the Unix-style, absolute path for the application root in the container image. May or
   * may not end with a trailing forward slash ('/').
   *
   * @return Unix-style, absolute path for the application root
   */
  public String getAppRoot() {
    return appRoot;
  }

  public ImmutableList<LayerConfiguration> getLayerConfigurations() {
    return layerConfigurationMap.values().asList();
  }

  public ImmutableList<LayerEntry> getDependencyLayerEntries() {
    return getLayerEntries(LayerType.DEPENDENCIES);
  }

  public ImmutableList<LayerEntry> getSnapshotDependencyLayerEntries() {
    return getLayerEntries(LayerType.SNAPSHOT_DEPENDENCIES);
  }

  public ImmutableList<LayerEntry> getResourceLayerEntries() {
    return getLayerEntries(LayerType.RESOURCES);
  }

  public ImmutableList<LayerEntry> getClassLayerEntries() {
    return getLayerEntries(LayerType.CLASSES);
  }

  public ImmutableList<LayerEntry> getExtraFilesLayerEntries() {
    return getLayerEntries(LayerType.EXTRA_FILES);
  }

  // TODO: remove this once we put files in WAR into the relevant layers (i.e., dependencies,
  // snapshot dependencies, resources, and classes layers). Should copy files in the right
  public ImmutableList<LayerEntry> getExplodedWarEntries() {
    return getLayerEntries(LayerType.EXPLODED_WAR);
  }

  private ImmutableList<LayerEntry> getLayerEntries(LayerType layerType) {
    return Preconditions.checkNotNull(layerConfigurationMap.get(layerType)).getLayerEntries();
  }
}
