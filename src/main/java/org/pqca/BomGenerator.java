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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Dependency;
import org.cyclonedx.model.Evidence;
import org.cyclonedx.model.component.evidence.Occurrence;
import org.pqca.errors.CBOMSerializationFailed;
import org.pqca.indexing.IndexingService;
import org.pqca.indexing.ProjectModule;
import org.pqca.scanning.CBOM;
import org.pqca.scanning.IScannerService;
import org.pqca.scanning.ScanResultDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BomGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(BomGenerator.class);

    private static final String GIT_SERVER = System.getenv("GITHUB_SERVER_URL");
    private static final String GIT_REPO = System.getenv("GITHUB_REPOSITORY");

    private static final String GIT_REVISION = System.getenv("GITHUB_REF_NAME");
    private static final String GIT_COMMIT = System.getenv("GITHUB_SHA");
    private static final String GIT_URL =
            GIT_SERVER != null && GIT_REPO != null ? GIT_SERVER + "/" + GIT_REPO : null;

    private static final boolean WRITE_EMPTY_CBOMS =
            Optional.ofNullable(System.getenv("CBOMKIT_WRITE_EMPTY_CBOMS"))
                    .map(Boolean::valueOf)
                    .orElse(true);
    private static final boolean GENERATE_MODULE_CBOMS =
            Optional.ofNullable(System.getenv("CBOMKIT_GENERATE_MODULE_CBOMS"))
                    .map(Boolean::valueOf)
                    .orElse(true);

    @Nonnull private final File projectDirectory;
    @Nonnull private final File outputDir;

    public BomGenerator(@Nonnull File projectDirectory, File outputDir) {
        this.projectDirectory = projectDirectory;
        this.outputDir = outputDir;
    }

    @Nonnull
    public ScanResultDTO generateBom(IndexingService indexer, IScannerService scanner)
            throws Exception {
        final List<ProjectModule> projectModules = indexer.index(null);
        ScanResultDTO scanResult = scanner.scan(projectModules);

        if (GENERATE_MODULE_CBOMS && scanResult.cbom() != null) {
            generateModuleCBOMs(scanResult.cbom(), projectModules);
        }

        return scanResult;
    }

    private void generateModuleCBOMs(CBOM cbom, List<ProjectModule> modules) {
        List<ProjectModule> packages = sortPackages(modules);
        if (!packages.isEmpty()) {
            List<String> locations = getLocations(cbom);
            for (ProjectModule pm : packages) {
                CBOM packageCBOM = extractPackageCBom(cbom, locations, pm);
                writeCBOM(packageCBOM, pm);
            }
        }
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

    private List<String> getLocations(CBOM cbom) {
        return new ArrayList<String>(
                cbom.cycloneDXbom().getComponents().stream()
                        .map(Component::getEvidence)
                        .filter(Objects::nonNull)
                        .map(Evidence::getOccurrences)
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .map(Occurrence::getLocation)
                        .toList());
    }

    @Nonnull
    private CBOM extractPackageCBom(
            @Nonnull CBOM cbom, @Nonnull List<String> toExtract, @Nonnull ProjectModule pm) {
        HashMap<String, Component> modComps = new HashMap<String, Component>();
        final Bom moduleBom = new Bom();
        moduleBom.setSerialNumber("urn:uuid:" + UUID.randomUUID());

        Path relPackagePath = this.projectDirectory.toPath().relativize(pm.packagePath());
        for (Component c : cbom.cycloneDXbom().getComponents()) {
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
        for (Dependency d : cbom.cycloneDXbom().getDependencies()) {
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

        return new CBOM(moduleBom);
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

    private String getCBOMFileName(@Nullable ProjectModule pm) {
        StringBuilder sb = new StringBuilder(outputDir + "/cbom");
        if (pm != null) {
            sb.append("_" + pm.identifier().replaceAll("/", "."));
        }
        sb.append(".json");
        return sb.toString();
    }

    public void writeCBOM(CBOM cbom, ProjectModule pm) {
        int numberOfFindings = cbom.getNumberOfFindings();
        if (WRITE_EMPTY_CBOMS || numberOfFindings > 0) {
            String subFolder =
                    !(pm == null || pm.packagePath().equals(projectDirectory.toPath()))
                            ? projectDirectory.toPath().relativize(pm.packagePath()).toString()
                            : null;
            cbom.addMetadata(GIT_URL, GIT_REVISION, GIT_COMMIT, subFolder);
            String cbomFileName = getCBOMFileName(pm);
            try {
                cbom.write(cbomFileName);
                LOGGER.info("Wrote cbom {} with {} findings", cbomFileName, numberOfFindings);
            } catch (CBOMSerializationFailed e) {
                LOGGER.error(e.getMessage());
            }
        }
    }
}
