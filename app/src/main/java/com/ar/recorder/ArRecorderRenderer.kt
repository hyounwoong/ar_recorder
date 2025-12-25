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
import android.view.Surface
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
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

  // Viewport dimensions for aspect ratio matching
  private var viewportWidth = 1
  private var viewportHeight = 1

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
    viewportWidth = width
    viewportHeight = height
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

    // acquireCameraImage()는 렌더링 스레드(OpenGL 스레드)에서만 호출 가능
    // DeadlineExceededException 방지를 위해 즉시 호출
    try {
      val image = frame.acquireCameraImage()
      
      // 이미지 처리는 백그라운드 스레드로 이동
      Thread {
        try {
          saveFrame(image, frame, camera)
        } finally {
          // 이미지는 백그라운드 스레드에서 close 가능
          image.close()
        }
      }.start()
    } catch (e: NotYetAvailableException) {
      // Image not available yet, skip this frame
    } catch (e: com.google.ar.core.exceptions.DeadlineExceededException) {
      // 이미지 획득 실패 (너무 늦게 호출됨) - 무시하고 다음 프레임 시도
      Log.w(TAG, "Failed to acquire camera image: deadline exceeded")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to sample frame", e)
    }
  }

  private fun saveFrame(image: Image, frame: Frame, camera: Camera) {
    if (outputDir == null || metadataFile == null) {
      return
    }

    try {
      val frameNumber = (frame.timestamp / SAMPLING_INTERVAL_NS).toInt()
      val imageFile = File(outputDir, "frame_${frameNumber.toString().padStart(6, '0')}.jpg")
      
      // 화면의 4개 모서리를 NDC 좌표로 정의 (-1~1 범위)
      // BackgroundRenderer가 사용하는 것과 동일한 방식
      val screenCorners = floatArrayOf(
        -1f, -1f,  // bottom-left
         1f, -1f,  // bottom-right
        -1f,  1f,  // top-left
         1f,  1f   // top-right
      )
      
      // 두 단계 변환: NDC -> TEXTURE_NORMALIZED -> IMAGE_PIXELS
      // BackgroundRenderer와 동일한 방식 사용
      val textureCoords = FloatArray(8)
      frame.transformCoordinates2d(
        Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
        screenCorners,
        Coordinates2d.TEXTURE_NORMALIZED,
        textureCoords
      )
      
      val imageCorners = FloatArray(8)
      frame.transformCoordinates2d(
        Coordinates2d.TEXTURE_NORMALIZED,
        textureCoords,
        Coordinates2d.IMAGE_PIXELS,
        imageCorners
      )
      
      // 변환된 좌표에서 bounding box 계산
      val minX = minOf(imageCorners[0], imageCorners[2], imageCorners[4], imageCorners[6])
      val maxX = maxOf(imageCorners[0], imageCorners[2], imageCorners[4], imageCorners[6])
      val minY = minOf(imageCorners[1], imageCorners[3], imageCorners[5], imageCorners[7])
      val maxY = maxOf(imageCorners[1], imageCorners[3], imageCorners[5], imageCorners[7])
      
      // 디버깅 로그
      Log.d(TAG, "Frame $frameNumber - Image corners: " +
        "(${imageCorners[0]}, ${imageCorners[1]}), " +
        "(${imageCorners[2]}, ${imageCorners[3]}), " +
        "(${imageCorners[4]}, ${imageCorners[5]}), " +
        "(${imageCorners[6]}, ${imageCorners[7]})")
      Log.d(TAG, "Bounding box: minX=$minX, maxX=$maxX, minY=$minY, maxY=$maxY")
      Log.d(TAG, "Image size: ${image.width}x${image.height}")
      
      // 이미지 경계 내로 클리핑
      val cropX = maxOf(0, minX.toInt())
      val cropY = maxOf(0, minY.toInt())
      val cropWidth = minOf(image.width, maxX.toInt()) - cropX
      val cropHeight = minOf(image.height, maxY.toInt()) - cropY
      
      val cropRect = Rect(cropX, cropY, cropX + cropWidth, cropY + cropHeight)
      
      Log.d(TAG, "Crop rect: $cropRect (${cropWidth}x${cropHeight})")
      
      // Convert YUV_420_888 to NV21 and save as JPEG (크롭된 영역만)
      val nv21 = yuv420888ToNv21(image)
      val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
      FileOutputStream(imageFile).use { out ->
        yuvImage.compressToJpeg(cropRect, 90, out)
      }

      // Save metadata (크롭된 영역 기준으로 intrinsics 조정)
      val pose = camera.pose
      val intrinsics = camera.imageIntrinsics
      val focalLength = intrinsics.getFocalLength()
      val principalPoint = intrinsics.getPrincipalPoint()
      val imageDimensions = intrinsics.getImageDimensions()
      
      // 크롭된 영역에 맞게 intrinsics 조정
      val cropOffsetX = cropRect.left.toFloat()
      val cropOffsetY = cropRect.top.toFloat()
      val scaleX = cropWidth.toFloat() / imageDimensions[0]
      val scaleY = cropHeight.toFloat() / imageDimensions[1]
      
      val adjustedFx = focalLength[0] * scaleX
      val adjustedFy = focalLength[1] * scaleY
      val adjustedCx = (principalPoint[0] - cropOffsetX) * scaleX
      val adjustedCy = (principalPoint[1] - cropOffsetY) * scaleY

      val displayRotation = activity.windowManager.defaultDisplay.rotation * 90

      val metadata = """
        {
          "t_ns": ${frame.timestamp},
          "pos": [${pose.tx()}, ${pose.ty()}, ${pose.tz()}],
          "quat": [${pose.qx()}, ${pose.qy()}, ${pose.qz()}, ${pose.qw()}],
          "fx": $adjustedFx,
          "fy": $adjustedFy,
          "cx": $adjustedCx,
          "cy": $adjustedCy,
          "w": $cropWidth,
          "h": $cropHeight,
          "display_rotation": $displayRotation
        }
      """.trimIndent()

      metadataFile!!.write("$metadata\n".toByteArray())
      metadataFile!!.flush()

      Log.d(TAG, "Saved frame $frameNumber (cropped: ${cropWidth}x${cropHeight} from ${image.width}x${image.height}, screen: ${viewportWidth}x${viewportHeight})")
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

