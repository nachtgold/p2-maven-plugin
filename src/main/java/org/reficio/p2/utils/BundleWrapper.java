/**
 * Copyright (c) 2012 Reficio (TM) - Reestablish your software! All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.reficio.p2.utils;

import aQute.lib.osgi.Analyzer;
import aQute.lib.osgi.Jar;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.reficio.p2.P2Artifact;
import org.reficio.p2.log.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * @author Tom Bujok (tom.bujok@gmail.com)
 * @since 1.0.0
 *        <p/>
 *        Reficio (TM) - Reestablish your software!</br>
 *        http://www.reficio.org
 */
public class BundleWrapper {

    private static final String TOOL_KEY = "Tool";
    private static final String TOOL = "p2-maven-plugin (reficio.org)";

    public static final String ECLIPSE_SOURCE_BUNDLE = "Eclipse-SourceBundle";
    public static final String IMPLEMENTATION_TITLE = "Implementation-Title";
    public static final String SPECIFICATION_TITLE = "Specification-Title";
    public static final String MANIFEST_VERSION = "Manifest-Version";

    protected final BundleUtils bundleUtils;
    private final boolean pedantic;
    private final File bundlesDestinationFolder;

    public BundleWrapper(boolean pedantic, File bundlesDestinationFolder) {
        this.bundleUtils = new BundleUtils();
        this.pedantic = pedantic;
        this.bundlesDestinationFolder = bundlesDestinationFolder;
    }

    public void execute(P2Artifact artifact) throws Exception {
        wrapArtifacts(artifact);
    }

    private void wrapArtifacts(P2Artifact p2Artifact) throws Exception {
        validateConfig(p2Artifact);
        log().info("Executing BND:");
        for (ResolvedArtifact artifact : p2Artifact.getResolvedArtifacts()) {
            WrapRequest wrapRequest = populateWrapRequest(p2Artifact, artifact);
            wrap(wrapRequest);
        }
    }

    private void validateConfig(P2Artifact p2Artifact) {
        if (p2Artifact.shouldIncludeTransitive() && !p2Artifact.getInstructions().isEmpty()) {
            String message = "BND instructions are NOT applied to the transitive dependencies of ";
            log().warn(String.format("%s %s", message, p2Artifact.getId()));
        }
    }

    private WrapRequest populateWrapRequest(P2Artifact p2Artifact, ResolvedArtifact artifact) {
        File inputFile = artifact.getArtifact().getFile();
        File outputFile = new File(bundlesDestinationFolder, artifact.getArtifact().getFile().getName());
        WrapRequest request = new WrapRequest(p2Artifact, artifact, inputFile, outputFile);
        request.validate();
        return request;
    }

    private void wrap(WrapRequest request) throws Exception {
        doWrap(request);
        doSourceWrap(request);
    }

    private void doWrap(WrapRequest request) throws Exception {
        if (request.isShouldWrap()) {
            log().info("\t [EXEC] " + request.getInputFile().getName());
            handleVanillaJarWrap(request);
        } else {
            log().info("\t [SKIP] " + request.getInputFile().getName());
            handleBundleJarWrap(request);
        }
    }

    private void handleBundleJarWrap(WrapRequest request) throws IOException {
        FileUtils.copyFile(request.getInputFile(), request.getOutputFile());
    }

    private void handleVanillaJarWrap(WrapRequest request) throws Exception {
        Analyzer analyzer = initializeAnalyzer(request);
        try {
            analyzer.calcManifest();
            populateJar(analyzer, request.getOutputFile());
            bundleUtils.reportErrors(analyzer);
        } finally {
            analyzer.close();
            unsignJar(request.getOutputFile());
        }
    }

    private void populateJar(Analyzer analyzer, File outputFile) throws Exception {
        Jar jar = analyzer.getJar();
        try {
            jar.write(outputFile);
        } finally {
            jar.close();
        }
    }

    private void unsignJar(File jarToUnsign) {
        File unsignedJar = new File(jarToUnsign.getParent(), jarToUnsign.getName() + ".tmp");
        try {
            unsignedJar.createNewFile();
            ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(unsignedJar));
            try {
                ZipFile zip = new ZipFile(jarToUnsign);
                boolean unsigned = false;
                for (Enumeration list = zip.entries(); list.hasMoreElements(); ) {
                    ZipEntry entry = (ZipEntry) list.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory()) {
                        continue;
                    } else if (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".SF")) {
                        unsigned = true;
                        continue;
                    }
                    zipOutputStream.putNextEntry(entry);
                    InputStream zipInputStream = zip.getInputStream(entry);
                    try {
                        IOUtils.copy(zipInputStream, zipOutputStream);
                    } finally {
                        zipInputStream.close();
                    }
                }
                if (unsigned) {
                    log().info("\t [UNSIGN] " + jarToUnsign.getName());
                }
                IOUtils.closeQuietly(zipOutputStream);
                FileUtils.copyFile(unsignedJar, jarToUnsign);
            } finally {
                IOUtils.closeQuietly(zipOutputStream);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            FileUtils.deleteQuietly(unsignedJar);
        }
    }

    private Analyzer initializeAnalyzer(WrapRequest request) throws Exception {
        Analyzer analyzer = instantiateAnalyzer(request);
        setAnalyzerOptions(analyzer);
        setPackageOptions(analyzer);
        setBundleOptions(analyzer, request);
        setManifest(analyzer);
        setInstructions(analyzer, request);
        return analyzer;
    }

    private Analyzer instantiateAnalyzer(WrapRequest request) throws Exception {
        Analyzer analyzer = new Analyzer();
        analyzer.setJar(getInputJarBlankManifest(request));
        return analyzer;
    }

    public Jar getInputJarBlankManifest(WrapRequest request) throws Exception {
        File parentFolder = request.getInputFile().getParentFile();
        File jarBlankManifest = new File(parentFolder, request.getInputFile().getName() + "." + UUID.randomUUID());
        Jar jar = new Jar(request.getInputFile());
        try {
            jar.setManifest(new Manifest());
            jar.write(jarBlankManifest);
            return new Jar(jarBlankManifest);
        } finally {
            jar.close();
        }
    }

    private void setAnalyzerOptions(Analyzer analyzer) {
        analyzer.setPedantic(pedantic);
    }

    private void setPackageOptions(Analyzer analyzer) {
        analyzer.setProperty(Analyzer.IMPORT_PACKAGE, "*;resolution:=optional");
        String export = analyzer.calculateExportsFromContents(analyzer.getJar());
        analyzer.setProperty(Analyzer.EXPORT_PACKAGE, export);
    }

    private void setBundleOptions(Analyzer analyzer, WrapRequest request) {
        analyzer.setProperty(Analyzer.BUNDLE_SYMBOLICNAME, request.getProperties().getSymbolicName());
        analyzer.setProperty(Analyzer.BUNDLE_NAME, request.getProperties().getName());
        analyzer.setProperty(Analyzer.BUNDLE_VERSION, request.getProperties().getVersion());
        analyzer.setProperty(TOOL_KEY, TOOL);
    }

    private void setInstructions(Analyzer analyzer, WrapRequest request) {
        // do not set instructions on transitive artifacts
        if (request.getResolvedArtifact().isRoot()) {
            if (!request.getP2artifact().getInstructions().isEmpty()) {
                analyzer.setProperties(BundleUtils.transformDirectives(request.getP2artifact().getInstructions()));
            }
        }
    }

    private void setManifest(Analyzer analyzer) throws IOException {
        analyzer.mergeManifest(analyzer.getJar().getManifest());
    }

    private void doSourceWrap(WrapRequest request) throws Exception {
        if (request.getResolvedArtifact().getSourceArtifact() == null) {
            return;
        }
        log().info("\t [EXEC] " + request.getResolvedArtifact().getSourceArtifact().getFile().getName());
        File wrappedSource = new File(bundlesDestinationFolder, request.getResolvedArtifact().getSourceArtifact().getFile().getName());
        String symbolicName = request.getProperties().getSourceSymbolicName();
        String version = request.getProperties().getSourceVersion();
        String name = request.getProperties().getSourceName();
        Jar jar = new Jar(request.getResolvedArtifact().getSourceArtifact().getFile());
        Manifest manifest = getManifest(jar);
        decorateSourceManifest(manifest, name, symbolicName, version);
        jar.setManifest(manifest);
        jar.write(wrappedSource);
        jar.close();
    }

    private Manifest getManifest(Jar jar) throws IOException {
        Manifest manifest = jar.getManifest();
        if (manifest == null) {
            manifest = new Manifest();
        }
        return manifest;
    }

    private void decorateSourceManifest(Manifest manifest, String name, String symbolicName, String version) {
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue(Analyzer.BUNDLE_SYMBOLICNAME, symbolicName);
        attributes.putValue(ECLIPSE_SOURCE_BUNDLE, symbolicName + ";version=\"" + version + "\";roots:=\".\"");
        attributes.putValue(Analyzer.BUNDLE_VERSION, version);
        attributes.putValue(Analyzer.BUNDLE_LOCALIZATION, "plugin");
        attributes.putValue(MANIFEST_VERSION, "1.0");
        attributes.putValue(Analyzer.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Analyzer.BUNDLE_NAME, name);
        attributes.putValue(IMPLEMENTATION_TITLE, name);
        attributes.putValue(SPECIFICATION_TITLE, name);
        attributes.putValue(TOOL_KEY, TOOL);
    }

    private Logger log() {
        return Logger.getLog();
    }

}
