/*
 * Copyright 2010-2016 Monits S.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.monits.gradle.sca.config

import com.monits.gradle.sca.ClasspathAware
import com.monits.gradle.sca.RulesConfig
import com.monits.gradle.sca.StaticCodeAnalysisExtension
import com.monits.gradle.sca.ToolVersions
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Namer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.quality.FindBugs
import org.gradle.api.plugins.quality.FindBugsExtension
import org.gradle.api.plugins.quality.FindBugsReports
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.util.GUtil

/**
 * A configurator for Findbugs tasks
*/
@CompileStatic
class FindbugsConfigurator implements AnalysisConfigurator, ClasspathAware {
    private static final String FINDBUGS = 'findbugs'
    private static final String FINBUGS_PLUGINS_CONFIGURATION = 'findbugsPlugins'
    private static final String ANT_WILDCARD = '**'

    private final RemoteConfigLocator configLocator = new RemoteConfigLocator(FINDBUGS)

    @Override
    void applyConfig(final Project project, final StaticCodeAnalysisExtension extension) {
        setupPlugin(project, extension)

        SourceSetContainer sourceSets = project.convention.getPlugin(JavaPluginConvention).sourceSets
        setupTasksPerSourceSet(project, extension, sourceSets)
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    @Override
    void applyAndroidConfig(final Project project, final StaticCodeAnalysisExtension extension) {
        setupPlugin(project, extension)

        setupTasksPerSourceSet(project, extension, project.android.sourceSets) { FindBugs findbugsTask, sourceSet ->
            /*
             * Android doesn't expose name of the task compiling the sourceset, and names vary
             * widely from version to version of the plugin, plus needs to take flavors into account.
             * This is inefficient, but safer and simpler.
            */
            dependsOn project.tasks.withType(JavaCompile)

            // Filter analyzed classes to just include those that are in the sourceset
            String srcDirPath = sourceSet.java.srcDirs.first().absolutePath
            Collection<String> sourceSetClassesPaths = sourceSet.java.sourceFiles
                .findAll { File f -> !f.directory }
                .collectMany { File f ->
                    String relativePath = f.absolutePath - srcDirPath
                    String pathWithoutExtension = relativePath.take(relativePath.lastIndexOf('.'))
                    // allow both top level and inner classes
                    [ANT_WILDCARD + pathWithoutExtension + '.class', ANT_WILDCARD + pathWithoutExtension + '$*.class']
                }

            if (sourceSetClassesPaths.empty) {
                classes = project.files() // empty file collection
            } else {
                classes = getProjectClassTree(project, sourceSet.name).include(sourceSetClassesPaths)
            }

            source sourceSet.java.srcDirs
            exclude '**/gen/**'

            setupAndroidClasspathAwareTask(findbugsTask, project, sourceSetClassesPaths)
        }
    }

    @SuppressWarnings('UnnecessaryGetter')
    private static void setupPlugin(final Project project, final StaticCodeAnalysisExtension extension) {
        project.plugins.apply FINDBUGS

        project.dependencies.with {
            add(FINBUGS_PLUGINS_CONFIGURATION,
                    'com.monits:findbugs-plugin:' + ToolVersions.monitsFindbugsVersion) { ModuleDependency d ->
                d.transitive = false
            }

            add(FINBUGS_PLUGINS_CONFIGURATION, 'com.mebigfatguy.fb-contrib:fb-contrib:' + ToolVersions.fbContribVersion)
        }

        project.extensions.configure(FindBugsExtension) { FindBugsExtension it ->
            it.with {
                toolVersion = ToolVersions.findbugsVersion
                effort = 'max'
                ignoreFailures = extension.getIgnoreErrors()
            }
        }
    }

    @SuppressWarnings('UnnecessaryGetter')
    private void setupTasksPerSourceSet(final Project project, final StaticCodeAnalysisExtension extension,
                                               final NamedDomainObjectContainer<?> sourceSets,
                                               final Closure<?> configuration = null) {
        // Create a phony findbugs task that just executes all real findbugs tasks
        Task findbugsRootTask = project.tasks.findByName(FINDBUGS) ?: project.task(FINDBUGS)
        sourceSets.all { sourceSet ->
            Namer<Object> namer = sourceSets.namer as Namer<Object>
            String sourceSetName = namer.determineName(sourceSet)
            RulesConfig config = extension.sourceSetConfig.maybeCreate(sourceSetName)

            File filterSource
            boolean remoteLocation
            String downloadTaskName

            // findbugs exclude is optional
            if (config.getFindbugsExclude()) {
                remoteLocation = RemoteConfigLocator.isRemoteLocation(config.getFindbugsExclude())
                downloadTaskName = generateTaskName('downloadFindbugsExcludeFilter', sourceSetName)
                if (remoteLocation) {
                    filterSource = configLocator.makeDownloadFileTask(project, config.getFindbugsExclude(),
                            String.format('excludeFilter-%s.xml', sourceSetName), downloadTaskName)
                } else {
                    filterSource = new File(config.getFindbugsExclude())
                }
            }

            Task findbugsTask = getOrCreateTask(project, generateTaskName(sourceSetName)) { FindBugs it ->
                it.with {
                    // most defaults are good enough
                    if (remoteLocation) {
                        dependsOn project.tasks.findByName(downloadTaskName)
                    }

                    if (filterSource) {
                        excludeFilter = filterSource
                    }

                    reports { FindBugsReports r ->
                        r.with {
                            xml.setDestination(new File(project.extensions.getByType(ReportingExtension).file(FINDBUGS),
                                "findbugs-${sourceSetName}.xml"))
                            xml.withMessages = true
                        }
                    }
                }
            }

            if (configuration) {
                // Add the sourceset as second parameter for configuration closure
                findbugsTask.configure configuration.rcurry(sourceSet)
            }

            findbugsRootTask.dependsOn findbugsTask
        }

        project.tasks.findByName('check').dependsOn findbugsRootTask
    }

    private static String generateTaskName(final String taskName = FINDBUGS, final String sourceSetName) {
        GUtil.toLowerCamelCase(String.format('%s %s', taskName, sourceSetName))
    }

    private static Task getOrCreateTask(final Project project, final String taskName, final Closure closure) {
        Task findbugsTask
        if (project.tasks.findByName(taskName)) {
            findbugsTask = project.tasks.findByName(taskName)
        } else {
            findbugsTask = project.task(taskName, type:FindBugs)
        }

        findbugsTask.configure closure
    }
}
