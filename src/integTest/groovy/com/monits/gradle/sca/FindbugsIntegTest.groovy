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

import com.monits.gradle.sca.fixture.AbstractPerSourceSetPluginIntegTestFixture
import com.monits.gradle.sca.io.TestFile
import org.gradle.testkit.runner.BuildResult
import org.gradle.util.GradleVersion
import spock.lang.Unroll

import static org.gradle.testkit.runner.TaskOutcome.FAILED
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.hamcrest.CoreMatchers.containsString
import static org.hamcrest.core.IsNot.not
import static org.junit.Assert.assertThat

/**
 * Integration test of Findbugs tasks.
 */
class FindbugsIntegTest extends AbstractPerSourceSetPluginIntegTestFixture {
    @SuppressWarnings('MethodName')
    @Unroll('Findbugs #findbugsVersion should run when using gradle #version')
    void 'findbugs is run'() {
        given:
        writeBuildFile()
        useEmptySuppressionFilter()
        goodCode()

        when:
        BuildResult result = gradleRunner()
            .withGradleVersion(version)
            .build()

        then:
        if (GradleVersion.version(version) >= GradleVersion.version('2.5')) {
            // Executed task capture is only available in Gradle 2.5+
            result.task(taskName()).outcome == SUCCESS
        }

        // Make sure report exists and was using the expected tool version
        reportFile().exists()
        reportFile().assertContents(containsString("<BugCollection version=\"$findbugsVersion\""))

        // Plugins should be automatically added and enabled
        reportFile().assertContents(containsString('<Plugin id="com.mebigfatguy.fbcontrib" enabled="true"/>'))
        reportFile().assertContents(containsString('<Plugin id="jp.co.worksap.oss.findbugs" enabled="true"/>'))

        where:
        version << TESTED_GRADLE_VERSIONS
        findbugsVersion = ToolVersions.findbugsVersion
    }

    @SuppressWarnings('MethodName')
    void 'findbugs downloads remote suppression config'() {
        given:
        writeBuildFile()
        // setup a remote config
        buildScriptFile() << '''
            staticCodeAnalysis {
                findbugsExclude = 'http://static.monits.com/findbugs-exclusions-android.xml'
            }
        '''
        goodCode()

        when:
        BuildResult result = gradleRunner()
                .build()

        then:
        result.task(taskName()).outcome == SUCCESS

        // The config must exist
        file('config/findbugs/excludeFilter-main.xml').exists()
        file('config/findbugs/excludeFilter-test.xml').exists()

        // Make sure findbugs report exists
        reportFile().exists()
    }

    @SuppressWarnings('MethodName')
    void 'running offline fails download'() {
        given:
        writeBuildFile()
        // setup a remote config
        buildScriptFile() << '''
            staticCodeAnalysis {
                findbugsExclude = 'http://static.monits.com/findbugs-exclusions-android.xml'
            }
        '''
        goodCode()

        when:
        BuildResult result = gradleRunner()
                .withArguments('check', '--stacktrace', '--offline')
                .buildAndFail()

        then:
        result.task(':downloadFindbugsExcludeFilterMain').outcome == FAILED
        assertThat(result.output, containsString('Running in offline mode, but there is no cached version'))
    }

    @SuppressWarnings('MethodName')
    void 'running offline with a cached file passes but warns'() {
        given:
        writeBuildFile()
        // setup a remote config
        buildScriptFile() << '''
            staticCodeAnalysis {
                findbugsExclude = 'http://static.monits.com/findbugs-exclusions-android.xml'
            }
        '''
        writeEmptySuppressionFilter('main')
        writeEmptySuppressionFilter('test')
        goodCode()

        when:
        BuildResult result = gradleRunner()
                .withArguments('check', '--stacktrace', '--offline')
                .build()

        then:
        result.task(':downloadFindbugsExcludeFilterMain').outcome == SUCCESS
        result.task(':downloadFindbugsExcludeFilterTest').outcome == SUCCESS
        assertThat(result.output, containsString('Running in offline mode. Using a possibly outdated version of'))
    }

    @SuppressWarnings(['MethodName', 'LineLength'])
    void 'Findbugs-related annotations are available'() {
        given:
        writeBuildFile()
        useEmptySuppressionFilter()
        file('src/main/java/com/monits/ClassA.java') << '''
            package com.monits;

            import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

            @SuppressFBWarnings(value = "MISSING_FIELD_IN_TO_STRING", justification = "doesn't provide meaningful information")
            public class ClassA {
                public boolean isFoo(Object arg) {
                    return true;
                }
            }
        '''

        when:
        BuildResult result = gradleRunner()
                .build()

        then:
        result.task(taskName()).outcome == SUCCESS

        // The report must exist, and not complain on missing classes from liba
        reportFile().exists()
        reportFile().assertContents(containsString('<Errors errors="0" missingClasses="0">'))
    }

    @SuppressWarnings('MethodName')
    void 'Android generated classes are available'() {
        given:
        writeAndroidBuildFile(DEFAULT_ANDROID_VERSION)
        writeAndroidManifest()
        useEmptySuppressionFilter()
        file('src/main/res/values/strings.xml') <<
            '''<?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="greeting">Hey there!</string>
                </resources>
            '''
        file('src/main/java/com/monits/ClassA.java') << '''
            package com.monits;

            import com.monits.staticCodeAnalysis.R;

            public class ClassA {
                public boolean isFoo() {
                    return R.string.greeting == 1;
                }
            }
        '''

        when:
        BuildResult result = gradleRunner()
                .withGradleVersion(gradleVersionForAndroid(DEFAULT_ANDROID_VERSION))
                .build()

        then:
        result.task(taskName()).outcome == SUCCESS

        // The report must exist, and not complain on missing classes from liba
        reportFile().exists()
        reportFile().assertContents(containsString('<Errors errors="0" missingClasses="0">'))
    }

    @Unroll('Android classes are available when using android gradle plugin #androidVersion and gradle #gradleVersion')
    @SuppressWarnings('MethodName')
    void 'Android SDK classes are available'() {
        given:
        writeAndroidBuildFile(androidVersion)
        writeAndroidManifest()
        useEmptySuppressionFilter()
        file('src/main/java/com/monits/ClassA.java') << '''
            package com.monits;

            import android.view.View;

            public class ClassA {
                public boolean isFoo() {
                    return new View(null).callOnClick();
                }
            }
        '''

        when:
        BuildResult result = gradleRunner()
                .withGradleVersion(gradleVersion)
                .build()

        then:
        if (GradleVersion.version(gradleVersion) >= GradleVersion.version('2.5')) {
            result.task(taskName()).outcome == SUCCESS
        }

        // The report must exist, and not complain on missing classes from liba
        reportFile().exists()
        reportFile().assertContents(containsString('<Errors errors="0" missingClasses="0">'))

        where:
        androidVersion << AndroidLintIntegTest.ANDROID_PLUGIN_VERSIONS
        gradleVersion = gradleVersionForAndroid(androidVersion)
    }

    @SuppressWarnings('MethodName')
    void 'multimodule android project has all classes'() {
        given:
        setupMultimoduleAndroidProject()

        when:
        BuildResult result = gradleRunner()
                .withGradleVersion(gradleVersionForAndroid(DEFAULT_ANDROID_VERSION))
                .build()

        then:
        result.task(':libb' + taskName()).outcome == SUCCESS

        // The report must exist, and not complain on missing classes from liba
        TestFile finbugsReport = file('libb/' + reportFileName(null))
        finbugsReport.exists()
        finbugsReport.assertContents(not(containsString('<MissingClass>liba.ClassA</MissingClass>')))

        // make sure nothing is reported
        finbugsReport.assertContents(containsString('<Errors errors="0" missingClasses="0">'))
    }

    @SuppressWarnings('MethodName')
    void 'dsl allows to override rules per sourceset'() {
        given:
        writeBuildFile() << '''
            staticCodeAnalysis {
                sourceSetConfig {
                    test {
                        findbugsExclude = 'test-findbugsExclude.xml'
                    }
                }
            }
        '''
        file('test-findbugsExclude.xml') << '''
            <FindBugsFilter>
                <Match>
                    <Or>
                        <Bug pattern="UNKNOWN_NULLNESS_OF_PARAMETER" />
                    </Or>
                </Match>
            </FindBugsFilter>
        '''
        goodCode()

        when:
        BuildResult result = gradleRunner()
                .build()

        then:
        result.task(taskName()).outcome == SUCCESS

        // Make sure findbugs reports exist
        reportFile().exists()
        reportFile(TEST_SOURCESET).exists()

        // But results should differ in spite of being very similar code
        reportFile().assertContents(containsString('<BugInstance type="UNKNOWN_NULLNESS_OF_PARAMETER"'))
        reportFile(TEST_SOURCESET)
                .assertContents(not(containsString('<BugInstance type="UNKNOWN_NULLNESS_OF_PARAMETER"')))
    }

    @SuppressWarnings('MethodName')
    void 'reports include just classes from their sourcesets'() {
        given:
        writeAndroidBuildFile(DEFAULT_ANDROID_VERSION)
        writeAndroidManifest()
        useEmptySuppressionFilter()
        goodCode()

        when:
        BuildResult result = gradleRunner()
                .withGradleVersion(gradleVersionForAndroid(DEFAULT_ANDROID_VERSION))
                .build()

        then:
        result.task(taskName()).outcome == SUCCESS

        // The report must exist, analyzed classes must match sourcesets, and not complain of missing classes
        reportFile().exists()
        reportFile()
            .assertContents(containsString('<FileStats path="com/monits/Class1.java" '))
            .assertContents(not(containsString('<FileStats path="com/monits/Class1Test.java" ')))
            .assertContents(containsString('<Errors errors="0" missingClasses="0">'))

        reportFile(TEST_SOURCESET).exists()
        reportFile(TEST_SOURCESET)
            .assertContents(not(containsString('<FileStats path="com/monits/Class1.java" ')))
            .assertContents(containsString('<FileStats path="com/monits/Class1Test.java" '))
            .assertContents(containsString('<Errors errors="0" missingClasses="0">'))
    }

    @Override
    String reportFileName(final String sourceSet) {
        "build/reports/findbugs/findbugs${sourceSet ? "-${sourceSet}" : '-main'}.xml"
    }

    @Override
    String taskName() {
        ':findbugs'
    }

    @Override
    String toolName() {
        'findbugs'
    }

    @SuppressWarnings('GStringExpressionWithinString')
    void useEmptySuppressionFilter() {
        writeEmptySuppressionFilter()

        buildScriptFile() << '''
            staticCodeAnalysis {
                findbugsExclude = "${project.rootDir}/config/findbugs/excludeFilter.xml"
            }
        '''
    }

    TestFile writeEmptySuppressionFilter(final String sourceSet = null) {
        file("config/findbugs/excludeFilter${sourceSet ? "-${sourceSet}" : ''}.xml") << '''
            <FindBugsFilter>
            </FindBugsFilter>
        ''' as TestFile
    }
}
