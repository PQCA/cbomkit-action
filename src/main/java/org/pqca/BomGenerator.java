/*
 * CBOMkit-action
 * Copyright (C) 2025 PQCA
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
package org.pqca;

import jakarta.annotation.Nonnull;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.cyclonedx.Version;
import org.cyclonedx.exception.GeneratorException;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.OrganizationalEntity;
import org.cyclonedx.model.Property;
import org.cyclonedx.model.Service;
import org.cyclonedx.model.metadata.ToolInformation;
import org.pqca.errors.CoulNotFindJavaClassDirs;
import org.pqca.errors.CouldNotLoadJavaJars;
import org.pqca.errors.ProjectNotBuilt;
import org.pqca.indexing.JavaIndexService;
import org.pqca.indexing.ProjectModule;
import org.pqca.indexing.PythonIndexService;
import org.pqca.packages.JavaPackageFinderService;
import org.pqca.packages.PackageMetadata;
import org.pqca.packages.PythonPackageFinderService;
import org.pqca.scanning.java.JavaScannerService;
import org.pqca.scanning.python.PythonScannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BomGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final String ACTION_NAME = "CBOMkit-action";
    private static final String ACTION_ORG = "PQCA";

    @Nonnull private final File projectDirectory;
    @Nonnull private final File outputDir;

    public BomGenerator(@Nonnull File projectDirectory, File outputDir) {
        this.projectDirectory = projectDirectory;
        this.outputDir = outputDir;
    }

    @Nonnull
    private String getJavaDependencyJARSPath() {
        File javaJarDir =
                Optional.ofNullable(System.getenv("CBOMKIT_JAVA_JAR_DIR"))
                        .map(relativeDir -> new File(relativeDir))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Could not load jar dependencies for java scanning")); // Error
        if (javaJarDir.exists() && javaJarDir.isDirectory()) {
            return javaJarDir.getAbsolutePath();
        }

        throw new IllegalStateException(
                "Jar dependencies dir for java scanning does not exist or is not directory");
    }

    @Nonnull
    private List<String> getJavaClassDirectories() {
        try (Stream<Path> walk = Files.walk(this.projectDirectory.toPath())) {
            return walk.filter(p -> p.endsWith("target/classes") && Files.isDirectory(p))
                    .map(p -> p.toString())
                    .toList();
        } catch (Exception e) {
            LOG.error("Failed to find class directories: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    @Nonnull
    public List<Bom> generateJavaBoms() throws CouldNotLoadJavaJars, CoulNotFindJavaClassDirs {
        final String javaJarDir = getJavaDependencyJARSPath();
        final List<String> targetClassDirs = getJavaClassDirectories();
        if (targetClassDirs.isEmpty()) {
            throw new CoulNotFindJavaClassDirs();
        }

        final JavaIndexService javaIndexService = new JavaIndexService(projectDirectory);
        final List<ProjectModule> javaProjectModules = javaIndexService.index(null);
        final List<Bom> javaBoms = new ArrayList<>();
        final JavaPackageFinderService packageFinder =
                new JavaPackageFinderService(projectDirectory);
        for (PackageMetadata pm : packageFinder.findPackages()) {
            final List<ProjectModule> packageModules =
                    getPackageModules(javaProjectModules, pm.packageDir());
            if (!packageModules.isEmpty()) {
                LOG.info("Scanning java package {}", pm.packageDir());
                final JavaScannerService javaScannerService =
                        new JavaScannerService(javaJarDir, targetClassDirs, pm.packageDir());
                try {
                    final Bom javaBom = javaScannerService.scan(packageModules);
                    writeBom(pm, javaBom);
                    javaBoms.add(javaBom);
                } catch (ProjectNotBuilt pnb) {
                    LOG.error(
                            "Package {} not built - skipping CBOM scan", pnb.getProjectDirectory());
                }
            }
        }

        return javaBoms;

        //     final JavaScannerService javaScannerService =
        //             new JavaScannerService(javaJarDir, projectDirectory);
        //     return javaScannerService.scan(javaProjectModules);
    }

    @Nonnull
    public List<Bom> generatePythonBoms() {
        final PythonIndexService pythonIndexService = new PythonIndexService(projectDirectory);
        final List<ProjectModule> pythonProjectModules = pythonIndexService.index(null);
        final List<Bom> pythonBoms = new ArrayList<>();
        final PythonPackageFinderService packageFinder =
                new PythonPackageFinderService(projectDirectory);
        for (PackageMetadata pm : packageFinder.findPackages()) {
            final List<ProjectModule> packageModules =
                    getPackageModules(pythonProjectModules, pm.packageDir());
            if (!packageModules.isEmpty()) {
                LOG.info("Scanning python package {}", pm.packageDir());
                final PythonScannerService pythonScannerService =
                        new PythonScannerService(pm.packageDir());
                final Bom pythonBom = pythonScannerService.scan(packageModules);
                writeBom(pm, pythonBom);
                pythonBoms.add(pythonBom);
            }
        }
        return pythonBoms;

        // final PythonScannerService pythonScannerService =
        //         new PythonScannerService(projectDirectory);
        // return pythonScannerService.scan(pythonProjectModules);
    }

    private List<ProjectModule> getPackageModules(List<ProjectModule> allModules, File packageDir) {
        return allModules.stream()
                .filter(
                        pm ->
                                projectDirectory
                                        .toPath()
                                        .resolve(pm.identifier())
                                        .equals(packageDir.toPath()))
                .toList();
    }

    public void writeBom(Bom bom) {
        writeBom(new PackageMetadata(projectDirectory, null), bom);
    }

    private void writeBom(PackageMetadata packageMetadata, Bom bom) {
        bom.setMetadata(generateMetadata(packageMetadata));

        final BomJsonGenerator bomGenerator =
                BomGeneratorFactory.createJson(Version.VERSION_16, bom);

        try {
            String bomString = bomGenerator.toJsonString();
            if (bomString == null) {
                LOG.error("Empty CBOM");
            } else {
                int numFindings = 0;
                if (bom.getComponents() != null) {
                    for (Component c : bom.getComponents()) {
                        numFindings += c.getEvidence().getOccurrences().size();
                    }
                }

                if ("".equals(packageMetadata.name())) {
                    LOG.info(
                            "Writing {} top-level findings into consolidated {}/cbom.json",
                            numFindings,
                            this.outputDir);
                    return;
                }

                final String fileName = packageMetadata.getCbomFileName();
                final File cbomFile = new File(this.outputDir, fileName);
                LOG.info("Writing cbom {} with {} findings", cbomFile, numFindings);

                try (FileWriter writer = new FileWriter(cbomFile)) {
                    writer.write(bomString);
                }
            }
        } catch (IOException | GeneratorException e) {
            LOG.error(e.getMessage(), e);
        }
    }

    private Metadata generateMetadata(PackageMetadata packageMetadata) {
        final Metadata metadata = new Metadata();
        metadata.setTimestamp(new Date());

        final ToolInformation scannerInfo = new ToolInformation();
        final Service scannerService = new Service();
        scannerService.setName(ACTION_NAME);

        final OrganizationalEntity organization = new OrganizationalEntity();
        organization.setName(ACTION_ORG);
        scannerService.setProvider(organization);
        scannerInfo.setServices(List.of(scannerService));
        metadata.setToolChoice(scannerInfo);

        final String gitServer = System.getenv("GITHUB_SERVER_URL");
        final String gitUrl = System.getenv("GITHUB_REPOSITORY");
        if (gitServer != null && gitUrl != null) {
            final Property gitUrlProperty = new Property();
            gitUrlProperty.setName("gitUrl");
            gitUrlProperty.setValue(gitServer + "/" + gitUrl);
            metadata.addProperty(gitUrlProperty);
        }

        final String revision = System.getenv("GITHUB_REF_NAME");
        if (revision != null) {
            final Property revisionProperty = new Property();
            revisionProperty.setName("revision");
            revisionProperty.setValue(revision);
            metadata.addProperty(revisionProperty);
        }

        final String commit = System.getenv("GITHUB_SHA");
        if (commit != null) {
            final Property commitProperty = new Property();
            commitProperty.setName("commit");
            commitProperty.setValue(commit.substring(0, 7));
            metadata.addProperty(commitProperty);
        }

        if (!packageMetadata.packageDir().equals(projectDirectory)) {
            final Path relPackageDir =
                    projectDirectory.toPath().relativize(packageMetadata.packageDir().toPath());
            final Property subFolderProperty = new Property();
            subFolderProperty.setName("subfolder");
            subFolderProperty.setValue(relPackageDir.toString());
            metadata.addProperty(subFolderProperty);
        }

        return metadata;
    }
}
