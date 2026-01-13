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
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Camera
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.Pose
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
import com.ar.recorder.network.SessionUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    
    private const val SAMPLING_INTERVAL_NS = 200_000_000L // 0.2 seconds (5fps)
    private const val Z_NEAR = 0.1f
    private const val Z_FAR = 100.0f
    private const val SPHERE_RADIUS = 0.01f // 1cm in meters
  }

  lateinit var render: SampleRender
  lateinit var backgroundRenderer: BackgroundRenderer
  var hasSetTextureNames = false

  // Viewport dimensions for aspect ratio matching
  private var viewportWidth = 1
  private var viewportHeight = 1
  
  // Crosshair for aiming
  private var crosshairShader: com.ar.recorder.common.samplerender.Shader? = null
  private var crosshairMesh: com.ar.recorder.common.samplerender.Mesh? = null

  // Sphere rendering
  private var sphereShader: com.ar.recorder.common.samplerender.Shader? = null
  private var sphereMesh: com.ar.recorder.common.samplerender.Mesh? = null
  private val spheres = mutableListOf<FloatArray>() // List of [x, y, z] positions
  private val projectionMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val modelMatrix = FloatArray(16)
  private val modelViewMatrix = FloatArray(16)
  private val modelViewProjectionMatrix = FloatArray(16)

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
      
      // Create crosshair shader and mesh
      createCrosshair(render)
      
      // Create sphere shader and mesh
      createSphereMesh(render)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }
  
  private fun createSphereMesh(render: SampleRender) {
    try {
      // Shader for drawing a colored sphere
      val vertexShader = """
        #version 300 es
        layout(location = 0) in vec3 a_Position;
        uniform mat4 u_ModelViewProjection;
        void main() {
          gl_Position = u_ModelViewProjection * vec4(a_Position, 1.0);
        }
      """.trimIndent()
      
      val fragmentShader = """
        #version 300 es
        precision mediump float;
        out vec4 fragColor;
        void main() {
          fragColor = vec4(0.0, 1.0, 0.0, 1.0); // Green color
        }
      """.trimIndent()
      
      sphereShader = com.ar.recorder.common.samplerender.Shader(
        render,
        vertexShader,
        fragmentShader,
        null
      ).setDepthTest(true).setDepthWrite(true)
      
      // Create a sphere mesh using spherical coordinates
      val numSegments = 32 // Number of segments for latitude and longitude
      val vertices = mutableListOf<Float>()
      val indices = mutableListOf<Int>()
      
      // Generate vertices
      for (lat in 0..numSegments) {
        val theta = lat * Math.PI.toFloat() / numSegments // 0 to PI
        val sinTheta = kotlin.math.sin(theta)
        val cosTheta = kotlin.math.cos(theta)
        
        for (lon in 0..numSegments) {
          val phi = lon * 2.0f * Math.PI.toFloat() / numSegments // 0 to 2*PI
          val sinPhi = kotlin.math.sin(phi)
          val cosPhi = kotlin.math.cos(phi)
          
          // Spherical to Cartesian coordinates
          val x = SPHERE_RADIUS * sinTheta * cosPhi
          val y = SPHERE_RADIUS * cosTheta
          val z = SPHERE_RADIUS * sinTheta * sinPhi
          
          vertices.add(x)
          vertices.add(y)
          vertices.add(z)
        }
      }
      
      // Generate indices for triangles
      for (lat in 0 until numSegments) {
        for (lon in 0 until numSegments) {
          val first = lat * (numSegments + 1) + lon
          val second = first + numSegments + 1
          
          // First triangle
          indices.add(first)
          indices.add(second)
          indices.add(first + 1)
          
          // Second triangle
          indices.add(second)
          indices.add(second + 1)
          indices.add(first + 1)
        }
      }
      
      val vertexBuffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()
      vertexBuffer.put(vertices.toFloatArray())
      vertexBuffer.position(0)
      
      val vertexBufferObj = com.ar.recorder.common.samplerender.VertexBuffer(
        render,
        3,
        vertexBuffer
      )
      
      val indexBuffer = com.ar.recorder.common.samplerender.IndexBuffer(
        render,
        java.nio.ByteBuffer.allocateDirect(indices.size * 4)
          .order(java.nio.ByteOrder.nativeOrder())
          .asIntBuffer()
          .apply { put(indices.toIntArray()); position(0) }
      )
      
      sphereMesh = com.ar.recorder.common.samplerender.Mesh(
        render,
        com.ar.recorder.common.samplerender.Mesh.PrimitiveMode.TRIANGLES,
        indexBuffer,
        arrayOf(vertexBufferObj)
      )
      
      Log.d(TAG, "Sphere mesh created with ${vertices.size / 3} vertices, ${indices.size / 3} triangles")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create sphere mesh", e)
    }
  }
  
  fun createCircleAt(x: Float, y: Float, z: Float) {
    spheres.add(floatArrayOf(x, y, z))
    Log.d(TAG, "Sphere created at ($x, $y, $z)")
  }
  
  private fun createCrosshair(render: SampleRender) {
    try {
      // Simple shader for drawing a colored square
      val vertexShader = """
        #version 300 es
        layout(location = 0) in vec2 a_Position;
        void main() {
          gl_Position = vec4(a_Position, 0.0, 1.0);
        }
      """.trimIndent()
      
      val fragmentShader = """
        #version 300 es
        precision mediump float;
        out vec4 fragColor;
        void main() {
          fragColor = vec4(1.0, 0.0, 0.0, 1.0); // Red color
        }
      """.trimIndent()
      
      crosshairShader = com.ar.recorder.common.samplerender.Shader(
        render,
        vertexShader,
        fragmentShader,
        null
      ).setDepthTest(false).setDepthWrite(false)
      
      // Create a small square at center (0, 0) in NDC coordinates
      // Size: 4 pixels in each direction (8x8 pixels total)
      // Convert to NDC: 8 pixels / viewport size
      val size = 0.01f // Small size in NDC (about 1% of screen)
      val coords = floatArrayOf(
        -size, -size,  // Bottom-left
         size, -size,  // Bottom-right
        -size,  size,  // Top-left
         size,  size   // Top-right
      )
      
      val coordBuffer = java.nio.ByteBuffer.allocateDirect(coords.size * 4)
        .order(java.nio.ByteOrder.nativeOrder())
        .asFloatBuffer()
      coordBuffer.put(coords)
      coordBuffer.position(0)
      
      val vertexBuffer = com.ar.recorder.common.samplerender.VertexBuffer(
        render,
        2,
        coordBuffer
      )
      
      // Create index buffer for triangle strip
      val indices = intArrayOf(0, 1, 2, 3)
      val indexBuffer = com.ar.recorder.common.samplerender.IndexBuffer(
        render,
        java.nio.ByteBuffer.allocateDirect(indices.size * 4)
          .order(java.nio.ByteOrder.nativeOrder())
          .asIntBuffer()
          .apply { put(indices); position(0) }
      )
      
      crosshairMesh = com.ar.recorder.common.samplerender.Mesh(
        render,
        com.ar.recorder.common.samplerender.Mesh.PrimitiveMode.TRIANGLE_STRIP,
        indexBuffer,
        arrayOf(vertexBuffer)
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create crosshair", e)
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
    
    // Draw crosshair at center
    crosshairShader?.let { shader ->
      crosshairMesh?.let { mesh ->
        render.draw(mesh, shader)
      }
    }

    // Draw spheres in 3D space
    if (camera.trackingState == TrackingState.TRACKING && spheres.isNotEmpty()) {
      // Get projection and view matrices
      camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
      camera.getViewMatrix(viewMatrix, 0)
      
      sphereShader?.let { shader ->
        sphereMesh?.let { mesh ->
          for (spherePos in spheres) {
            // Create model matrix (translation to sphere position)
            // Spheres don't need rotation since they look the same from all angles
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, spherePos[0], spherePos[1], spherePos[2])
            
            // Calculate MVP matrix
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
            
            // Set uniform and draw
            shader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            render.draw(mesh, shader)
          }
        }
      }
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

      val sessionDir = outputDir
      Log.i(TAG, "Recording stopped: ${sessionDir?.absolutePath}")
      
      activity.runOnUiThread {
        activity.view.statusText.text = "업로드 중..."
      }
      
      // 서버에 업로드
      if (sessionDir != null && sessionDir.exists()) {
        uploadSessionToServer(sessionDir)
      } else {
        activity.runOnUiThread {
          activity.view.statusText.text = activity.getString(R.string.status_saved)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to stop recording", e)
      activity.runOnUiThread {
        activity.view.statusText.text = "저장 실패: ${e.message}"
      }
    }
  }
  
  private fun uploadSessionToServer(sessionDir: File) {
    val uploader = SessionUploader()
    
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val result = uploader.uploadSessionFolder(sessionDir)
        
        result.onSuccess { response ->
          Log.i(TAG, "업로드 성공: ${response.cup_coordinates}")
          
          activity.runOnUiThread {
            if (response.cup_coordinates != null && response.cup_coordinates.size >= 3) {
              val x = response.cup_coordinates[0]
              val y = response.cup_coordinates[1]
              val z = response.cup_coordinates[2]
              
              // 컵 좌표를 AR 화면에 표시
              createCircleAt(x, y, z)
              
              activity.view.statusText.text = "처리 완료! 컵: ($x, $y, $z)"
            } else {
              activity.view.statusText.text = "처리 완료 (좌표 없음)"
            }
          }
        }.onFailure { error ->
          Log.e(TAG, "업로드 실패", error)
          
          activity.runOnUiThread {
            activity.view.statusText.text = "업로드 실패: ${error.message}"
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "업로드 중 예외 발생", e)
        activity.runOnUiThread {
          activity.view.statusText.text = "업로드 오류: ${e.message}"
        }
      }
    }
  }

  private fun sampleFrame(frame: Frame, camera: Camera) {
    val currentTime = frame.timestamp
    
    // Check if enough time has passed since last sample (2fps = 0.5s interval)
    if (currentTime - lastSampleTimestamp < SAMPLING_INTERVAL_NS) {
      return
    }

    lastSampleTimestamp = currentTime

    // 렌더링 스레드에서 frame 관련 작업 모두 수행 (frame은 다음 session.update() 전까지만 유효)
    try {
      val image = frame.acquireCameraImage()
      
      // 카메라 데이터 추출
      val pose = camera.pose
      val intrinsics = camera.imageIntrinsics
      val displayRotation = activity.windowManager.defaultDisplay.rotation * 90
      
      // 이미지 처리는 백그라운드 스레드로 이동
      Thread {
        try {
          saveFrame(image, currentTime, pose, intrinsics, displayRotation)
        } finally {
          image.close()
        }
      }.start()
    } catch (e: NotYetAvailableException) {
      // Image not available yet, skip this frame
    } catch (e: com.google.ar.core.exceptions.DeadlineExceededException) {
      Log.w(TAG, "Failed to acquire camera image: deadline exceeded")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to sample frame", e)
    }
  }

  private fun saveFrame(
    image: Image,
    timestamp: Long,
    pose: Pose,
    intrinsics: CameraIntrinsics,
    displayRotation: Int
  ) {
    if (outputDir == null || metadataFile == null) {
      return
    }

    try {
      val frameNumber = (timestamp / SAMPLING_INTERVAL_NS).toInt()
      val imageFile = File(outputDir, "frame_${frameNumber.toString().padStart(6, '0')}.jpg")
      
      // Convert YUV_420_888 to NV21 and save as JPEG (전체 이미지)
      val nv21 = yuv420888ToNv21(image)
      val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
      FileOutputStream(imageFile).use { out ->
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
      }

      // Intrinsics 이미지 크기 확인 및 검증
      val intrinsicsSize = intrinsics.getImageDimensions()
      val intrinsicsWidth = intrinsicsSize[0]
      val intrinsicsHeight = intrinsicsSize[1]
      
      val focalLength = intrinsics.getFocalLength()
      val principalPoint = intrinsics.getPrincipalPoint()
      
      // Intrinsics 크기와 실제 이미지 크기 비교
      val fx: Float
      val fy: Float
      val cx: Float
      val cy: Float
      
      if (intrinsicsWidth != image.width || intrinsicsHeight != image.height) {
        // 크기가 다르면 스케일링 필요
        val scaleX = image.width.toFloat() / intrinsicsWidth
        val scaleY = image.height.toFloat() / intrinsicsHeight
        
        fx = focalLength[0] * scaleX
        fy = focalLength[1] * scaleY
        cx = principalPoint[0] * scaleX
        cy = principalPoint[1] * scaleY
        
        Log.w(
          TAG,
          "⚠️ Intrinsics와 이미지 크기 불일치! " +
          "Intrinsics: ${intrinsicsWidth}x${intrinsicsHeight}, " +
          "Image: ${image.width}x${image.height}, " +
          "Scale: ${scaleX}x${scaleY}"
        )
      } else {
        // 크기가 같으면 그대로 사용
        fx = focalLength[0]
        fy = focalLength[1]
        cx = principalPoint[0]
        cy = principalPoint[1]
        
        Log.d(
          TAG,
          "Intrinsics size: ${intrinsicsWidth}x${intrinsicsHeight}, " +
          "Image size: ${image.width}x${image.height} (일치)"
        )
      }

      val metadata = """
        {
          "t_ns": $timestamp,
          "pos": [${pose.tx()}, ${pose.ty()}, ${pose.tz()}],
          "quat": [${pose.qx()}, ${pose.qy()}, ${pose.qz()}, ${pose.qw()}],
          "fx": $fx,
          "fy": $fy,
          "cx": $cx,
          "cy": $cy,
          "w": ${image.width},
          "h": ${image.height},
          "intrinsics_w": $intrinsicsWidth,
          "intrinsics_h": $intrinsicsHeight,
          "display_rotation": $displayRotation
        }
      """.trimIndent()

      metadataFile!!.write("$metadata\n".toByteArray())
      metadataFile!!.flush()

      Log.d(TAG, "Saved frame $frameNumber (${image.width}x${image.height})")
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

