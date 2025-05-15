/*
 * CBOMkit-action
 * Copyright (C) 2024 PQCA
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pqca.scanning.java;

import jakarta.annotation.Nonnull;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.cyclonedx.model.Bom;
import org.pqca.indexing.ProjectModule;
import org.pqca.scanning.ScannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.internal.DefaultFileSystem;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.measures.FileLinesContext;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.java.DefaultJavaResourceLocator;
import org.sonar.java.JavaFrontend;
import org.sonar.java.SonarComponents;
import org.sonar.java.classpath.ClasspathForMain;
import org.sonar.java.classpath.ClasspathForTest;
import org.sonar.java.model.JavaVersionImpl;
import org.sonar.plugins.java.api.JavaResourceLocator;
import org.sonar.plugins.java.api.JavaVersion;

public final class JavaScannerService extends ScannerService {
    private static final Logger LOG = LoggerFactory.getLogger(JavaScannerService.class);

    private static final JavaVersion JAVA_VERSION =
            new JavaVersionImpl(JavaVersionImpl.MAX_SUPPORTED);

    @Nonnull private final String javaDependencyJars;
    @Nonnull private final String targetClassDirectories;

    public JavaScannerService(@Nonnull File projectDirectory) {
        super(projectDirectory);

        this.javaDependencyJars = getJavaDependencyJARs();
        this.targetClassDirectories = getJavaClassDirPatterns();

        String requireBuildStr = System.getenv("CBOMKIT_JAVA_REQUIRE_BUILD");
        boolean requireBuild = requireBuildStr != null ? Boolean.valueOf(requireBuildStr) : true;
        if (!projectIsBuilt()) {
            if (requireBuild) {
                throw new IllegalStateException(
                        "No Java build artifacts found. Propject must be build prior to scanning");
            } else {
                LOG.warn(
                        "No Java build artifacts found. Scanning Java code without prior build may produce less accurate CBOMs.");
            }
        }
    }

    @Override
    @Nonnull
    public synchronized Bom scan(@Nonnull List<ProjectModule> index) {
        final SensorContextTester sensorContext = SensorContextTester.create(projectDirectory);
        sensorContext.setSettings(
                new MapSettings()
                        .setProperty(SonarComponents.SONAR_BATCH_MODE_KEY, true)
                        // .setProperty("sonar.java.jdkHome", System.getProperty("java.home"))
                        .setProperty("sonar.java.libraries", javaDependencyJars)
                        .setProperty("sonar.java.binaries", targetClassDirectories)
                        .setProperty(SonarComponents.SONAR_AUTOSCAN, false)
                        .setProperty(SonarComponents.SONAR_BATCH_SIZE_KEY, 8 * 1024 * 1024));
        final DefaultFileSystem fileSystem = sensorContext.fileSystem();
        final ClasspathForMain classpathForMain =
                new ClasspathForMain(sensorContext.config(), fileSystem);
        final ClasspathForTest classpathForTest =
                new ClasspathForTest(sensorContext.config(), fileSystem);
        final SonarComponents sonarComponents =
                getSonarComponents(fileSystem, classpathForMain, classpathForTest);
        sonarComponents.setSensorContext(sensorContext);
        LOGGER.info("Start scanning {} java projects", index.size());

        final JavaResourceLocator javaResourceLocator =
                new DefaultJavaResourceLocator(classpathForMain, classpathForTest);
        final JavaFrontend javaFrontend =
                new JavaFrontend(
                        JAVA_VERSION,
                        sonarComponents,
                        null,
                        javaResourceLocator,
                        null,
                        new JavaDetectionCollectionRule(this));

        int counter = 1;
        for (ProjectModule project : index) {
            final String projectStr =
                    project.identifier() + " (" + counter + "/" + index.size() + ")";
            LOGGER.info("Scanning project " + projectStr);

            javaFrontend.scan(project.inputFileList(), List.of(), List.of());
            counter++;
        }

        return this.getBOM();
    }

    @Nonnull
    private static SonarComponents getSonarComponents(
            DefaultFileSystem fileSystem,
            ClasspathForMain classpathForMain,
            ClasspathForTest classpathForTest) {
        final FileLinesContextFactory fileLinesContextFactory =
                inputFile ->
                        new FileLinesContext() {
                            @Override
                            public void setIntValue(@Nonnull String s, int i, int i1) {
                                // nothing
                            }

                            @Override
                            public void setStringValue(
                                    @Nonnull String s, int i, @Nonnull String s1) {
                                // nothing
                            }

                            @Override
                            public void save() {
                                // nothing
                            }
                        };
        return new SonarComponents(
                fileLinesContextFactory,
                fileSystem,
                classpathForMain,
                classpathForTest,
                null,
                null);
    }

    private String getJavaDependencyJARs() {
        String javaDependencyJars = System.getenv("CBOMKIT_JAVA_JARS");
        if (javaDependencyJars != null) {
            LOG.info("CBOMKIT_JAVA_JARS: {}", javaDependencyJars);
            return javaDependencyJars;
        }

        List<String> jars = new ArrayList<String>();
        addJarPattern(jars, projectDirectory.getAbsolutePath());
        addJarPattern(jars, System.getProperty("user.home") + "/.m2/repository");
        addJarPattern(jars, System.getProperty("user.home") + "/.gradle");
        addJarPattern(jars, System.getenv("CBOMKIT_JAVA_JAR_DIR"));

        LOG.info("CBOMKIT_JAVA_JARS not set. Default pattern: {}", jars);
        return String.join(",", jars);
    }

    private static void addJarPattern(List<String> jarPatterns, String dir) {
        try {
            File javaJarDir = (new File(dir)).getCanonicalFile();
            if (!javaJarDir.exists() || !javaJarDir.isDirectory()) {
                LOG.warn("Jar directory does not exists: {}", javaJarDir);
                return;
            }

            jarPatterns.add(javaJarDir.getAbsolutePath() + "/**/*.jar");
            jarPatterns.add(javaJarDir.getAbsolutePath() + "/**/*.zip");
        } catch (Exception e) {
            LOG.error("Failed to contruct jar pattern from {}", dir);
        }
    }

    private String getJavaClassDirPatterns() {
        String javaClasses = System.getenv("CBOMKIT_JAVA_CLASSES");
        if (javaClasses != null) {
            LOG.info("CBOMKIT_JAVA_CLASSES: {}", javaClasses);
            return javaClasses;
        }

        try {
            javaClasses =
                    this.projectDirectory.getCanonicalFile().getAbsolutePath() + "/**/classes";
            LOG.info("CBOMKIT_JAVA_CLASSES not set. Default pattern: {}", javaClasses);
            return javaClasses;
        } catch (Exception e) {
            return "";
        }
    }

    private boolean projectIsBuilt() {
        try (Stream<Path> walk = Files.walk(this.projectDirectory.toPath())) {
            return !walk.filter(p -> p.endsWith("classes") && Files.isDirectory(p))
                    .toList().isEmpty();
        } catch (Exception e) {
            LOG.error(e.getMessage());
            return false;
        }
    }
}
