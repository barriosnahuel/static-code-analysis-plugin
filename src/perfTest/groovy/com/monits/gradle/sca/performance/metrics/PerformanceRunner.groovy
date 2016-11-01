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
package com.monits.gradle.sca.performance.metrics

import groovy.transform.CompileStatic
import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion

/**
 * A helper to run performance analysis
 */
@CompileStatic
class PerformanceRunner {
    // To give us < 0.3% odds of a falsely identified regression.
    // https://en.wikipedia.org/wiki/Standard_deviation#Rules_for_normally_distributed_data
    private static final BigDecimal NUM_STANDARD_ERRORS_FROM_MEAN = 3.0G
    // We want to ignore regressions of less than 2% over the baseline.
    private static final BigDecimal MINIMUM_REGRESSION_PERCENTAGE = 0.02G

    private static final int WARM_UP_ITERATIONS = 3
    private static final int MEASURE_ITERATIONS = 10

    private static final long SLEEP_AFTER_RUN_MS = 500L
    private static final long SLEEP_AFTER_WARM_UP_MS = 5000L

    private static final int PERCENT = 100

    private final GradleVersion version

    private final AggregateExecutionMetrics results = new AggregateExecutionMetrics()

    PerformanceRunner(final GradleVersion version) {
        this.version = version
    }

    void exercise(final GradleRunner runner) {
        GradleRunner cleanRunner = GradleRunner.create()
            .withGradleVersion(version.version)
            .withProjectDir(runner.projectDir)
            .withArguments('clean')

        // warm up
        for (int i = 0; i < WARM_UP_ITERATIONS; i++) {
            if (i > 0) {
                sleep(SLEEP_AFTER_RUN_MS)
            }
            runner.build()
            cleanRunner.build()
        }

        println 'Warm up is done'
        sleep(SLEEP_AFTER_WARM_UP_MS)

        // measure
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            if (i > 0) {
                sleep(SLEEP_AFTER_RUN_MS)
            }
            results.add(TimedRunnable.run { runner.build() })
            cleanRunner.build()
            println "Iteration ${i + 1} / ${MEASURE_ITERATIONS} is done."
        }
    }

    void assertVersionHasNotRegressed(final PerformanceRunner baseline) {
        baseline.assertEveryBuildSucceeds()
        assertEveryBuildSucceeds()

        if (results.totalTime.average - baseline.results.totalTime.average > maxExecutionTimeRegression) {
            throw new AssertionError("New version is slower.\nResults: ${results.totalTime}" +
                "\nBaseline: ${baseline.results.totalTime}" as Object)
        }

        // We are on par or faster, check for informational purposes
        if (baseline.results.totalTime.average - results.totalTime.average > baseline.maxExecutionTimeRegression) {
            println "We are actually faster than old plugin using ${baseline.version} under ${version} " +
                "by ${baseline.results.totalTime.average / results.totalTime.average * PERCENT - PERCENT}%"
        } else {
            println "We are on par with old plugin using ${baseline.version} under ${version}"
        }
    }

    private void assertEveryBuildSucceeds() {
        assert results.failures.empty : 'Some builds have failed.'
    }

    private BigDecimal getMaxExecutionTimeRegression() {
        BigDecimal allowedPercentageRegression = results.totalTime.average *
            MINIMUM_REGRESSION_PERCENTAGE
        BigDecimal allowedStatisticalRegression = results.totalTime.standardErrorOfMean *
            NUM_STANDARD_ERRORS_FROM_MEAN

        (allowedStatisticalRegression > allowedPercentageRegression) ?
            allowedStatisticalRegression : allowedPercentageRegression
    }
}
