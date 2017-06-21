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
package com.monits.gradle.sca

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree

import java.security.MessageDigest

/**
 * Trait for configuring classpath aware tasks.
*/
@CompileStatic
trait ClasspathAware {

    private static final List<String> AUTOGENERATED_CLASSES = [
        '**/R.class', // R.java
        '**/R$*.class', // R.java inner classes
        '**/Manifest.class', // Manifest.java
        '**/Manifest$*.class', // Manifest.java inner classes
        '**/BuildConfig.class', // BuildConfig.java
        '**/BuildConfig$*.class', // BuildConfig.java inner classes
    ].asImmutable()

    private static final String MOCKABLE_ANDROID_JAR_TASK = 'mockableAndroidJar'
    private static final String DEBUG_SOURCESET = 'debug'
    private static final String MAIN_SOURCESET = 'main'
    private static final String TEST_SOURCESET = 'test'
    private static final String ANDROID_TEST_SOURCESET = 'androidTest'

    void setupAndroidClasspathAwareTask(final Task taskToConfigure, final Project project,
                                        final Collection<String> sourceSetClasses) {
        ClasspathAware cpa = this

        /*
         * For best results, this task needs ALL classes, including Android's SDK,
         * but we need that configure before execution to be considered in up-to-date check.
         * We do it in a separate task, executing AFTER all other needed tasks are done
         */
        Task cpTask = project.task(
                'configureClasspathFor' + taskToConfigure.name.capitalize()) { Task self ->
            // The mockable android jar allows us to know Android's classes in our analysis
            project.tasks.matching { Task t -> t.name == MOCKABLE_ANDROID_JAR_TASK }.all { Task t ->
                self.dependsOn t
            }

            // we need all other task to be done first
            self.dependsOn taskToConfigure.dependsOn.findAll { it != self } // avoid cycles
        }.doLast {
            cpa.configAndroidClasspath(taskToConfigure, project,
                    project.tasks.findByName(MOCKABLE_ANDROID_JAR_TASK), sourceSetClasses)
        }

        taskToConfigure.dependsOn cpTask
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    void configAndroidClasspath(final Task task, final Project project,
                                final Task mockableAndroidJarTask,
                                final Collection<String> sourceSetClasses) {
        // Manually add classes of project dependencies
        FileCollection dependantModuleClasses = project.files()
        project.configurations.scaconfigModules.dependencies.all { ProjectDependency dependency ->
            // TODO : is it okay to always use debug?
            dependantModuleClasses += getProjectClassTree(dependency.dependencyProject, DEBUG_SOURCESET)
        }

        FileCollection mockableAndroidJar = project.files()
        if (mockableAndroidJarTask) {
            mockableAndroidJar += mockableAndroidJarTask.outputs.files
        }

        task.classpath = project.configurations.scaconfig +
                getJarsForAarDependencies(project) +
                mockableAndroidJar +
                // TODO : is it okay to always use debug?
                getNonAnalyzedProjectClasses(project, DEBUG_SOURCESET, sourceSetClasses) +
                // TODO : is it okay to always include test classes?
                project.files(pathToCompiledClasses(project, 'testDebug')) +
                dependantModuleClasses
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    static FileTree getJarsForAarDependencies(final Project project) {
        if (!AndroidHelper.usesBuildCache(project)) {
            return project.fileTree(
                dir:"${project.buildDir}/intermediates/exploded-aar/",
                include:'**/*.jar',
                exclude:"${project.rootProject.name}/*/unspecified/jars/classes.jar",)
        }

        String cacheDir = AndroidHelper.getBuildCacheDir(project)
        project.files(project.configurations.scaconfig.files.findAll { File it -> it.name.endsWith '.aar' }
            .collect { File it ->
                MessageDigest sha1 = MessageDigest.getInstance('SHA1')
                String inputFile = 'COMMAND=PREPARE_LIBRARY\n' +
                    "FILE_PATH=${it.absolutePath}\n" +
                    "FILE_SIZE=${it.length()}\n" +
                    "FILE_TIMESTAMP=${it.lastModified()}"
                String hash = new BigInteger(1, sha1.digest(inputFile.bytes)).toString(16)
                cacheDir + hash + File.separator + 'output/jars/classes.jar'
            }).asFileTree
    }

    /**
     * Retrieves a FileTree pointing to all interesting .class files for
     * static code analysis. This ignores for instance, Android's autogenerated classes
     *
     * @param proj The project whose class file tree to obtain.
     * @param sourceSetName The name of the sourceset whose classes to obtain.
     * @param sourceSetClasses The classes being analyzed corresponding to this sourceset.
     * @return FileTree pointing to all interesting .class files
     */
    static FileTree getNonAnalyzedProjectClasses(final Project proj, final String sourceSetName,
                                                 final Collection<String> sourceSetClasses) {
        ConfigurableFileTree tree = proj.fileTree(dir:pathToCompiledClasses(proj, sourceSetName))
        if (sourceSetClasses) {
            tree.exclude sourceSetClasses
        }
        tree
    }

    /**
     * Retrieves a FileTree pointing to all interesting .class files for
     * static code analysis. This ignores for instance, Android's autogenerated classes
     *
     * @param proj The project whose class file tree to obtain.
     * @param sourceSetName Optional. The name of the sourceset whose classes to obtain.
     * @return FileCollection pointing to all interesting .class files
     */
    FileCollection getProjectClassTree(final Project proj, final String sourceSetName) {
        ConfigurableFileTree tree = proj.fileTree(
                dir:pathToCompiledClasses(proj, sourceSetName))
        tree.exclude(AUTOGENERATED_CLASSES) as FileTree
    }

    /**
     * Retrieves the path to the location of compiled classes for the given sourceset under android
     *
     * @param project The project under which the sourceset exists
     * @param sourceSetName The name of the sourceset being compiled
     * @return The path to the directory were compiled classes can be found for this sourceset
     */
    private static String pathToCompiledClasses(final Project project, final String sourceSetName) {
        String sourceSetOutputPath

        if (sourceSetName == ANDROID_TEST_SOURCESET) {
            sourceSetOutputPath = 'androidTest/debug'
        } else if (sourceSetName == TEST_SOURCESET) {
            sourceSetOutputPath = 'test/debug'
        } else {
            // generate output path for classes. 'main' is filtered, since those map directly to debug / release
            sourceSetOutputPath = camelToWords(sourceSetName)*.toLowerCase()
                .findAll { String it -> it != MAIN_SOURCESET }.join(File.separator)

            if (sourceSetOutputPath.empty) {
                sourceSetOutputPath = DEBUG_SOURCESET
            }
        }

        project.buildDir.absolutePath + '/intermediates/classes/' + sourceSetOutputPath + File.separator
    }

    /**
     * Converts a camel case string into an array of words. Casing is not changed for each word
     *
     * @param camelCase The camel case sring to be split
     * @return The split words
     */
    private static String[] camelToWords(final String camelCase) {
        camelCase.split(
            String.format('%s|%s|%s',
                '(?<=[A-Z])(?=[A-Z][a-z])',
                '(?<=[^A-Z])(?=[A-Z])',
                '(?<=[A-Za-z])(?=[^A-Za-z])'
            )
        )
    }
}
