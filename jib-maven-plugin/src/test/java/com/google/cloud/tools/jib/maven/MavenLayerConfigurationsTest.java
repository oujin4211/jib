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

package com.google.cloud.tools.jib.maven;

import com.google.cloud.tools.jib.filesystem.AbsoluteUnixPath;
import com.google.cloud.tools.jib.frontend.JavaLayerConfigurations;
import com.google.cloud.tools.jib.image.LayerEntry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

/** Tests for {@link MavenLayerConfigurations}. */
@RunWith(MockitoJUnitRunner.class)
public class MavenLayerConfigurationsTest {

  private static ImmutableList<Path> getSourceFilesFromLayerEntries(
      ImmutableList<LayerEntry> layerEntries) {
    return layerEntries
        .stream()
        .map(LayerEntry::getSourceFile)
        .collect(ImmutableList.toImmutableList());
  }

  private static List<String> getExtractionPathFromLayerEntries(List<LayerEntry> layerEntries) {
    Stream<LayerEntry> stream = layerEntries.stream();
    return stream.map(LayerEntry::getAbsoluteExtractionPathString).collect(Collectors.toList());
  }

  @Rule public TestRepository testRepository = new TestRepository();

  @Mock private MavenProject mockMavenProject;
  @Mock private Build mockBuild;

  @Before
  public void setUp() throws URISyntaxException {
    Path sourcePath = Paths.get(Resources.getResource("application/source").toURI());
    Path outputPath = Paths.get(Resources.getResource("application/output").toURI());

    Mockito.when(mockMavenProject.getBuild()).thenReturn(mockBuild);
    Mockito.when(mockBuild.getSourceDirectory()).thenReturn(sourcePath.toString());
    Mockito.when(mockBuild.getOutputDirectory()).thenReturn(outputPath.toString());

    Set<Artifact> artifacts =
        ImmutableSet.of(
            makeArtifact(Paths.get("application", "dependencies", "libraryB.jar")),
            makeArtifact(Paths.get("application", "dependencies", "libraryA.jar")),
            // maven reads and populates "Artifacts" with it's own processing, so read some from
            // a repository
            testRepository.findArtifact("com.test", "dependency", "1.0.0"),
            testRepository.findArtifact("com.test", "dependencyX", "1.0.0-SNAPSHOT"));
    Mockito.when(mockMavenProject.getArtifacts()).thenReturn(artifacts);
  }

  @Test
  public void test_correctFiles() throws URISyntaxException, IOException {
    ImmutableList<Path> expectedDependenciesFiles =
        // on windows, these files may be in a different order, so sort
        ImmutableList.sortedCopyOf(
            ImmutableList.of(
                testRepository.artifactPathOnDisk("com.test", "dependency", "1.0.0"),
                Paths.get("application", "dependencies", "libraryA.jar"),
                Paths.get("application", "dependencies", "libraryB.jar")));
    ImmutableList<Path> expectedSnapshotDependenciesFiles =
        ImmutableList.of(
            testRepository.artifactPathOnDisk("com.test", "dependencyX", "1.0.0-SNAPSHOT"));
    Path applicationDirectory = Paths.get(Resources.getResource("application").toURI());
    ImmutableList<Path> expectedResourcesFiles =
        ImmutableList.of(
            applicationDirectory.resolve("output/directory"),
            applicationDirectory.resolve("output/directory/somefile"),
            applicationDirectory.resolve("output/resourceA"),
            applicationDirectory.resolve("output/resourceB"),
            applicationDirectory.resolve("output/world"));
    ImmutableList<Path> expectedClassesFiles =
        ImmutableList.of(
            applicationDirectory.resolve("output/HelloWorld.class"),
            applicationDirectory.resolve("output/package"),
            applicationDirectory.resolve("output/package/some.class"),
            applicationDirectory.resolve("output/some.class"));

    JavaLayerConfigurations javaLayerConfigurations =
        MavenLayerConfigurations.getForProject(
            mockMavenProject, Paths.get("nonexistent/path"), AbsoluteUnixPath.get("/app"));
    Assert.assertEquals(
        expectedDependenciesFiles,
        getSourceFilesFromLayerEntries(javaLayerConfigurations.getDependencyLayerEntries()));
    Assert.assertEquals(
        expectedSnapshotDependenciesFiles,
        getSourceFilesFromLayerEntries(
            javaLayerConfigurations.getSnapshotDependencyLayerEntries()));
    Assert.assertEquals(
        expectedResourcesFiles,
        getSourceFilesFromLayerEntries(javaLayerConfigurations.getResourceLayerEntries()));
    Assert.assertEquals(
        expectedClassesFiles,
        getSourceFilesFromLayerEntries(javaLayerConfigurations.getClassLayerEntries()));
  }

  @Test
  public void test_extraFiles() throws URISyntaxException, IOException {
    Path extraFilesDirectory = Paths.get(Resources.getResource("layer").toURI());

    AbsoluteUnixPath appRoot = AbsoluteUnixPath.get("/app");
    JavaLayerConfigurations javaLayerConfigurations =
        MavenLayerConfigurations.getForProject(mockMavenProject, extraFilesDirectory, appRoot);

    ImmutableList<Path> expectedExtraFiles =
        ImmutableList.of(
            extraFilesDirectory.resolve("a"),
            extraFilesDirectory.resolve("a/b"),
            extraFilesDirectory.resolve("a/b/bar"),
            extraFilesDirectory.resolve("c"),
            extraFilesDirectory.resolve("c/cat"),
            extraFilesDirectory.resolve("foo"));

    Assert.assertEquals(
        expectedExtraFiles,
        getSourceFilesFromLayerEntries(javaLayerConfigurations.getExtraFilesLayerEntries()));
  }

  @Test
  public void testGetForProject_nonDefaultAppRoot() throws URISyntaxException, IOException {
    Path extraFilesDirectory = Paths.get(Resources.getResource("layer").toURI());

    AbsoluteUnixPath appRoot = AbsoluteUnixPath.get("/my/app");
    JavaLayerConfigurations configuration =
        MavenLayerConfigurations.getForProject(mockMavenProject, extraFilesDirectory, appRoot);

    Assert.assertEquals(
        // on windows, these files may be in a different order, so use Set
        new HashSet<>(
            Arrays.asList(
                "/my/app/libs/dependency-1.0.0.jar",
                "/my/app/libs/libraryA.jar",
                "/my/app/libs/libraryB.jar")),
        new HashSet<>(
            getExtractionPathFromLayerEntries(configuration.getDependencyLayerEntries())));
    Assert.assertEquals(
        Arrays.asList("/my/app/libs/dependencyX-1.0.0-SNAPSHOT.jar"),
        getExtractionPathFromLayerEntries(configuration.getSnapshotDependencyLayerEntries()));
    Assert.assertEquals(
        Arrays.asList(
            "/my/app/resources/directory",
            "/my/app/resources/directory/somefile",
            "/my/app/resources/resourceA",
            "/my/app/resources/resourceB",
            "/my/app/resources/world"),
        getExtractionPathFromLayerEntries(configuration.getResourceLayerEntries()));
    Assert.assertEquals(
        Arrays.asList(
            "/my/app/classes/HelloWorld.class",
            "/my/app/classes/package",
            "/my/app/classes/package/some.class",
            "/my/app/classes/some.class"),
        getExtractionPathFromLayerEntries(configuration.getClassLayerEntries()));
    Assert.assertEquals(
        Arrays.asList("/a", "/a/b", "/a/b/bar", "/c", "/c/cat", "/foo"),
        getExtractionPathFromLayerEntries(configuration.getExtraFilesLayerEntries()));
  }

  private Artifact makeArtifact(Path path) {
    Artifact artifact = Mockito.mock(Artifact.class);
    Mockito.when(artifact.getFile()).thenReturn(path.toFile());
    return artifact;
  }
}
