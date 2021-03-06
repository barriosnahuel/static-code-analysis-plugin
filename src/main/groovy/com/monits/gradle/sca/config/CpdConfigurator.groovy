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

import com.monits.gradle.sca.StaticCodeAnalysisExtension
import com.monits.gradle.sca.ToolVersions
import com.monits.gradle.sca.task.CPDTask
import groovy.transform.CompileStatic
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileTree

/**
 * A configurator for CPD tasks.
 */
class CpdConfigurator implements AnalysisConfigurator {
    private static final String CPD = 'cpd'

    @SuppressWarnings('UnnecessaryGetter')
    @Override
    void applyConfig(final Project project, final StaticCodeAnalysisExtension extension) {
        // prevent applying it twice
        if (project.tasks.findByName(CPD)) {
            return
        }

        Task cpdTask = project.task(CPD, type:CPDTask) {
            ignoreFailures = extension.getIgnoreErrors()

            FileTree srcDir = project.fileTree("$project.projectDir/src/")
            srcDir.include '**/*.java'
            srcDir.exclude '**/gen/**'

            toolVersion = ToolVersions.pmdVersion
            inputFiles = srcDir
            outputFile = new File("$project.buildDir/reports/pmd/cpd.xml")
        }

        project.tasks.check.dependsOn cpdTask
    }

    @CompileStatic
    @Override
    void applyAndroidConfig(final Project project, final StaticCodeAnalysisExtension extension) {
        applyConfig(project, extension) // no difference at all
    }
}
