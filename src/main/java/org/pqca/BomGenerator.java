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
import jakarta.annotation.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.cyclonedx.Version;
import org.cyclonedx.exception.GeneratorException;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Dependency;
import org.cyclonedx.model.Evidence;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.OrganizationalEntity;
import org.cyclonedx.model.Property;
import org.cyclonedx.model.Service;
import org.cyclonedx.model.component.evidence.Occurrence;
import org.cyclonedx.model.metadata.ToolInformation;
import org.pqca.indexing.JavaIndexService;
import org.pqca.indexing.ProjectModule;
import org.pqca.indexing.PythonIndexService;
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
                                        new IllegalArgumentException(
                                                "Could not load jar dependencies for java scanning")); // Error
        if (javaJarDir.exists() && javaJarDir.isDirectory()) {
            return javaJarDir.getAbsolutePath();
        }

        throw new IllegalArgumentException(
                "Jar dependencies dir for java scanning does not exist or is not directory");
    }

    @Nonnull
    private List<String> getJavaClassDirectories() {
        try (Stream<Path> walk = Files.walk(this.projectDirectory.toPath())) {
            return walk.filter(p -> p.endsWith("classes") && Files.isDirectory(p))
                    .map(p -> p.toAbsolutePath().toString())
                    .toList();
        } catch (Exception e) {
            LOG.error("Failed to find class directories: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    @Nonnull
    public Bom generateJavaBom() {
        final String javaJarDir = getJavaDependencyJARSPath();
        boolean requireBuild =
                Optional.ofNullable(System.getenv("CBOMKIT_JAVA_REQUIRE_BUILD"))
                        .map(v -> Boolean.valueOf(v))
                        .orElse(true);
        final List<String> targetClassDirs = getJavaClassDirectories();
        if (targetClassDirs.isEmpty()) {
            if (requireBuild) {
                throw new IllegalStateException(
                        "No Java class directories found. Propject must be build prior to scanning");
            } else {
                LOG.warn(
                        "No Java class directories found. Scanning Java code without prior build may produce less accurate CBOMs.");
            }
        }

        final JavaIndexService javaIndexService = new JavaIndexService(projectDirectory);
        final List<ProjectModule> javaProjectModules = javaIndexService.index(null);
        final JavaScannerService javaScannerService =
                new JavaScannerService(javaJarDir, targetClassDirs, projectDirectory);
        Bom bom = javaScannerService.scan(javaProjectModules);

        List<ProjectModule> packages = sortPackages(javaProjectModules);
        if (!packages.isEmpty()) {
            List<String> locations = getLocations(bom);
            for (ProjectModule projectModule : packages) {
                Bom packageBom = extractPackageBom(bom, locations, projectModule);
                writeBom(packageBom, projectModule);
            }
        }

        return bom;
    }

    @Nonnull
    public Bom generatePythonBom() {
        final PythonIndexService pythonIndexService = new PythonIndexService(projectDirectory);
        final List<ProjectModule> pythonProjectModules = pythonIndexService.index(null);
        final PythonScannerService pythonScannerService =
                new PythonScannerService(projectDirectory);
        Bom bom = pythonScannerService.scan(pythonProjectModules);

        List<ProjectModule> packages = sortPackages(pythonProjectModules);
        if (!packages.isEmpty()) {
            List<String> locations = getLocations(bom);
            for (ProjectModule projectModule : packages) {
                Bom packageBom = extractPackageBom(bom, locations, projectModule);
                writeBom(packageBom, projectModule);
            }
        }

        return bom;
    }

    private List<ProjectModule> sortPackages(List<ProjectModule> modules) {
        return modules.stream()
                .filter(pm -> !"".equals(pm.identifier()))
                .sorted(
                        Comparator.comparingInt(
                                        pm -> ((ProjectModule) pm).packagePath().getNameCount())
                                .reversed())
                .toList();
    }

    private List<String> getLocations(Bom bom) {
        return new ArrayList<String>(
                bom.getComponents().stream()
                        .map(Component::getEvidence)
                        .filter(Objects::nonNull)
                        .map(Evidence::getOccurrences)
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .map(Occurrence::getLocation)
                        .toList());
    }

    public void writeBom(Bom bom) {
        writeBom(bom, null);
    }

    private void writeBom(Bom bom, ProjectModule pm) {
        bom.setMetadata(generateMetadata(pm));

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

                final String fileName = getCbomFileName(pm);
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

    public String getCbomFileName(@Nullable ProjectModule pm) {
        StringBuilder sb = new StringBuilder("cbom");
        if (pm != null) {
            sb.append("_" + pm.identifier().replaceAll("/", "."));
        }
        sb.append(".json");
        return sb.toString();
    }

    private Metadata generateMetadata(@Nullable ProjectModule pm) {
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

        if (pm != null && !pm.packagePath().equals(projectDirectory.toPath())) {
            final Path relPackageDir = projectDirectory.toPath().relativize(pm.packagePath());
            final Property subFolderProperty = new Property();
            subFolderProperty.setName("subfolder");
            subFolderProperty.setValue(relPackageDir.toString());
            metadata.addProperty(subFolderProperty);
        }

        return metadata;
    }

    @Nonnull
    public Bom extractPackageBom(
            @Nonnull Bom bom, @Nonnull List<String> toExtract, @Nonnull ProjectModule pm) {
        HashMap<String, Component> modComps = new HashMap<String, Component>();
        final Bom moduleBom = new Bom();
        moduleBom.setSerialNumber("urn:uuid:" + UUID.randomUUID());

        Path relPackagePath = this.projectDirectory.toPath().relativize(pm.packagePath());
        for (Component c : bom.getComponents()) {
            Evidence e = c.getEvidence();
            if (e != null) {
                List<Occurrence> os = e.getOccurrences();
                if (os != null && !os.isEmpty()) {
                    for (Occurrence o : os) {
                        if (Paths.get(o.getLocation()).startsWith(relPackagePath)
                                && toExtract.contains(o.getLocation())) {
                            Component modComp = getOrNewComponent(modComps, c);
                            modComp.getEvidence().addOccurrence(o);
                            toExtract.remove(o.getLocation());
                        }
                    }
                }
            }
        }
        moduleBom.setComponents(modComps.values().stream().collect(Collectors.toList()));

        HashMap<String, Dependency> modDeps = new HashMap<String, Dependency>();
        for (Dependency d : bom.getDependencies()) {
            if (modComps.containsKey(d.getRef())) {
                List<String> localDeps = new ArrayList<String>();
                for (Dependency dd : d.getDependencies()) {
                    if (modComps.containsKey(dd.getRef()) && !localDeps.contains(dd.getRef())) {
                        localDeps.add(dd.getRef());
                    }
                }
                if (!localDeps.isEmpty()) {
                    Dependency newd = getOrNewDependency(modDeps, d);
                    newd.setDependencies(localDeps.stream().map(Dependency::new).toList());
                    modDeps.put(d.getRef(), newd);
                }
            }
        }
        moduleBom.setDependencies(modDeps.values().stream().collect(Collectors.toList()));

        return moduleBom;
    }

    private Component getOrNewComponent(Map<String, Component> modComps, Component c) {
        String bomRef = c.getBomRef();
        if (modComps.containsKey(bomRef)) {
            return modComps.get(bomRef);
        }

        Component copy = new Component();
        copy.setType(c.getType());
        copy.setBomRef(bomRef);
        copy.setName(c.getName());
        copy.setCryptoProperties(c.getCryptoProperties());
        Evidence e = new Evidence();
        e.setOccurrences(new ArrayList<Occurrence>());
        copy.setEvidence(e);

        modComps.put(bomRef, copy);
        return copy;
    }

    private Dependency getOrNewDependency(Map<String, Dependency> modDeps, Dependency d) {
        String bomRef = d.getRef();
        if (modDeps.containsKey(bomRef)) {
            return modDeps.get(bomRef);
        }

        return new Dependency(bomRef);
    }
}
