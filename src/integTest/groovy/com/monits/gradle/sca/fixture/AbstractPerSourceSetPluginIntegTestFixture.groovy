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
package com.monits.gradle.sca.fixture

import org.gradle.testkit.runner.BuildResult

import static org.apache.commons.lang3.StringUtils.capitalize
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

/**
 * Base specification to test a single analysis report.
*/
abstract class AbstractPerSourceSetPluginIntegTestFixture extends AbstractPluginIntegTestFixture {

    static final MAIN_SOURCESET = 'main'
    static final TEST_SOURCESET = 'test'
    static final ANDROID_TEST_SOURCESET = 'androidTest'

    @SuppressWarnings('MethodName')
    void 'multimodule android project runs all tasks'() {
        given:
        setupMultimoduleAndroidProject()

        when:
        BuildResult result = gradleRunner()
                .withArguments(toolName()) // use the meta-task
                .build()

        then:
        result.task(LIBA_PATH + taskName()).outcome == SUCCESS
        result.task(LIBA_PATH + taskName() + capitalize(MAIN_SOURCESET)).outcome == SUCCESS
        result.task(LIBA_PATH + taskName() + capitalize(TEST_SOURCESET)).outcome == SUCCESS
        result.task(LIBB_PATH + taskName()).outcome == SUCCESS
        result.task(LIBB_PATH + taskName() + capitalize(MAIN_SOURCESET)).outcome == SUCCESS
        result.task(LIBB_PATH + taskName() + capitalize(TEST_SOURCESET)).outcome == SUCCESS

        // On empty sourcesets, the task should be UP-TO-DATE
        result.task(LIBA_PATH + taskName() + capitalize(ANDROID_TEST_SOURCESET)).outcome == UP_TO_DATE
        result.task(LIBB_PATH + taskName() + capitalize(ANDROID_TEST_SOURCESET)).outcome == UP_TO_DATE

        // The reports must exist
        file(LIBA_DIRNAME + reportFileName(MAIN_SOURCESET)).exists()
        file(LIBA_DIRNAME + reportFileName(TEST_SOURCESET)).exists()
        file(LIBB_DIRNAME + reportFileName(MAIN_SOURCESET)).exists()
        file(LIBB_DIRNAME + reportFileName(TEST_SOURCESET)).exists()

        // On empty sourcesets, no report file is generated
        !file(LIBA_DIRNAME + reportFileName(ANDROID_TEST_SOURCESET)).exists()
        !file(LIBB_DIRNAME + reportFileName(ANDROID_TEST_SOURCESET)).exists()
    }
}
