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

package com.google.jetpackcamera.feature.preview

import android.util.Log
import android.view.Display
import androidx.camera.core.Camera
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview.SurfaceProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.jetpackcamera.domain.camera.CameraUseCase
import com.google.jetpackcamera.domain.camera.CameraUseCase.LensFacing
import com.google.jetpackcamera.settings.SettingsRepository
import com.google.jetpackcamera.settings.model.DEFAULT_CAMERA_APP_SETTINGS
import com.google.jetpackcamera.settings.model.FlashModeStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.google.jetpackcamera.settings.model.AspectRatio
import com.google.jetpackcamera.settings.model.CameraAppSettings


private const val TAG = "PreviewViewModel"

/**
 * [ViewModel] for [PreviewScreen].
 */
@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val cameraUseCase: CameraUseCase,
    private val settingsRepository: SettingsRepository // only reads from settingsRepository. do not push changes to repository from here
) : ViewModel() {

    private val _previewUiState: MutableStateFlow<PreviewUiState> =
        MutableStateFlow(PreviewUiState(currentCameraSettings = DEFAULT_CAMERA_APP_SETTINGS))

    val previewUiState: StateFlow<PreviewUiState> = _previewUiState

    private var runningCameraJob: Job? = null
    private var recordingJob: Job? = null

    init {
        lateinit var initialCameraSettings : CameraAppSettings
        viewModelScope.launch {
            settingsRepository.cameraAppSettings.collect { settings ->
                //TODO: only update settings that were actually changed
                // currently resets all "quick" settings to stored settings
                _previewUiState
                    .emit(previewUiState.value.copy(currentCameraSettings = settings))
                initialCameraSettings = settings

            }
        }
        initializeCamera(initialCameraSettings)

        viewModelScope.launch {
            cameraUseCase.config.collect {
                _previewUiState.emit(
                    it.toUiStateWith(previewUiState.value)
                )
            }
        }
    }

    private fun initializeCamera(initialCameraAppSettings: CameraAppSettings) {
        // TODO(yasith): Handle CameraUnavailableException
        Log.d(TAG, "initializeCamera")
        viewModelScope.launch {
            cameraUseCase.initialize(
                CameraUseCase.Config(
                    lensFacing = if(initialCameraAppSettings.default_front_camera)  LensFacing.LENS_FACING_FRONT else LensFacing.LENS_FACING_BACK,
                )
            )
            _previewUiState.emit(
                previewUiState.value.copy(
                    cameraState = CameraState.READY
                )
            )
        }
    }

    fun runCamera(surfaceProvider: SurfaceProvider) {
        Log.d(TAG, "runCamera")
        stopCamera()
        runningCameraJob = viewModelScope.launch {
            // TODO(yasith): Handle Exceptions from binding use cases
            cameraUseCase.runCamera(
                surfaceProvider,
                previewUiState.value.currentCameraSettings
            )
        }
    }

    fun stopCamera() {
        Log.d(TAG, "stopCamera")
        runningCameraJob?.apply {
            if (isActive) {
                cancel()
            }
        }
    }

    fun setFlash(flashModeStatus: FlashModeStatus) {
        viewModelScope.launch {
            _previewUiState.emit(
                previewUiState.value.copy(
                    currentCameraSettings =
                    previewUiState.value.currentCameraSettings.copy(
                        flash_mode_status = flashModeStatus
                    )
                )
            )
            cameraUseCase.setFlashMode(previewUiState.value.currentCameraSettings.flash_mode_status)
        }
    }

    fun setAspectRatio(aspectRatio: AspectRatio) {
        viewModelScope.launch {
            _previewUiState.emit(
                previewUiState.value.copy(
                    currentCameraSettings =
                    previewUiState.value.currentCameraSettings.copy(
                        aspect_ratio = aspectRatio
                    )
                )
            )
            val currentConfig = cameraUseCase.config.value
            cameraUseCase.setConfig(
                currentConfig.copy(
                    aspectRatio = aspectRatio.toConfigAspectRatio()
                )
            )
        }
    }

    fun toggleCaptureMode() {
        val currentConfig = cameraUseCase.config.value
        viewModelScope.launch {
            cameraUseCase.setConfig(
                currentConfig.copy(
                    captureMode = currentConfig.captureMode.next()
                )
            )
        }
    }

    fun flipCamera() {
        // Only flip if both directions are available
        if (previewUiState.value.currentCameraSettings.back_camera_available
            && previewUiState.value.currentCameraSettings.front_camera_available
        ) {
            val currentConfig = cameraUseCase.config.value
            viewModelScope.launch {
                cameraUseCase.setConfig(
                    currentConfig.copy(
                        lensFacing = currentConfig.lensFacing.next()
                    )
                )
            }
        }
    }

    fun captureImage() {
        Log.d(TAG, "captureImage")
        viewModelScope.launch {
            try {
                cameraUseCase.takePicture()
                Log.d(TAG, "cameraUseCase.takePicture success")
            } catch (exception: ImageCaptureException) {
                Log.d(TAG, "cameraUseCase.takePicture error")
                Log.d(TAG, exception.toString())
            }
        }
    }

    fun startVideoRecording() {
        Log.d(TAG, "startVideoRecording")
        recordingJob = viewModelScope.launch {

            try {
                cameraUseCase.startVideoRecording()
                _previewUiState.emit(
                    previewUiState.value.copy(
                        videoRecordingState = VideoRecordingState.ACTIVE
                    )
                )
                Log.d(TAG, "cameraUseCase.startRecording success")
            } catch (exception: IllegalStateException) {
                Log.d(TAG, "cameraUseCase.startVideoRecording error")
                Log.d(TAG, exception.toString())
            }
        }
    }

    fun stopVideoRecording() {
        Log.d(TAG, "stopVideoRecording")
        viewModelScope.launch {
            _previewUiState.emit(
                previewUiState.value.copy(
                    videoRecordingState = VideoRecordingState.INACTIVE
                )
            )
        }
        cameraUseCase.stopVideoRecording()
        recordingJob?.cancel()
    }

    fun setZoomScale(scale: Float): Float {
        return cameraUseCase.setZoomScale(scale = scale)
    }

    // modify ui values
    fun toggleQuickSettings() {
        toggleQuickSettings(!previewUiState.value.quickSettingsIsOpen)
    }

    fun toggleQuickSettings(isOpen: Boolean) {
        viewModelScope.launch {
            _previewUiState.emit(
                previewUiState.value.copy(
                    quickSettingsIsOpen = isOpen
                )
            )
        }
    }

    fun tapToFocus(display: Display, surfaceWidth: Int, surfaceHeight: Int, x: Float, y: Float) {
        cameraUseCase.tapToFocus(
            display = display,
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            x = x,
            y = y
        )
    }
}

private fun CameraUseCase.Config.toUiStateWith(
    currentUiState: PreviewUiState
) = currentUiState.copy(
    lensFacing = this.lensFacing,
    captureMode = this.captureMode.toUiStateCaptureMode()
)

private inline fun <reified T: Enum<T>> T.next(): T {
    val values = enumValues<T>()
    val nextOrdinal = (ordinal + 1) % values.size
    return values[nextOrdinal]
}

private fun AspectRatio.toConfigAspectRatio() = when(this) {
    AspectRatio.THREE_FOUR -> CameraUseCase.AspectRatio.ASPECT_RATIO_4_3
    AspectRatio.NINE_SIXTEEN -> CameraUseCase.AspectRatio.ASPECT_RATIO_16_9
    // 1:1 is not supported by CameraX, falling back to 4:3
    AspectRatio.ONE_ONE -> CameraUseCase.AspectRatio.ASPECT_RATIO_4_3
}

private fun CameraUseCase.CaptureMode.toUiStateCaptureMode() = when(this) {
    CameraUseCase.CaptureMode.CAPTURE_MODE_MULTI_STREAM -> CaptureMode.CAPTURE_MODE_MULTI_STREAM
    CameraUseCase.CaptureMode.CAPTURE_MODE_SINGLE_STREAM -> CaptureMode.CAPTURE_MODE_SINGLE_STREAM
}
