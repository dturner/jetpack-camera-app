/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jetpackcamera.benchmark

import android.content.Intent
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstFrameBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun timeToFirstFrame(){
        benchmarkFirstFrame()
    }

    /**
     * the benchmark for first frame tracks the amount of time it takes from preview loading on the
     * screen to when the use case is able to start capturing frames
     */
    @OptIn(ExperimentalMetricApi::class)
    private fun benchmarkFirstFrame(
        startupMode: StartupMode? = StartupMode.COLD,
        timeout: Long = 15000,
        intent: Intent? = null
    ) {
        benchmarkRule.measureRepeated(
            packageName = JCA_PACKAGE_NAME,
            metrics = listOf(
                StartupTimingMetric(),
                TraceSectionMetric(sectionName = FIRST_FRAME_TRACE, targetPackageOnly = false)
            ),
            iterations = DEFAULT_TEST_ITERATIONS,
            startupMode = startupMode,
            setupBlock = {
                allowCamera()
            }
        ) {
            pressHome()
            if (intent == null) startActivityAndWait() else startActivityAndWait(intent)
            device.waitForIdle()

            clickCaptureButton(device)

            // ensure trace is closed
            findObjectByRes(
                device = device,
                testTag = IMAGE_CAPTURE_SUCCESS_TOAST,
                timeout = timeout,
                shouldFailIfNotFound = true
            )
        }
    }
}