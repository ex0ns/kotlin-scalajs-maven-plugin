/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
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
 */

package org.jetbrains.kotlin.maven;

import ch.epfl.k2sjsir.K2SJSIRCompiler;
import ch.epfl.k2sjsir.K2SJSIRCompilerArguments;
import com.intellij.openapi.util.text.StringUtil;
import kotlin.text.StringsKt;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.js.JavaScript;
import org.jetbrains.kotlin.utils.KotlinJavascriptMetadataUtils;
import org.jetbrains.kotlin.utils.LibraryUtils;
import org.scalajs.core.tools.io.IRFileCache;
import org.scalajs.core.tools.io.WritableFileVirtualJSFile;
import org.scalajs.core.tools.io.WritableFileVirtualJSFile$;
import org.scalajs.core.tools.linker.Linker;
import org.scalajs.core.tools.linker.backend.LinkerBackend;
import org.scalajs.core.tools.linker.backend.ModuleKind;
import org.scalajs.core.tools.linker.backend.OutputMode;
import org.scalajs.core.tools.linker.frontend.LinkerFrontend;
import org.scalajs.core.tools.logging.Level;
import org.scalajs.core.tools.logging.Logger;
import org.scalajs.core.tools.logging.ScalaConsoleLogger;
import org.scalajs.core.tools.sem.Semantics$;
import scala.collection.Seq$;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Converts Kotlin to JavaScript code
 *
 * @noinspection UnusedDeclaration
 */
@Mojo(name = "sjs", defaultPhase = LifecyclePhase.COMPILE, requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class K2SJSCompilerMojo extends KotlinCompileMojoBase<K2SJSIRCompilerArguments> {

    private static final String OUTPUT_DIRECTORIES_COLLECTOR_PROPERTY_NAME = "outputDirectoriesCollector";
    private static final Lock lock = new ReentrantLock();

    /**
     * The output JS file name
     */
    @Parameter(defaultValue = "${project.build.directory}/sjs/${project.artifactId}.js", required = true)
    private String outputFile;

    @Parameter(defaultValue = "${project.build.directory}/sjs/", required = true)
    private String destination;

    /**
     * Flag enables or disables .meta.js and .kjsm files generation, used to create libraries
     */
    @Parameter(defaultValue = "true")
    private boolean metaInfo;

    /**
     * Flags enables or disable source map generation
     */
    @Parameter(defaultValue = "false")
    private boolean sourceMap;

    /**
     * <p>Specifies which JS module system to generate compatible sources for. Options are:</p>
     * <ul>
     *     <li><b>amd</b> &mdash;
     *       <a href="https://github.com/amdjs/amdjs-api/wiki/AMD"></a>Asynchronous Module System</a>;</li>
     *     <li><b>commonjs</b> &mdash; npm/CommonJS conventions based on synchronous <code>require</code>
     *       function;</li>
     *     <li><b>plain</b> (default) &mdash; no module system, keep all modules in global scope;</li>
     *     <li><b>umd</b> &mdash; Universal Module Definition, stub wrapper that detects current
     *       module system in runtime and behaves as <code>plain</code> if none detected.</li>
     * </ul>
     */
    @Parameter(defaultValue = "plain")
    private String moduleKind;

    @Override
    protected void configureSpecificCompilerArguments(@NotNull K2SJSIRCompilerArguments arguments) throws MojoExecutionException {
        arguments.outputFile_$eq(outputFile);
        arguments.noStdlib_$eq(true);
        arguments.metaInfo_$eq(metaInfo);
        arguments.destination_$eq(destination);
        arguments.moduleKind_$eq(moduleKind);

        List<String> libraries = null;
        try {
            libraries = getKotlinJavascriptLibraryFiles();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Unresolved dependencies", e);
        }
        getLog().debug("libraries: " + libraries);
        arguments.libraries_$eq(StringUtil.join(libraries, File.pathSeparator));

        arguments.sourceMap_$eq(sourceMap);

        Set<String> collector = getOutputDirectoriesCollector();

        if (outputFile != null) {
            collector.add(new File(outputFile).getParent());
        }
        if (metaInfo) {
            String output = outputFile == null ? "" : outputFile; // fqname here because of J8 compatibility issues
            String metaFile = StringsKt.substringBeforeLast(output, JavaScript.DOT_EXTENSION, output) + KotlinJavascriptMetadataUtils.META_JS_SUFFIX;
            collector.add(new File(metaFile).getParent());
        }
    }

    protected List<String> getClassPathElements() throws DependencyResolutionRequiredException {
        return project.getCompileClasspathElements();
    }

    /**
     * Returns all Kotlin Javascript dependencies that this project has, including transitive ones.
     *
     * @return array of paths to kotlin javascript libraries
     */
    @NotNull
    private List<String> getKotlinJavascriptLibraryFiles() throws DependencyResolutionRequiredException {
        List<String> libraries = new ArrayList<String>();

        for (String path : getClassPathElements()) {
            File file = new File(path);

            if (file.exists() && LibraryUtils.isKotlinJavascriptLibrary(file)) {
                libraries.add(file.getAbsolutePath());
            }
            else {
                getLog().debug("artifact " + file.getAbsolutePath() + " is not a Kotlin Javascript Library");
            }
        }

        for (String path : getOutputDirectoriesCollector()) {
            File file = new File(path);

            if (file.exists() && LibraryUtils.isKotlinJavascriptLibrary(file)) {
                libraries.add(file.getAbsolutePath());
            }
            else {
                getLog().debug("JS output directory missing: " + file);
            }
        }

        return libraries;
    }

    @NotNull
    @Override
    protected K2SJSIRCompilerArguments createCompilerArguments() {
        return new K2SJSIRCompilerArguments();
    }

    @Override
    protected List<String> getRelatedSourceRoots(MavenProject project) {
        return project.getCompileSourceRoots();
    }

    @NotNull
    @Override
    protected K2SJSIRCompiler createCompiler() {
        return new K2SJSIRCompiler();
    }

    @SuppressWarnings("unchecked")
    private Set<String> getOutputDirectoriesCollector() {
        lock.lock();
        try {
            Set<String> collector = (Set<String>) getPluginContext().get(OUTPUT_DIRECTORIES_COLLECTOR_PROPERTY_NAME);
            if (collector == null) {
                collector = new ConcurrentSkipListSet<String>();
                getPluginContext().put(OUTPUT_DIRECTORIES_COLLECTOR_PROPERTY_NAME, collector);
            }

            return collector;
        } finally {
            lock.unlock();
        }
    }
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        super.execute();


        ArrayList<File> cp = new ArrayList<File>();
        try {
            for(String file : project.getRuntimeClasspathElements()) {
                cp.add(new File(file));
            }
        } catch (DependencyResolutionRequiredException e) {
            e.printStackTrace();
        }
        cp.add(new File(destination));

        scala.collection.immutable.Seq<File> col =
                scala.collection.JavaConverters.collectionAsScalaIterable(cp).toList();
        scala.collection.Seq<IRFileCache.IRContainer> irContainers = IRFileCache.IRContainer$.MODULE$.fromClasspath(col);

        LinkerFrontend.Config frontendConfig = LinkerFrontend.Config$.MODULE$.apply();

        LinkerBackend.Config backendConfig = LinkerBackend.Config$.MODULE$.apply();

        Linker.Config config = Linker.Config$.MODULE$.apply()
                .withSourceMap(false)
                .withOptimizer(true)
                .withParallel(true)
                .withClosureCompilerIfAvailable(true)
                .withFrontendConfig(frontendConfig)
                .withBackendConfig(backendConfig);

        Linker linker = Linker.apply(Semantics$.MODULE$.Defaults(),
                OutputMode.ECMAScript51Isolated$.ECMAScript51Isolated$.MODULE$,
                ModuleKind.NoModule$.NoModule$.MODULE$,
                config);

        Logger logger = new ScalaConsoleLogger(Level.Info$.MODULE$);
        WritableFileVirtualJSFile outFile = WritableFileVirtualJSFile$.MODULE$.apply(new File(outputFile));
        IRFileCache.Cache cache = new IRFileCache().newCache();

        linker.link(cache.cached(irContainers), Seq$.MODULE$.empty(), outFile, logger);
    }

}
