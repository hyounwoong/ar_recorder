/*
 * Copyright 2021 Google LLC
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
package com.ar.recorder

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES30
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.ar.recorder.common.helpers.DisplayRotationHelper
import com.ar.recorder.common.helpers.TrackingStateHelper
import com.ar.recorder.common.samplerender.Framebuffer
import com.ar.recorder.common.samplerender.GLError
import com.ar.recorder.common.samplerender.SampleRender
import com.ar.recorder.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Renders the AR Recorder application. */
class ArRecorderRenderer(val activity: ArRecorderActivity) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  companion object {
    val TAG = "ArRecorderRenderer"
    
    private const val SAMPLING_INTERVAL_NS = 500_000_000L // 0.5 seconds (2fps)
  }

  lateinit var render: SampleRender
  lateinit var backgroundRenderer: BackgroundRenderer
  var hasSetTextureNames = false

  // Recording state
  private val isRecording = AtomicBoolean(false)
  private var lastSampleTimestamp: Long = 0
  private var sessionStartTime: Long = 0
  private var outputDir: File? = null
  private var metadataFile: FileOutputStream? = null

  val session
    get() = activity.arCoreSessionHelper.session

  val displayRotationHelper = DisplayRotationHelper(activity)
  val trackingStateHelper = TrackingStateHelper(activity)

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
    stopRecording()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    this.render = render
    try {
      backgroundRenderer = BackgroundRenderer(render)
      // Disable depth visualization and occlusion
      backgroundRenderer.setUseDepthVisualization(render, false)
      backgroundRenderer.setUseOcclusion(render, false)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
  }

  override fun onDrawFrame(render: SampleRender) {
    val session = session ?: return

    // Texture names should only be set once on a GL thread unless they change.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }

    // Notify ARCore session that the view size changed
    displayRotationHelper.updateSessionIfNeeded(session)

    // Obtain the current frame from ARSession
    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        Log.e(TAG, "Camera not available during onDrawFrame", e)
        showError("Camera not available. Try restarting the app.")
        return
      }

    val camera = frame.camera

    // BackgroundRenderer.updateDisplayGeometry must be called every frame
    backgroundRenderer.updateDisplayGeometry(frame)

    // Keep the screen unlocked while tracking
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    // Draw background
    if (frame.timestamp != 0L) {
      backgroundRenderer.drawBackground(render)
    }

    // Sample frame if recording and tracking
    if (isRecording.get() && camera.trackingState == TrackingState.TRACKING) {
      sampleFrame(frame, camera)
    }
  }

  fun startRecording() {
    if (isRecording.get()) {
      Log.w(TAG, "Already recording")
      return
    }

    try {
      // Create output directory
      val recordingsDir = File(activity.getExternalFilesDir(null), "recordings")
      if (!recordingsDir.exists()) {
        recordingsDir.mkdirs()
      }

      val timestamp = System.currentTimeMillis()
      outputDir = File(recordingsDir, "session_$timestamp")
      outputDir!!.mkdirs()

      // Create metadata file
      val metadataFilePath = File(outputDir, "session_$timestamp.jsonl")
      metadataFile = FileOutputStream(metadataFilePath)

      sessionStartTime = System.nanoTime()
      lastSampleTimestamp = 0
      isRecording.set(true)

      Log.i(TAG, "Recording started: ${outputDir!!.absolutePath}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start recording", e)
      showError("Failed to start recording: $e")
      isRecording.set(false)
    }
  }

  fun stopRecording() {
    if (!isRecording.get()) {
      return
    }

    try {
      isRecording.set(false)
      metadataFile?.close()
      metadataFile = null

      Log.i(TAG, "Recording stopped: ${outputDir?.absolutePath}")
      activity.runOnUiThread {
        activity.view.statusText.text = activity.getString(R.string.status_saved)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to stop recording", e)
    }
  }

  private fun sampleFrame(frame: Frame, camera: Camera) {
    val currentTime = frame.timestamp
    
    // Check if enough time has passed since last sample (2fps = 0.5s interval)
    if (currentTime - lastSampleTimestamp < SAMPLING_INTERVAL_NS) {
      return
    }

    lastSampleTimestamp = currentTime

    // Sample on background thread to avoid blocking rendering
    Thread {
      try {
        val image = frame.acquireCameraImage()
        try {
          saveFrame(image, frame, camera)
        } finally {
          image.close()
        }
      } catch (e: NotYetAvailableException) {
        // Image not available yet, skip this frame
      } catch (e: Exception) {
        Log.e(TAG, "Failed to sample frame", e)
      }
    }.start()
  }

  private fun saveFrame(image: Image, frame: Frame, camera: Camera) {
    if (outputDir == null || metadataFile == null) {
      return
    }

    try {
      val frameNumber = (frame.timestamp / SAMPLING_INTERVAL_NS).toInt()
      val imageFile = File(outputDir, "frame_${frameNumber.toString().padStart(6, '0')}.jpg")
      
      // Convert YUV_420_888 to NV21 and save as JPEG
      val nv21 = yuv420888ToNv21(image)
      val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
      FileOutputStream(imageFile).use { out ->
        yuvImage.compressToJpeg(
          Rect(0, 0, image.width, image.height),
          90,
          out
        )
      }

      // Save metadata
      val pose = camera.pose
      val intrinsics = camera.imageIntrinsics
      val displayRotation = activity.windowManager.defaultDisplay.rotation * 90

      val focalLength = intrinsics.getFocalLength()
      val principalPoint = intrinsics.getPrincipalPoint()
      val imageDimensions = intrinsics.getImageDimensions()

      val metadata = """
        {
          "t_ns": ${frame.timestamp},
          "pos": [${pose.tx()}, ${pose.ty()}, ${pose.tz()}],
          "quat": [${pose.qx()}, ${pose.qy()}, ${pose.qz()}, ${pose.qw()}],
          "fx": ${focalLength[0]},
          "fy": ${focalLength[1]},
          "cx": ${principalPoint[0]},
          "cy": ${principalPoint[1]},
          "w": ${imageDimensions[0]},
          "h": ${imageDimensions[1]},
          "display_rotation": $displayRotation
        }
      """.trimIndent()

      metadataFile!!.write("$metadata\n".toByteArray())
      metadataFile!!.flush()

      Log.d(TAG, "Saved frame $frameNumber")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save frame", e)
    }
  }

  private fun yuv420888ToNv21(image: Image): ByteArray {
    val width = image.width
    val height = image.height
    val ySize = width * height
    val uvSize = ySize / 4
    val nv21 = ByteArray(ySize + uvSize * 2)

    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    val yRowStride = image.planes[0].rowStride
    val uRowStride = image.planes[1].rowStride
    val vRowStride = image.planes[2].rowStride
    val uPixelStride = image.planes[1].pixelStride
    val vPixelStride = image.planes[2].pixelStride

    // Copy Y plane
    var yPos = 0
    for (i in 0 until height) {
      yBuffer.position(i * yRowStride)
      yBuffer.get(nv21, yPos, width)
      yPos += width
    }

    // Interleave U and V planes
    var uvPos = ySize
    for (i in 0 until height / 2) {
      val uPos = i * uRowStride
      val vPos = i * vRowStride
      for (j in 0 until width / 2) {
        nv21[uvPos++] = vBuffer.get(vPos + j * vPixelStride)
        nv21[uvPos++] = uBuffer.get(uPos + j * uPixelStride)
      }
    }

    return nv21
  }

  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)
}

