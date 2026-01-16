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
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.Trackable
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
  lateinit var planeRenderer: com.ar.recorder.common.samplerender.arcore.PlaneRenderer
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
  
  // Anchor visualization (blue spheres)
  private var anchorSphereShader: com.ar.recorder.common.samplerender.Shader? = null
  private var anchorSphereMesh: com.ar.recorder.common.samplerender.Mesh? = null
  
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
  
  // First frame anchor info for coordinate transformation
  private var firstAnchorPos: FloatArray? = null  // First frame anchor position
  private var firstAnchorQuat: FloatArray? = null  // First frame anchor quaternion
  
  // New anchor for rotation axis (to be created in GL thread)
  private var newAnchorCenter: FloatArray? = null  // Center point for new anchor
  
  // Anchor visualization
  private val anchorSpheres = mutableListOf<FloatArray>()  // List of anchor positions for visualization
  
  // Tracking stability detection
  private var stableStartTime: Long? = null
  private var previousPose: Pose? = null
  private var previousTimestamp: Long = 0
  private var isTrackingStable = false
  
  private val REQUIRED_STABLE_DURATION_NS = 2_000_000_000L // 2 seconds in nanoseconds
  private val TRANSLATION_THRESHOLD = 0.05f // 5cm
  private val ROTATION_THRESHOLD = 2.0f // 2 degrees

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
      
      // Create plane renderer for plane visualization
      planeRenderer = com.ar.recorder.common.samplerender.arcore.PlaneRenderer(render)
      
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
  
  private fun createAnchorSphereShader(render: SampleRender) {
    try {
      // Reuse sphere mesh, just create blue shader for anchors
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
          fragColor = vec4(0.0, 0.0, 1.0, 1.0); // Blue color for anchors
        }
      """.trimIndent()
      
      anchorSphereShader = com.ar.recorder.common.samplerender.Shader(
        render,
        vertexShader,
        fragmentShader,
        null
      ).setDepthTest(true).setDepthWrite(true)
      
      // Reuse the same sphere mesh
      anchorSphereMesh = sphereMesh
      
      Log.d(TAG, "Anchor sphere shader created")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create anchor sphere shader", e)
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
   * 앵커 pose로부터 4x4 변환 행렬 생성 (회전 + 이동)
   * GPU의 A1, Ai 행렬과 동일한 형식
   * 
   * 올바른 형식: T = [[R  t],
   *                   [0  1]]
   * 여기서 R은 회전 행렬, t는 이동 벡터
   */
  private fun buildAnchorTransformMatrix(pos: FloatArray, quat: FloatArray): FloatArray {
    val rotation = quaternionToRotationMatrix(quat[0], quat[1], quat[2], quat[3])
    val matrix = FloatArray(16)
    
    // 회전 행렬 복사 (상단 3x3)
    for (i in 0..2) {
      for (j in 0..2) {
        matrix[i * 4 + j] = rotation[i * 4 + j]
      }
    }
    
    // 이동 벡터 설정 (12, 13, 14번 인덱스)
    matrix[12] = pos[0]
    matrix[13] = pos[1]
    matrix[14] = pos[2]
    
    // 하단 행 설정
    matrix[3] = 0.0f   // (0,3)
    matrix[7] = 0.0f   // (1,3)
    matrix[11] = 0.0f  // (2,3)
    matrix[15] = 1.0f  // (3,3)
    
    return matrix
  }
  
  // Pose로부터 변환 행렬 생성 (오버로드)
  private fun buildAnchorTransformMatrix(pose: Pose): FloatArray {
    return buildAnchorTransformMatrix(
      floatArrayOf(pose.tx(), pose.ty(), pose.tz()),
      floatArrayOf(pose.qx(), pose.qy(), pose.qz(), pose.qw())
    )
  }
  
  // Helper: 행렬 곱셈
  private fun multiplyMatrices(a: FloatArray, b: FloatArray): FloatArray {
    val result = FloatArray(16)
    android.opengl.Matrix.multiplyMM(result, 0, a, 0, b, 0)
    return result
  }
  
  // Helper: 행렬 역행렬
  private fun invertMatrix(matrix: FloatArray): FloatArray {
    val result = FloatArray(16)
    android.opengl.Matrix.invertM(result, 0, matrix, 0)
    return result
  }
  
  // 월드 좌표 → 앵커 기준 상대 좌표
  private fun transformWorldToAnchorRelative(pointWorld: FloatArray, anchorPose: Pose): FloatArray {
    val rotation = quaternionToRotationMatrix(anchorPose.qx(), anchorPose.qy(), anchorPose.qz(), anchorPose.qw())
    val rotationT = FloatArray(16).apply { android.opengl.Matrix.transposeM(this, 0, rotation, 0) }
    
    val relative = floatArrayOf(
      pointWorld[0] - anchorPose.tx(),
      pointWorld[1] - anchorPose.ty(),
      pointWorld[2] - anchorPose.tz()
    )
    
    val result = FloatArray(4).apply {
      relative.copyInto(this, 0, 0, 3)
      this[3] = 1.0f
    }
    android.opengl.Matrix.multiplyMV(result, 0, rotationT, 0, result, 0)
    
    return floatArrayOf(result[0], result[1], result[2])
  }
  
  // 앵커 기준 상대 좌표 → 월드 좌표
  private fun transformAnchorRelativeToWorld(pointAnchorRelative: FloatArray, anchorPose: Pose): FloatArray {
    val rotation = quaternionToRotationMatrix(anchorPose.qx(), anchorPose.qy(), anchorPose.qz(), anchorPose.qw())
    
    val relative = FloatArray(4).apply {
      pointAnchorRelative.copyInto(this, 0, 0, 3)
      this[3] = 1.0f
    }
    android.opengl.Matrix.multiplyMV(relative, 0, rotation, 0, relative, 0)
    
    return floatArrayOf(
      relative[0] + anchorPose.tx(),
      relative[1] + anchorPose.ty(),
      relative[2] + anchorPose.tz()
    )
  }
  
  // GPU 결과(w0 좌표계)를 현재 ARCore 월드 좌표로 변환
  // S(t)^-1 = T_aw(t) · (T_aw(0))^-1
  private fun transformW0ToCurrentWorld(
    pointW0: FloatArray,
    firstAnchorPos: FloatArray,
    firstAnchorQuat: FloatArray,
    currentAnchorPose: Pose
  ): FloatArray {
    val A0 = buildAnchorTransformMatrix(firstAnchorPos, firstAnchorQuat)
    val A_t = buildAnchorTransformMatrix(currentAnchorPose)
    val S_inv = multiplyMatrices(A_t, invertMatrix(A0))
    
    val pointW0Homogeneous = FloatArray(4).apply {
      pointW0.copyInto(this, 0, 0, 3)
      this[3] = 1.0f
    }
    
    val result = FloatArray(4)
    android.opengl.Matrix.multiplyMV(result, 0, S_inv, 0, pointW0Homogeneous, 0)
    
    return floatArrayOf(result[0], result[1], result[2])
  }
  
  /**
   * 현재 프레임의 앵커 pose 가져오기
   */
  private fun getCurrentAnchorPose(): Pose? {
    val currentAnchor = anchor ?: return null
    return try {
      // ARCore Anchor의 현재 pose를 가져옴 (ARCore가 자동으로 추적)
      // TrackingState 확인 (Anchor의 trackingState는 com.google.ar.core.TrackingState를 반환)
      if (currentAnchor.trackingState == TrackingState.TRACKING) {
        currentAnchor.pose
      } else {
        // 추적되지 않으면 null 반환
        Log.w(TAG, "Anchor not tracking: ${currentAnchor.trackingState}")
        null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get current anchor pose", e)
      null
    }
  }
  
  /**
   * Quaternion 간 각도 차이 계산 (도 단위)
   */
  private fun calculateQuaternionAngle(q1: FloatArray, q2: FloatArray): Float {
    // Quaternion 내적
    val dot = q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3]
    // 각도 계산 (라디안 → 도)
    val angleRad = 2.0 * kotlin.math.acos(kotlin.math.abs(dot).toDouble())
    return Math.toDegrees(angleRad).toFloat()
  }
  
  /**
   * Tracking 안정화 감지
   * TrackingState + 시간 체크 (드리프트 체크 제거 - 핸드폰 움직임 허용)
   */
  private fun checkTrackingStability(camera: Camera, frame: Frame): Boolean {
    // 1. TrackingState 체크
    if (camera.trackingState != TrackingState.TRACKING) {
      stableStartTime = null
      isTrackingStable = false
      return false
    }
    
    val currentTimestamp = frame.timestamp
    
    // 2. 시간 체크 (지속성)
    // TrackingState가 TRACKING이고 일정 시간 지속되면 안정화된 것으로 판단
    // 드리프트 체크는 제거 - 핸드폰을 움직여도 안정화 진행
    if (stableStartTime == null) {
      stableStartTime = currentTimestamp
    }
    
    val stableDuration = currentTimestamp - stableStartTime!!
    
    if (stableDuration >= REQUIRED_STABLE_DURATION_NS) {
      // 안정화 완료!
      isTrackingStable = true
      return true
    }
    
    isTrackingStable = false
    return false
  }
  
  /**
   * 앵커 좌표를 UI에 표시
   */
  // 앵커 좌표 UI 표시
  private fun updateAnchorCoordinatesDisplay() {
    val anchor = anchor ?: return
    val pose = if (anchor.trackingState == TrackingState.TRACKING) anchor.pose else anchorPose
    
    pose?.let {
      val status = when (anchor.trackingState) {
        TrackingState.TRACKING -> "추적중"
        TrackingState.PAUSED -> "일시정지"
        TrackingState.STOPPED -> "중단"
        else -> "알수없음"
      }
      activity.runOnUiThread {
        activity.view.statusText.text = String.format(
          "앵커: (%.3f, %.3f, %.3f) [%s]", it.tx(), it.ty(), it.tz(), status
        )
      }
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
    
    // Tracking 안정화 감지 (매 프레임 체크)
    val isStable = checkTrackingStability(camera, frame)
    
    // 안정화 상태에 따라 Start 버튼 활성화/비활성화
    if (!isRecording.get() && !shouldStartRecording.get()) {
      activity.runOnUiThread {
        if (isStable) {
          // 안정화 완료: Start 버튼 활성화
          activity.view.startButton.isEnabled = true
          activity.view.statusText.text = "준비 완료! Start 버튼을 누르세요"
        } else {
          // 안정화 중: Start 버튼 비활성화
          activity.view.startButton.isEnabled = false
          val stableDuration = if (stableStartTime != null) {
            (frame.timestamp - stableStartTime!!) / 1_000_000_000.0
          } else {
            0.0
          }
          activity.view.statusText.text = String.format(
            "환경 스캔 중... (%.1f초 / 2.0초)", stableDuration
          )
        }
      }
    }
    
    // Update anchor coordinates display
    updateAnchorCoordinatesDisplay()
    
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
    
    // Visualize plane at screen center (화면 가운데 픽셀이 가리키는 평면만 시각화)
    if (camera.trackingState == TrackingState.TRACKING) {
      camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
      try {
        // 화면 중앙 좌표
        val centerX = viewportWidth / 2.0f
        val centerY = viewportHeight / 2.0f
        
        // 화면 중앙으로 hit test
        val hitResults = frame.hitTest(centerX, centerY)
        
        // 평면 찾기
        var centerPlane: Plane? = null
        for (hit in hitResults) {
          val trackable = hit.trackable
          if (trackable is Plane) {
            val plane = trackable
            val hitPose = hit.hitPose
            if (plane.trackingState == TrackingState.TRACKING &&
                plane.isPoseInPolygon(hitPose)) {
              centerPlane = plane
              break
            }
          }
        }
        
        // 찾은 평면만 시각화
        if (centerPlane != null) {
          planeRenderer.drawPlanes(
            render,
            listOf(centerPlane),
            camera.displayOrientedPose,
            projectionMatrix
          )
        }
      } catch (e: Exception) {
        // PlaneRenderer 초기화 실패 시 무시 (텍스처 파일 없을 수 있음)
        Log.w(TAG, "Failed to draw planes: ${e.message}")
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
            // GPU 좌표를 그대로 사용 (앵커 기준 변환 제거)
            val spherePoint = spherePos
            
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, spherePoint[0], spherePoint[1], spherePoint[2])
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
            
            shader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            render.draw(mesh, shader)
          }
        }
      }
    }
    
    // Draw anchor visualization (blue spheres)
    if (camera.trackingState == TrackingState.TRACKING && anchorSpheres.isNotEmpty()) {
      camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
      camera.getViewMatrix(viewMatrix, 0)
      
      // Create blue shader for anchors (if not exists)
      if (anchorSphereShader == null) {
        createAnchorSphereShader(render)
      }
      
      anchorSphereShader?.let { shader ->
        anchorSphereMesh?.let { mesh ->
          // 앵커 위치를 매 프레임 현재 pose로 업데이트 (드리프트 보정)
          if (anchor != null && anchor!!.trackingState == TrackingState.TRACKING) {
            val currentAnchorPose = anchor!!.pose
            if (anchorSpheres.isNotEmpty()) {
              // 현재 앵커 pose로 업데이트
              anchorSpheres[0][0] = currentAnchorPose.tx()
              anchorSpheres[0][1] = currentAnchorPose.ty()
              anchorSpheres[0][2] = currentAnchorPose.tz()
            }
          }
          
          for (anchorPos in anchorSpheres) {
            // Anchor positions are in world coordinates (updated each frame)
            val anchorPoint = anchorPos
            
            // Create model matrix
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, anchorPoint[0], anchorPoint[1], anchorPoint[2])
            
            // Scale anchor sphere to 1mm radius (SPHERE_RADIUS is 1cm, so scale by 0.1)
            Matrix.scaleM(modelMatrix, 0, 0.1f, 0.1f, 0.1f)
            
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
    if (camera.trackingState == TrackingState.TRACKING && rotationAxisBottom != null && rotationAxisTop != null) {
      // GPU 좌표는 첫 프레임 앵커 기준 월드 좌표(W0)이므로, 현재 앵커 기준으로 변환
      var bottomPoint = rotationAxisBottom!!
      var topPoint = rotationAxisTop!!
      
      // 현재 앵커 pose로 보정 적용
      val currentAnchorPose = getCurrentAnchorPose()
      if (currentAnchorPose != null && firstAnchorPos != null && firstAnchorQuat != null) {
        bottomPoint = transformW0ToCurrentWorld(bottomPoint, firstAnchorPos!!, firstAnchorQuat!!, currentAnchorPose)
        topPoint = transformW0ToCurrentWorld(topPoint, firstAnchorPos!!, firstAnchorQuat!!, currentAnchorPose)
      }
      
      camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
      camera.getViewMatrix(viewMatrix, 0)
      
      lineShader?.let { shader ->
        val vertices = floatArrayOf(
          bottomPoint[0], bottomPoint[1], bottomPoint[2],
          topPoint[0], topPoint[1], topPoint[2]
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
      
      // Draw spheres at rotation axis endpoints (0.5cm radius)
      sphereShader?.let { shader ->
        sphereMesh?.let { mesh ->
          // Draw sphere at bottom point
          Matrix.setIdentityM(modelMatrix, 0)
          Matrix.translateM(modelMatrix, 0, bottomPoint[0], bottomPoint[1], bottomPoint[2])
          // Scale to 0.5cm radius (SPHERE_RADIUS is 1cm, so scale by 0.5)
          Matrix.scaleM(modelMatrix, 0, 0.5f, 0.5f, 0.5f)
          Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
          Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
          shader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
          render.draw(mesh, shader)
          
          // Draw sphere at top point
          Matrix.setIdentityM(modelMatrix, 0)
          Matrix.translateM(modelMatrix, 0, topPoint[0], topPoint[1], topPoint[2])
          // Scale to 0.5cm radius
          Matrix.scaleM(modelMatrix, 0, 0.5f, 0.5f, 0.5f)
          Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
          Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
          shader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
          render.draw(mesh, shader)
        }
      }
    }

    // Initialize recording if requested (on OpenGL thread)
    // 앵커는 즉시 생성, 안정화 감지는 참고용으로만 사용
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
    
    // 안정화 확인 (이미 안정화된 상태에서만 호출되어야 함)
    if (!isTrackingStable) {
      Log.w(TAG, "Tracking not stable yet, cannot start recording")
      activity.runOnUiThread {
        activity.view.statusText.text = "아직 안정화되지 않았습니다. 잠시 기다려주세요..."
      }
      return
    }

    // Set flag to start recording on next frame (onDrawFrame will handle it)
    // This ensures session.update() is called on the OpenGL thread
    shouldStartRecording.set(true)
    Log.i(TAG, "Recording start requested, will create anchor immediately on next frame")
    
    activity.runOnUiThread {
      activity.view.statusText.text = "앵커 생성 중..."
    }
  }
  
  /**
   * Calculate distance from camera to plane
   */
  private fun calculateDistanceToPlane(hitPose: Pose, cameraPose: Pose): Float {
    val dx = hitPose.tx() - cameraPose.tx()
    val dy = hitPose.ty() - cameraPose.ty()
    val dz = hitPose.tz() - cameraPose.tz()
    return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
  }
  
  /**
   * 카메라 pose에서 forward 방향 벡터 계산 (ARCore OpenGL 좌표계: -Z 방향)
   */
  private fun getCameraForwardVector(cameraPose: Pose): FloatArray {
    // 카메라의 forward 방향은 -Z 축 (OpenGL 좌표계)
    val forwardLocal = floatArrayOf(0.0f, 0.0f, -1.0f, 1.0f)
    
    // Quaternion을 회전 행렬로 변환
    val rotationMatrix = quaternionToRotationMatrix(
      cameraPose.qx(), cameraPose.qy(), cameraPose.qz(), cameraPose.qw()
    )
    
    // 회전 행렬을 적용하여 월드 좌표계의 forward 벡터 계산
    val forwardWorld = FloatArray(4)
    android.opengl.Matrix.multiplyMV(forwardWorld, 0, rotationMatrix, 0, forwardLocal, 0)
    
    // 정규화 (방향만 필요하므로)
    val length = Math.sqrt(
      (forwardWorld[0] * forwardWorld[0] + 
       forwardWorld[1] * forwardWorld[1] + 
       forwardWorld[2] * forwardWorld[2]).toDouble()
    ).toFloat()
    
    return floatArrayOf(
      forwardWorld[0] / length,
      forwardWorld[1] / length,
      forwardWorld[2] / length
    )
  }
  
  /**
   * 카메라가 바라보는 방향으로 지정된 거리만큼 앞의 위치 계산
   */
  private fun calculatePointInFrontOfCamera(cameraPose: Pose, distanceMeters: Float): FloatArray {
    val forward = getCameraForwardVector(cameraPose)
    
    return floatArrayOf(
      cameraPose.tx() + forward[0] * distanceMeters,
      cameraPose.ty() + forward[1] * distanceMeters,
      cameraPose.tz() + forward[2] * distanceMeters
    )
  }
  
  private fun initializeRecording(frame: Frame, camera: Camera) {
    try {
      val session = session ?: return
      
      if (camera.trackingState != TrackingState.TRACKING) {
        showError("Camera not tracking. Please wait...")
        shouldStartRecording.set(false)
        return
      }
      
      // 안정화 상태 로그 (참고용)
      if (isTrackingStable) {
        Log.i(TAG, "Tracking stable, creating anchor...")
      } else {
        val stableDuration = if (stableStartTime != null) {
          (frame.timestamp - stableStartTime!!) / 1_000_000_000.0
        } else {
          0.0
        }
        Log.i(TAG, "Creating anchor immediately (stability: ${stableDuration}s)")
      }
      
      // 카메라가 바라보는 방향으로 30cm 앞에 앵커 생성
      val cameraPose = camera.pose
      val anchorPosition = calculatePointInFrontOfCamera(cameraPose, 0.3f) // 30cm = 0.3m
      
      // 앵커 위치의 Pose 생성 (카메라와 같은 방향 사용)
      // Pose 생성자는 translation(3개)과 quaternion(4개) 두 개의 FloatArray를 받습니다
      val anchorTranslation = floatArrayOf(
        anchorPosition[0],
        anchorPosition[1],
        anchorPosition[2]
      )
      val anchorQuaternion = floatArrayOf(
        cameraPose.qx(),
        cameraPose.qy(),
        cameraPose.qz(),
        cameraPose.qw()
      )
      
      // Pose 객체 생성
      val anchorPoseObj = Pose(anchorTranslation, anchorQuaternion)
      
      // 앵커 생성
      anchor = session.createAnchor(anchorPoseObj)
      anchorPose = anchor!!.pose
      
      // 첫 프레임 앵커 정보 저장 (보정에 사용) - 앵커 생성 직후 즉시 저장
      firstAnchorPos = floatArrayOf(anchorPose!!.tx(), anchorPose!!.ty(), anchorPose!!.tz())
      firstAnchorQuat = floatArrayOf(anchorPose!!.qx(), anchorPose!!.qy(), anchorPose!!.qz(), anchorPose!!.qw())
      
      // 앵커 위치를 시각화 리스트에 추가
      val anchorPos = floatArrayOf(anchorPose!!.tx(), anchorPose!!.ty(), anchorPose!!.tz())
      anchorSpheres.add(anchorPos)
      
      Log.i(TAG, "Anchor created 30cm in front of camera: pos=(${anchorPose!!.tx()}, ${anchorPose!!.ty()}, ${anchorPose!!.tz()})")
      Log.i(TAG, "First anchor info saved for coordinate correction: pos=(${firstAnchorPos!![0]}, ${firstAnchorPos!![1]}, ${firstAnchorPos!![2]})")
      
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
      
      // 안정화 감지 상태 리셋
      stableStartTime = null
      previousPose = null
      isTrackingStable = false

      Log.i(TAG, "Recording started: ${outputDir!!.absolutePath}")
      activity.runOnUiThread {
        activity.view.statusText.text = "녹화 중..."
      }
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
        // JSONL에서 첫 프레임 앵커 정보 읽기
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
                      Log.i(TAG, "First anchor loaded from JSONL: pos=(${firstAnchorPos!![0]}, ${firstAnchorPos!![1]}, ${firstAnchorPos!![2]})")
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
            // GPU 서버에서 계산한 좌표는 첫 프레임 앵커 기준 월드 좌표(W0)
            // 렌더링 시 현재 앵커 pose로 보정하여 사용
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
                
                Log.i(TAG, "GPU result (W0 coordinates): bottom=(${bottomWorld[0]}, ${bottomWorld[1]}, ${bottomWorld[2]}), top=(${topWorld[0]}, ${topWorld[1]}, ${topWorld[2]})")
                
                // GPU 좌표를 W0 좌표로 저장 (렌더링 시 현재 앵커로 보정)
                setRotationAxis(bottomWorld, topWorld)
                
                // 첫 프레임 앵커 정보 저장 (보정에 사용)
                if (firstAnchorPos == null || firstAnchorQuat == null) {
                  val currentAnchor = anchor
                  if (currentAnchor != null && currentAnchor.trackingState == TrackingState.TRACKING) {
                    val anchorPose = currentAnchor.pose
                    firstAnchorPos = floatArrayOf(anchorPose.tx(), anchorPose.ty(), anchorPose.tz())
                    firstAnchorQuat = floatArrayOf(anchorPose.qx(), anchorPose.qy(), anchorPose.qz(), anchorPose.qw())
                    Log.i(TAG, "First anchor info saved for coordinate correction: pos=(${firstAnchorPos!![0]}, ${firstAnchorPos!![1]}, ${firstAnchorPos!![2]})")
                  } else if (anchorPose != null) {
                    firstAnchorPos = floatArrayOf(anchorPose!!.tx(), anchorPose!!.ty(), anchorPose!!.tz())
                    firstAnchorQuat = floatArrayOf(anchorPose!!.qx(), anchorPose!!.qy(), anchorPose!!.qz(), anchorPose!!.qw())
                    Log.i(TAG, "First anchor info saved from anchorPose: pos=(${firstAnchorPos!![0]}, ${firstAnchorPos!![1]}, ${firstAnchorPos!![2]})")
                  }
                }
                
                activity.view.statusText.text = "처리 완료! 회전축 표시됨 (앵커 드리프트 보정 적용)"
                Log.i(TAG, "Rotation axis set with W0 coordinates (will be corrected with current anchor during rendering)")
              } else {
                activity.view.statusText.text = "처리 완료 (회전축 좌표 오류)"
              }
            } else if (response.cup_coordinates != null && response.cup_coordinates.size >= 3) {
              // 컵 좌표도 GPU 좌표를 그대로 사용
              val cupWorld = floatArrayOf(
                response.cup_coordinates[0],
                response.cup_coordinates[1],
                response.cup_coordinates[2]
              )
              
              createCircleAt(cupWorld[0], cupWorld[1], cupWorld[2])
              activity.view.statusText.text = "처리 완료! 컵 표시됨"
            } else {
              activity.view.statusText.text = "처리 완료 (좌표 없음)"
            }
          }
        }.onFailure { error ->
          Log.e(TAG, "업로드 실패", error)
          
          activity.runOnUiThread {
            activity.view.statusText.text = "업로드 실패: ${error.message}"
            // Clean up anchor even on failure
            anchor?.detach()
            anchor = null
            anchorPose = null
            firstAnchorPos = null
            firstAnchorQuat = null
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
      val displayRotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        activity.display?.rotation ?: 0
      } else {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.rotation
      } * 90
      
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

