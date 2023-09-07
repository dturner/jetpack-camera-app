/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.google.jetpackcamera.domain.camera

import android.util.Rational
import android.view.Display
import androidx.camera.core.Preview
import com.google.jetpackcamera.settings.model.AspectRatio
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.FlashModeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Data layer for camera.
 */
interface CameraUseCase {

    /**
     * Initializes the camera.
     */
    suspend fun initialize(initialConfig: Config)

    /**
     * Starts the camera with the provided [Preview.SurfaceProvider].
     *
     * The camera will run until the calling coroutine is cancelled.
     */
    suspend fun runCamera(
        surfaceProvider: Preview.SurfaceProvider,
        currentCameraSettings: CameraAppSettings
    )

    suspend fun takePicture()

    suspend fun startVideoRecording()

    fun stopVideoRecording()

    fun setZoomScale(scale: Float): Float

    fun setFlashMode(flashMode: FlashMode)

    fun tapToFocus(display: Display, surfaceWidth: Int, surfaceHeight: Int, x: Float, y: Float)

    suspend fun setConfig(config: Config)

    val config : StateFlow<Config>

    companion object {
        const val INVALID_ZOOM_SCALE = -1f
    }

    data class Config(
        val lensFacing: LensFacing = LensFacing.LENS_FACING_FRONT,
        val captureMode: CaptureMode = CaptureMode.CAPTURE_MODE_SINGLE_STREAM,
        val aspectRatio: AspectRatio = AspectRatio.ASPECT_RATIO_4_3,
        val flashMode: FlashMode = FlashMode.FLASH_MODE_OFF
    )

    enum class LensFacing {
        LENS_FACING_FRONT,
        LENS_FACING_BACK
    }

    enum class CaptureMode {
        CAPTURE_MODE_MULTI_STREAM,
        CAPTURE_MODE_SINGLE_STREAM
    }

    enum class AspectRatio(val rational: Rational) {
        ASPECT_RATIO_4_3(Rational(4, 3)),
        ASPECT_RATIO_16_9(Rational(16, 9))
    }

    enum class FlashMode {
        FLASH_MODE_OFF,
        FLASH_MODE_ON,
        FLASH_MODE_AUTO
    }
}