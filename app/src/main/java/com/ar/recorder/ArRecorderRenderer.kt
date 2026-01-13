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
import com.google.ar.core.Anchor
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
import org.json.JSONObject
import org.json.JSONArray

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
  
  // Line rendering for rotation axis
  private var lineShader: com.ar.recorder.common.samplerender.Shader? = null
  private var rotationAxisBottom: FloatArray? = null // [x, y, z]
  private var rotationAxisTop: FloatArray? = null // [x, y, z]
  
  private val projectionMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val modelMatrix = FloatArray(16)
  private val modelViewMatrix = FloatArray(16)
  private val modelViewProjectionMatrix = FloatArray(16)

  // Recording state
  private val isRecording = AtomicBoolean(false)
  private val shouldStartRecording = AtomicBoolean(false)  // Flag to start recording on next frame
  private var lastSampleTimestamp: Long = 0
  private var sessionStartTime: Long = 0
  private var outputDir: File? = null
  private var metadataFile: FileOutputStream? = null
  
  // Anchor for pose correction
  private var anchor: Anchor? = null  // ARCore Anchor object
  private var anchorPose: Pose? = null  // First frame anchor pose (for JSONL)

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
      
      // Create line shader for rotation axis
      createLineShader(render)
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
  
  private fun createLineShader(render: SampleRender) {
    try {
      // Shader for drawing a line
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
          fragColor = vec4(1.0, 0.0, 0.0, 1.0); // Red color for rotation axis
        }
      """.trimIndent()
      
      lineShader = com.ar.recorder.common.samplerender.Shader(
        render,
        vertexShader,
        fragmentShader,
        null
      ).setDepthTest(true).setDepthWrite(true)
      
      Log.d(TAG, "Line shader created for rotation axis")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create line shader", e)
    }
  }
  
  fun setRotationAxis(bottomPoint: FloatArray, topPoint: FloatArray) {
    rotationAxisBottom = bottomPoint
    rotationAxisTop = topPoint
    Log.d(TAG, "Rotation axis set: bottom=(${bottomPoint[0]}, ${bottomPoint[1]}, ${bottomPoint[2]}), top=(${topPoint[0]}, ${topPoint[1]}, ${topPoint[2]})")
  }
  
  /**
   * Quaternion을 회전 행렬로 변환
   */
  private fun quaternionToRotationMatrix(qx: Float, qy: Float, qz: Float, qw: Float): FloatArray {
    val matrix = FloatArray(16)
    android.opengl.Matrix.setIdentityM(matrix, 0)
    
    // Quaternion을 회전 행렬로 변환
    val xx = qx * qx
    val yy = qy * qy
    val zz = qz * qz
    val xy = qx * qy
    val xz = qx * qz
    val yz = qy * qz
    val wx = qw * qx
    val wy = qw * qy
    val wz = qw * qz
    
    matrix[0] = 1.0f - 2.0f * (yy + zz)
    matrix[1] = 2.0f * (xy + wz)
    matrix[2] = 2.0f * (xz - wy)
    matrix[3] = 0.0f
    
    matrix[4] = 2.0f * (xy - wz)
    matrix[5] = 1.0f - 2.0f * (xx + zz)
    matrix[6] = 2.0f * (yz + wx)
    matrix[7] = 0.0f
    
    matrix[8] = 2.0f * (xz + wy)
    matrix[9] = 2.0f * (yz - wx)
    matrix[10] = 1.0f - 2.0f * (xx + yy)
    matrix[11] = 0.0f
    
    matrix[12] = 0.0f
    matrix[13] = 0.0f
    matrix[14] = 0.0f
    matrix[15] = 1.0f
    
    return matrix
  }
  
  /**
   * 월드 좌표(첫 프레임 앵커 기준 월드 W*)를 현재 앵커 기준 상대 좌표로 변환
   * 
   * GPU는 이미 프레임 간 드리프트 보정을 적용하여 "첫 프레임 앵커 기준 월드 좌표"로 결과를 반환함.
   * Android에서는 이 월드 좌표를 현재 앵커 기준으로만 변환하여 렌더링.
   * 
   * 변환: P_current_relative = R_current^T * (P_world - t_current)
   * 
   * @param pointWorld 월드 좌표 (첫 프레임 앵커 기준 월드 W*, ARCore OpenGL 좌표계)
   * @param currentAnchorPose 현재 앵커의 pose
   * @return 현재 앵커 기준 상대 좌표
   */
  private fun transformWorldToCurrentAnchor(
    pointWorld: FloatArray,
    currentAnchorPose: Pose
  ): FloatArray {
    Log.d(TAG, "Transform: world=(${pointWorld[0]}, ${pointWorld[1]}, ${pointWorld[2]})")
    Log.d(TAG, "Transform: current_anchor_pos=(${currentAnchorPose.tx()}, ${currentAnchorPose.ty()}, ${currentAnchorPose.tz()})")
    
    // 현재 앵커 회전 행렬
    val currentAnchorRotation = quaternionToRotationMatrix(
      currentAnchorPose.qx(), currentAnchorPose.qy(), currentAnchorPose.qz(), currentAnchorPose.qw()
    )
    
    // 현재 앵커의 역회전 행렬 (transpose)
    val currentAnchorRotationT = FloatArray(16)
    android.opengl.Matrix.transposeM(currentAnchorRotationT, 0, currentAnchorRotation, 0)
    
    // 월드 좌표 → 현재 앵커 기준 상대 좌표
    // point_relative = point_world - current_anchor_pos
    // point_current_relative = current_anchor_rotation.T @ point_relative
    val pointRelativeToCurrent = floatArrayOf(
      pointWorld[0] - currentAnchorPose.tx(),
      pointWorld[1] - currentAnchorPose.ty(),
      pointWorld[2] - currentAnchorPose.tz()
    )
    
    val pointCurrentRelative = FloatArray(4)
    pointCurrentRelative[0] = pointRelativeToCurrent[0]
    pointCurrentRelative[1] = pointRelativeToCurrent[1]
    pointCurrentRelative[2] = pointRelativeToCurrent[2]
    pointCurrentRelative[3] = 1.0f
    android.opengl.Matrix.multiplyMV(pointCurrentRelative, 0, currentAnchorRotationT, 0, pointCurrentRelative, 0)
    
    Log.d(TAG, "Transform: current_relative=(${pointCurrentRelative[0]}, ${pointCurrentRelative[1]}, ${pointCurrentRelative[2]})")
    
    return floatArrayOf(
      pointCurrentRelative[0],
      pointCurrentRelative[1],
      pointCurrentRelative[2]
    )
  }
  
  /**
   * 현재 프레임의 앵커 pose 가져오기
   */
  private fun getCurrentAnchorPose(): Pose? {
    val currentAnchor = anchor ?: return null
    return try {
      // ARCore Anchor의 현재 pose를 가져옴 (ARCore가 자동으로 추적)
      // Anchor가 생성되었다면 pose는 항상 유효함 (ARCore가 자동으로 업데이트)
      currentAnchor.pose
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get current anchor pose", e)
      null
    }
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
    
    // Debug: Log camera position every 60 frames (about 1 second at 60fps)
    if (frame.timestamp % 1_000_000_000L < 16_666_666L) {  // Every ~1 second
      val cameraPose = camera.pose
      Log.d(TAG, "Camera position: (${cameraPose.tx()}, ${cameraPose.ty()}, ${cameraPose.tz()})")
    }

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
            // Debug: Log sphere position (only first sphere, every 60 frames)
            if (spheres.indexOf(spherePos) == 0 && frame.timestamp % 1_000_000_000L < 16_666_666L) {
              Log.d(TAG, "Rendering sphere at: (${spherePos[0]}, ${spherePos[1]}, ${spherePos[2]})")
            }
            
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
    
    // Draw rotation axis line
    if (camera.trackingState == TrackingState.TRACKING && 
        rotationAxisBottom != null && rotationAxisTop != null) {
      camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
      camera.getViewMatrix(viewMatrix, 0)
      
      lineShader?.let { shader ->
        // Create line mesh dynamically (like spheres)
        val vertices = floatArrayOf(
          rotationAxisBottom!![0], rotationAxisBottom!![1], rotationAxisBottom!![2],
          rotationAxisTop!![0], rotationAxisTop!![1], rotationAxisTop!![2]
        )
        
        val vertexBuffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
          .order(java.nio.ByteOrder.nativeOrder())
          .asFloatBuffer()
        vertexBuffer.put(vertices)
        vertexBuffer.position(0)
        
        val vertexBufferObj = com.ar.recorder.common.samplerender.VertexBuffer(
          render,
          3,
          vertexBuffer
        )
        
        val indices = intArrayOf(0, 1)
        val indexBuffer = com.ar.recorder.common.samplerender.IndexBuffer(
          render,
          java.nio.ByteBuffer.allocateDirect(indices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asIntBuffer()
            .apply { put(indices); position(0) }
        )
        
        val lineMesh = com.ar.recorder.common.samplerender.Mesh(
          render,
          com.ar.recorder.common.samplerender.Mesh.PrimitiveMode.LINES,
          indexBuffer,
          arrayOf(vertexBufferObj)
        )
        
        // Draw line
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
        
        shader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
        render.draw(lineMesh, shader)
      }
    }

    // Initialize recording if requested (on OpenGL thread)
    if (shouldStartRecording.get() && camera.trackingState == TrackingState.TRACKING) {
      initializeRecording(frame, camera)
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

    // Set flag to start recording on next frame (onDrawFrame will handle it)
    // This ensures session.update() is called on the OpenGL thread
    shouldStartRecording.set(true)
    Log.i(TAG, "Recording start requested, will start on next frame")
  }
  
  private fun initializeRecording(frame: Frame, camera: Camera) {
    try {
      val session = session ?: return
      
      if (camera.trackingState != TrackingState.TRACKING) {
        showError("Camera not tracking. Please wait...")
        shouldStartRecording.set(false)
        return
      }
      
      // Create actual ARCore Anchor from first frame camera pose
      anchor = session.createAnchor(camera.pose)
      anchorPose = camera.pose  // Store for JSONL
      Log.i(TAG, "Anchor created: pos=(${anchorPose!!.tx()}, ${anchorPose!!.ty()}, ${anchorPose!!.tz()})")
      
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
      shouldStartRecording.set(false)

      Log.i(TAG, "Recording started: ${outputDir!!.absolutePath}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start recording", e)
      showError("Failed to start recording: $e")
      isRecording.set(false)
      shouldStartRecording.set(false)
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
      
      // Don't clean up anchor yet - it's needed for rendering after server upload
      // Anchor will be cleaned up after server upload completes

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
        // 첫 프레임 앵커 정보 읽기
        val jsonlFiles = sessionDir.listFiles { _, name -> name.endsWith(".jsonl") }
        var firstAnchorPos: FloatArray? = null
        var firstAnchorQuat: FloatArray? = null
        
        if (jsonlFiles != null && jsonlFiles.isNotEmpty()) {
          try {
            val jsonlFile = jsonlFiles[0]
            val content = jsonlFile.readText()
            
            // JSONL 파일에서 첫 번째 JSON 객체 찾기 (여러 줄에 걸칠 수 있음)
            var braceCount = 0
            var jsonString = ""
            for (char in content) {
              jsonString += char
              if (char == '{') {
                braceCount++
              } else if (char == '}') {
                braceCount--
                if (braceCount == 0) {
                  // 첫 번째 JSON 객체 완성
                  try {
                    val json = JSONObject(jsonString.trim())
                    if (json.has("anchor_pos") && json.has("anchor_quat")) {
                      val anchorPosArray = json.getJSONArray("anchor_pos")
                      val anchorQuatArray = json.getJSONArray("anchor_quat")
                      firstAnchorPos = floatArrayOf(
                        anchorPosArray.getDouble(0).toFloat(),
                        anchorPosArray.getDouble(1).toFloat(),
                        anchorPosArray.getDouble(2).toFloat()
                      )
                      firstAnchorQuat = floatArrayOf(
                        anchorQuatArray.getDouble(0).toFloat(),
                        anchorQuatArray.getDouble(1).toFloat(),
                        anchorQuatArray.getDouble(2).toFloat(),
                        anchorQuatArray.getDouble(3).toFloat()
                      )
                      Log.i(TAG, "First anchor loaded: pos=(${firstAnchorPos[0]}, ${firstAnchorPos[1]}, ${firstAnchorPos[2]})")
                      break
                    }
                  } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse first JSON object: ${e.message}")
                  }
                }
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Failed to read anchor from JSONL: ${e.message}")
          }
        }
        
        val result = uploader.uploadSessionFolder(sessionDir)
        
        result.onSuccess { response ->
          Log.i(TAG, "업로드 성공: ${response.cup_coordinates}, rotation_axis: ${response.rotation_axis}")
          
          activity.runOnUiThread {
            // 현재 프레임의 앵커 pose 가져오기
            val currentAnchorPose = getCurrentAnchorPose()
            
            // GPU 결과는 월드 좌표(ARCore OpenGL 좌표계)로 반환됨
            // 기존 로직과 동일하게 월드 좌표를 직접 사용하여 렌더링
            // GPU에서 프레임 간 드리프트 보정이 이미 적용되어 있음
            
            // 컵 좌표 표시
            if (response.cup_coordinates != null && response.cup_coordinates.size >= 3) {
              val cupWorld = floatArrayOf(
                response.cup_coordinates[0],
                response.cup_coordinates[1],
                response.cup_coordinates[2]
              )
              Log.i(TAG, "Cup world coordinates: (${cupWorld[0]}, ${cupWorld[1]}, ${cupWorld[2]})")
              createCircleAt(cupWorld[0], cupWorld[1], cupWorld[2])
            }
            
            // 회전축 선 표시
            if (response.rotation_axis != null) {
              val bottomPoint = response.rotation_axis.bottom_point
              val topPoint = response.rotation_axis.top_point
              
              if (bottomPoint.size >= 3 && topPoint.size >= 3) {
                val bottomWorld = floatArrayOf(
                  bottomPoint[0],
                  bottomPoint[1],
                  bottomPoint[2]
                )
                val topWorld = floatArrayOf(
                  topPoint[0],
                  topPoint[1],
                  topPoint[2]
                )
                
                Log.i(TAG, "Rotation axis world coordinates: bottom=(${bottomWorld[0]}, ${bottomWorld[1]}, ${bottomWorld[2]}), top=(${topWorld[0]}, ${topWorld[1]}, ${topWorld[2]})")
                
                // 월드 좌표를 직접 사용하여 렌더링 (기존 로직과 동일)
                setRotationAxis(bottomWorld, topWorld)
                
                // 회전축의 두 끝점에도 구 표시
                createCircleAt(bottomWorld[0], bottomWorld[1], bottomWorld[2])
                createCircleAt(topWorld[0], topWorld[1], topWorld[2])
                
                activity.view.statusText.text = "처리 완료! 회전축 표시됨 (프레임 간 드리프트 보정 적용)"
                Log.i(TAG, "Rotation axis set: bottom=(${bottomWorld[0]}, ${bottomWorld[1]}, ${bottomWorld[2]}), top=(${topWorld[0]}, ${topWorld[1]}, ${topWorld[2]})")
              } else {
                activity.view.statusText.text = "처리 완료 (회전축 좌표 오류)"
              }
            } else if (response.cup_coordinates != null && response.cup_coordinates.size >= 3) {
              activity.view.statusText.text = "처리 완료! 컵 표시됨"
            } else {
              activity.view.statusText.text = "처리 완료 (좌표 없음)"
            }
            
            // Clean up anchor after rendering is set up
            // Anchor is no longer needed once coordinates are transformed and displayed
            anchor?.detach()
            anchor = null
            anchorPose = null
            Log.i(TAG, "Anchor cleaned up after server upload")
          }
        }.onFailure { error ->
          Log.e(TAG, "업로드 실패", error)
          
          activity.runOnUiThread {
            activity.view.statusText.text = "업로드 실패: ${error.message}"
            // Clean up anchor even on failure
            anchor?.detach()
            anchor = null
            anchorPose = null
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

      // Current anchor pose (for each frame, to track drift)
      val currentAnchorPose = anchor?.pose
      val anchorPos = if (currentAnchorPose != null) {
        "[${currentAnchorPose.tx()}, ${currentAnchorPose.ty()}, ${currentAnchorPose.tz()}]"
      } else if (anchorPose != null) {
        // Fallback to first frame anchor if current anchor not available
        "[${anchorPose!!.tx()}, ${anchorPose!!.ty()}, ${anchorPose!!.tz()}]"
      } else {
        "[0.0, 0.0, 0.0]"
      }
      val anchorQuat = if (currentAnchorPose != null) {
        "[${currentAnchorPose.qx()}, ${currentAnchorPose.qy()}, ${currentAnchorPose.qz()}, ${currentAnchorPose.qw()}]"
      } else if (anchorPose != null) {
        // Fallback to first frame anchor if current anchor not available
        "[${anchorPose!!.qx()}, ${anchorPose!!.qy()}, ${anchorPose!!.qz()}, ${anchorPose!!.qw()}]"
      } else {
        "[0.0, 0.0, 0.0, 1.0]"
      }
      
      val metadata = """
        {
          "t_ns": $timestamp,
          "pos": [${pose.tx()}, ${pose.ty()}, ${pose.tz()}],
          "quat": [${pose.qx()}, ${pose.qy()}, ${pose.qz()}, ${pose.qw()}],
          "anchor_pos": $anchorPos,
          "anchor_quat": $anchorQuat,
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

