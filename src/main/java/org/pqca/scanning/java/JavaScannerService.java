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
import java.util.List;
import org.cyclonedx.model.Bom;
import org.pqca.errors.ProjectNotBuilt;
import org.pqca.indexing.ProjectModule;
import org.pqca.scanning.ScannerService;
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
    private static final JavaVersion JAVA_VERSION =
            new JavaVersionImpl(JavaVersionImpl.MAX_SUPPORTED);

    @Nonnull private final String getJavaDependencyJARSPath;

    public JavaScannerService(
            @Nonnull String getJavaDependencyJARSPath, @Nonnull File projectDirectory) {
        super(projectDirectory);
        this.getJavaDependencyJARSPath = getJavaDependencyJARSPath;
    }

    @Override
    @Nonnull
    public synchronized Bom scan(@Nonnull List<ProjectModule> index) {
        final File targetJarClasses = new File(this.projectDirectory, "target/classes");
        if (!targetJarClasses.exists()) {
            throw new ProjectNotBuilt(this.projectDirectory);
        }

        final SensorContextTester sensorContext = SensorContextTester.create(this.projectDirectory);
        sensorContext.setSettings(
                new MapSettings()
                        .setProperty(SonarComponents.SONAR_BATCH_MODE_KEY, true)
                        .setProperty("sonar.java.libraries", this.getJavaDependencyJARSPath)
                        .setProperty("sonar.java.binaries", targetJarClasses.toString())
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
}
