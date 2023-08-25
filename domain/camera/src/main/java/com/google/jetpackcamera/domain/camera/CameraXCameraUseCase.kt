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

import android.app.Application
import android.content.ContentValues
import android.hardware.camera2.CaptureRequest
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.view.Display
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraSelector.LensFacing
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.Preview.SurfaceProvider
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import com.google.jetpackcamera.domain.camera.CameraUseCase.Companion.INVALID_ZOOM_SCALE
import com.google.jetpackcamera.settings.SettingsRepository
import com.google.jetpackcamera.settings.model.AspectRatio
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.FlashModeStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "CameraXCameraUseCase"
private val ASPECT_RATIO_16_9 = Rational(16, 9)

interface CameraController {
    fun run(coroutineScope: CoroutineScope, surfaceProvider: SurfaceProvider): Job
}

class CameraControllerFactory @Inject constructor(
    private val application: Application, private val settingsRepository: SettingsRepository
) {
    suspend fun create(): CameraController {
        val cameraProvider = application.getCameraProvider()

        val availableCameraLens =
            listOf(
                CameraSelector.LENS_FACING_BACK,
                CameraSelector.LENS_FACING_FRONT
            ).filter { lensFacing ->
                cameraProvider.hasCamera(CameraXCameraUseCase.cameraLensToSelector(lensFacing))
            }

        //updates values for available camera lens if necessary
        settingsRepository.updateAvailableCameraLens(
            frontLensAvailable = CameraSelector.LENS_FACING_FRONT in availableCameraLens,
            backLensAvailable = CameraSelector.LENS_FACING_BACK in availableCameraLens
        )

        return cameraProvider.asCameraController()
    }
}

private fun ProcessCameraProvider.asCameraController() = object: CameraController {
    override fun run(coroutineScope: CoroutineScope, surfaceProvider: SurfaceProvider): Job {
        Log.d(TAG, "startPreview")

        val cameraSelector =
            CameraXCameraUseCase.cameraLensToSelector(getLensFacing(currentCameraSettings.default_front_camera))

        Preview.Builder().also { builder ->
            Camera2Interop.Extender(builder).setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }.build().setSurfaceProvider(surfaceProvider)

        coroutineScope.launch {
            runWith(cameraSelector, useCaseGroup) {
                camera = it
                awaitCancellation()
            }
        }
    }

}

private suspend fun Application.getCameraProvider() =
    ProcessCameraProvider.getInstance(this).await()

/**
 * CameraX based implementation for [CameraUseCase]
 */
@ExperimentalCamera2Interop
class CameraXCameraUseCase @Inject constructor(
    private val application: Application,
    private val defaultDispatcher: CoroutineDispatcher,
    private val settingsRepository: SettingsRepository
) : CameraUseCase {

    private var camera: Camera? = null

    //TODO apply flash from settings

    //    private val previewUseCase = Preview.Builder().build()

    private lateinit var useCaseGroup: UseCaseGroup

    private lateinit var aspectRatio: AspectRatio
    private var singleStreamCaptureEnabled = false
    private var isFrontFacing = true


    override suspend fun takePicture() =
        imageCaptureUseCase.takePicture(defaultDispatcher.asExecutor())

    private suspend fun ImageCapture.takePicture(executor: Executor) =
        suspendCoroutine {
            takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        Log.d(TAG, "onCaptureSuccess")
                        it.resume(Unit)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        super.onError(exception)
                        it.resumeWithException(exception)
                        Log.d(TAG, "takePicture onError: $exception")
                    }
                })
        }

    override fun startVideoRecording(scope: CoroutineScope): Job {
        Log.d(TAG, "recordVideo")
        val name = "JCA-recording-${Date()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            application.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()
        return videoCaptureUseCase.startIn(mediaStoreOutput, scope)
    }

    private fun VideoCapture<Recorder>.startIn(
        outputOptions: MediaStoreOutputOptions,
        scope: CoroutineScope
    ): Job =
        scope.launch {
            output
                .prepareRecording(application, outputOptions)
                .start(ContextCompat.getMainExecutor(application), Consumer { videoRecordEvent ->
                    run {
                        Log.d(TAG, videoRecordEvent.toString())
                    }
                })
                .use {
                    awaitCancellation()
                }
        }

    override fun setZoomScale(scale: Float): Float {
        val zoomState = getZoomState() ?: return INVALID_ZOOM_SCALE
        val finalScale =
            (zoomState.zoomRatio * scale).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(finalScale)
        return finalScale
    }

    private fun getZoomState(): ZoomState? = camera?.cameraInfo?.zoomState?.value

    // flips the camera to the designated lensFacing direction
    override suspend fun flipCamera(isFrontFacing: Boolean) {
        cameraProvider.rebindUseCases(
            createUseCaseGroup(previewUseCase, aspectRatio, singleStreamCaptureEnabled),
            isFrontFacing
        )
    }

    override fun tapToFocus(
        display: Display,
        surfaceWidth: Int,
        surfaceHeight: Int,
        x: Float,
        y: Float
    ) {
        if (camera != null) {
            val meteringPoint = DisplayOrientedMeteringPointFactory(
                display,
                camera!!.cameraInfo,
                surfaceWidth.toFloat(),
                surfaceHeight.toFloat()
            )
                .createPoint(x, y);

            val action = FocusMeteringAction.Builder(meteringPoint).build()

            camera!!.cameraControl.startFocusAndMetering(action)
            Log.d(TAG, "Tap to focus on: $meteringPoint")
        }
    }

    override fun setFlashMode(flashModeStatus: FlashModeStatus) {
        imageCaptureUseCase.setFlashMode(flashModeStatus)
        Log.d(TAG, "Set flash mode to: ${imageCaptureUseCase.flashMode}")
    }

    private fun ImageCapture.setFlashMode(flashModeStatus: FlashModeStatus) {
        flashMode = when (flashModeStatus) {
            FlashModeStatus.OFF -> ImageCapture.FLASH_MODE_OFF // 2
            FlashModeStatus.ON -> ImageCapture.FLASH_MODE_ON // 1
            FlashModeStatus.AUTO -> ImageCapture.FLASH_MODE_AUTO // 0
        }
    }

    override suspend fun setAspectRatio(aspectRatio: AspectRatio, isFrontFacing: Boolean) {
    }

    suspend fun ProcessCameraProvider.reconfigure(
        preview: Preview,
        aspectRatio: AspectRatio,
        singleStreamCaptureEnabled: Boolean,
        isFrontFacing: Boolean
    ) {
        rebindUseCases(
            createUseCaseGroup(previewUseCase, aspectRatio, singleStreamCaptureEnabled),
            isFrontFacing
        )
    }

    override suspend fun toggleCaptureMode() {
        singleStreamCaptureEnabled = !singleStreamCaptureEnabled
        Log.d(TAG, "Changing CaptureMode: singleStreamCaptureEnabled: $singleStreamCaptureEnabled")
        cameraProvider.rebindUseCases(
            createUseCaseGroup(previewUseCase, aspectRatio, singleStreamCaptureEnabled),
            isFrontFacing
        )
    }

    private fun createUseCaseGroup(
        preview: Preview, aspectRatio: AspectRatio, singleStreamCaptureEnabled: Boolean
    ) =
        UseCaseGroup.Builder().apply {
            setViewPort(ViewPort.Builder(aspectRatio.ratio, preview.targetRotation).build())
            addUseCase(preview)
            addUseCase(imageCaptureUseCase)
            addUseCase(videoCaptureUseCase)

            if (singleStreamCaptureEnabled) {
                addEffect(SingleSurfaceForcingEffect())
            }
        }
            .build()

    // converts LensFacing from datastore to @LensFacing Int value
    private fun getLensFacing(isFrontFacing: Boolean): Int =
        when (isFrontFacing) {
            true -> CameraSelector.LENS_FACING_FRONT
            false -> CameraSelector.LENS_FACING_BACK
        }

    private suspend fun ProcessCameraProvider.rebindUseCases(
        useCaseGroup: UseCaseGroup,
        isFrontFacing: Boolean = true,
    ) {
        val cameraSelector = cameraLensToSelector(
            getLensFacing(isFrontFacing)
        )
        unbindAll()
        runWith(cameraSelector, useCaseGroup) {
            camera = it
            Camera2CameraControl.from(it.cameraControl).captureRequestOptions =
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    ).build()
            awaitCancellation()
        }
    }

    companion object {
        private fun cameraLensToSelector(@LensFacing lensFacing: Int): CameraSelector =
            when (lensFacing) {
                CameraSelector.LENS_FACING_FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                CameraSelector.LENS_FACING_BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                else -> throw IllegalArgumentException("Invalid lens facing type: $lensFacing")
            }

        private val camera2InteropBuilder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )

        private val recorder =
            Recorder.Builder().setExecutor(defaultDispatcher.asExecutor()).build()
        private val imageCaptureUseCase = ImageCapture.Builder().build()
        private val videoCaptureUseCase = VideoCapture.withOutput(recorder)

        private val previewUseCase = Preview.Builder().also { builder ->
            Camera2Interop.Extender(builder).setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }.build()
    }
}
