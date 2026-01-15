package com.ar.recorder

import android.opengl.Matrix
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 보정 로직 검증 테스트
 * 
 * 테스트 시나리오:
 * 1. 첫 프레임 앵커 pose 설정
 * 2. 현재 프레임 앵커 pose 설정 (드리프트 발생)
 * 3. W0 좌표계의 점을 현재 좌표계로 변환
 * 4. 수학적으로 예상되는 결과와 비교
 */
class CoordinateTransformTest {
    
    companion object {
        private const val EPSILON = 0.001f // 부동소수점 비교 허용 오차
    }
    
    /**
     * Quaternion을 회전 행렬로 변환 (ArRecorderRenderer와 동일)
     */
    private fun quaternionToRotationMatrix(qx: Float, qy: Float, qz: Float, qw: Float): FloatArray {
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        
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
     * 앵커 pose로부터 4x4 변환 행렬 생성 (현재 구현)
     */
    private fun buildAnchorTransformMatrixCurrent(pos: FloatArray, quat: FloatArray): FloatArray {
        val rotation = quaternionToRotationMatrix(quat[0], quat[1], quat[2], quat[3])
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        
        // 회전 먼저 적용
        Matrix.multiplyMM(matrix, 0, matrix, 0, rotation, 0)
        // 그 다음 이동
        Matrix.translateM(matrix, 0, pos[0], pos[1], pos[2])
        
        return matrix
    }
    
    /**
     * 앵커 pose로부터 4x4 변환 행렬 생성 (올바른 구현)
     */
    private fun buildAnchorTransformMatrixCorrect(pos: FloatArray, quat: FloatArray): FloatArray {
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
        matrix[3] = 0.0f
        matrix[7] = 0.0f
        matrix[11] = 0.0f
        matrix[15] = 1.0f
        
        return matrix
    }
    
    /**
     * 행렬 곱셈
     */
    private fun multiplyMatrices(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, a, 0, b, 0)
        return result
    }
    
    /**
     * 행렬 역행렬
     */
    private fun invertMatrix(matrix: FloatArray): FloatArray {
        val result = FloatArray(16)
        Matrix.invertM(result, 0, matrix, 0)
        return result
    }
    
    /**
     * W0 좌표를 현재 좌표로 변환 (현재 구현)
     */
    private fun transformW0ToCurrentWorldCurrent(
        pointW0: FloatArray,
        firstAnchorPos: FloatArray,
        firstAnchorQuat: FloatArray,
        currentAnchorPos: FloatArray,
        currentAnchorQuat: FloatArray
    ): FloatArray {
        val A0 = buildAnchorTransformMatrixCurrent(firstAnchorPos, firstAnchorQuat)
        val A_t = buildAnchorTransformMatrixCurrent(currentAnchorPos, currentAnchorQuat)
        val S_inv = multiplyMatrices(A_t, invertMatrix(A0))
        
        val pointW0Homogeneous = FloatArray(4).apply {
            pointW0.copyInto(this, 0, 0, 3)
            this[3] = 1.0f
        }
        
        val result = FloatArray(4)
        Matrix.multiplyMV(result, 0, S_inv, 0, pointW0Homogeneous, 0)
        
        return floatArrayOf(result[0], result[1], result[2])
    }
    
    /**
     * W0 좌표를 현재 좌표로 변환 (올바른 구현)
     */
    private fun transformW0ToCurrentWorldCorrect(
        pointW0: FloatArray,
        firstAnchorPos: FloatArray,
        firstAnchorQuat: FloatArray,
        currentAnchorPos: FloatArray,
        currentAnchorQuat: FloatArray
    ): FloatArray {
        val A0 = buildAnchorTransformMatrixCorrect(firstAnchorPos, firstAnchorQuat)
        val A_t = buildAnchorTransformMatrixCorrect(currentAnchorPos, currentAnchorQuat)
        val S_inv = multiplyMatrices(A_t, invertMatrix(A0))
        
        val pointW0Homogeneous = FloatArray(4).apply {
            pointW0.copyInto(this, 0, 0, 3)
            this[3] = 1.0f
        }
        
        val result = FloatArray(4)
        Matrix.multiplyMV(result, 0, S_inv, 0, pointW0Homogeneous, 0)
        
        return floatArrayOf(result[0], result[1], result[2])
    }
    
    /**
     * 두 FloatArray가 거의 같은지 확인
     */
    private fun assertFloatArrayEquals(expected: FloatArray, actual: FloatArray, epsilon: Float = EPSILON) {
        assertEquals("Array length mismatch", expected.size, actual.size)
        for (i in expected.indices) {
            val diff = abs(expected[i] - actual[i])
            assertTrue(
                "Mismatch at index $i: expected ${expected[i]}, actual ${actual[i]}, diff $diff",
                diff < epsilon
            )
        }
    }
    
    /**
     * 테스트 1: 단순 이동만 있는 경우
     * 첫 프레임 앵커: 원점, 회전 없음
     * 현재 프레임 앵커: x축으로 1m 이동, 회전 없음
     * W0 좌표: [0, 0, 0] (첫 프레임 앵커 위치)
     * 예상 결과: [1, 0, 0] (현재 앵커 위치)
     */
    @Test
    fun testSimpleTranslation() {
        // 첫 프레임 앵커: 원점, 회전 없음
        val firstAnchorPos = floatArrayOf(0.0f, 0.0f, 0.0f)
        val firstAnchorQuat = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f) // 단위 quaternion
        
        // 현재 프레임 앵커: x축으로 1m 이동
        val currentAnchorPos = floatArrayOf(1.0f, 0.0f, 0.0f)
        val currentAnchorQuat = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
        
        // W0 좌표: 첫 프레임 앵커 위치
        val pointW0 = floatArrayOf(0.0f, 0.0f, 0.0f)
        
        // 올바른 구현으로 변환
        val resultCorrect = transformW0ToCurrentWorldCorrect(
            pointW0, firstAnchorPos, firstAnchorQuat,
            currentAnchorPos, currentAnchorQuat
        )
        
        // 예상 결과: 현재 앵커 위치
        val expected = floatArrayOf(1.0f, 0.0f, 0.0f)
        
        println("Test 1 - Simple Translation:")
        println("  W0: [${pointW0[0]}, ${pointW0[1]}, ${pointW0[2]}]")
        println("  Expected: [${expected[0]}, ${expected[1]}, ${expected[2]}]")
        println("  Correct: [${resultCorrect[0]}, ${resultCorrect[1]}, ${resultCorrect[2]}]")
        
        assertFloatArrayEquals(expected, resultCorrect)
    }
    
    /**
     * 테스트 2: 회전만 있는 경우
     * 첫 프레임 앵커: 원점, 회전 없음
     * 현재 프레임 앵커: 원점, y축으로 90도 회전
     * W0 좌표: [1, 0, 0] (첫 프레임 앵커 기준 x축 방향 1m)
     * 예상 결과: [0, 0, -1] (현재 앵커 기준으로 회전된 위치)
     */
    @Test
    fun testSimpleRotation() {
        // 첫 프레임 앵커: 원점, 회전 없음
        val firstAnchorPos = floatArrayOf(0.0f, 0.0f, 0.0f)
        val firstAnchorQuat = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
        
        // 현재 프레임 앵커: 원점, y축으로 90도 회전
        // y축 90도 회전 quaternion: [0, sin(45°), 0, cos(45°)] = [0, 0.707, 0, 0.707]
        val currentAnchorPos = floatArrayOf(0.0f, 0.0f, 0.0f)
        val currentAnchorQuat = floatArrayOf(0.0f, 0.7071068f, 0.0f, 0.7071068f) // y축 90도
        
        // W0 좌표: 첫 프레임 앵커 기준 x축 방향 1m
        val pointW0 = floatArrayOf(1.0f, 0.0f, 0.0f)
        
        // 올바른 구현으로 변환
        val resultCorrect = transformW0ToCurrentWorldCorrect(
            pointW0, firstAnchorPos, firstAnchorQuat,
            currentAnchorPos, currentAnchorQuat
        )
        
        // 예상 결과: y축 90도 회전하면 x축 [1,0,0]이 z축 음수 방향 [0,0,-1]이 됨
        val expected = floatArrayOf(0.0f, 0.0f, -1.0f)
        
        println("\nTest 2 - Simple Rotation:")
        println("  W0: [${pointW0[0]}, ${pointW0[1]}, ${pointW0[2]}]")
        println("  Expected: [${expected[0]}, ${expected[1]}, ${expected[2]}]")
        println("  Correct: [${resultCorrect[0]}, ${resultCorrect[1]}, ${resultCorrect[2]}]")
        
        assertFloatArrayEquals(expected, resultCorrect, 0.01f)
    }
    
    /**
     * 테스트 3: 이동 + 회전이 있는 경우
     * 첫 프레임 앵커: [0, 0, 0], 회전 없음
     * 현재 프레임 앵커: [1, 0, 0] 이동 + y축 90도 회전
     * W0 좌표: [0, 0, 0] (첫 프레임 앵커 위치)
     * 예상 결과: [1, 0, 0] (현재 앵커 위치)
     */
    @Test
    fun testTranslationAndRotation() {
        // 첫 프레임 앵커: 원점, 회전 없음
        val firstAnchorPos = floatArrayOf(0.0f, 0.0f, 0.0f)
        val firstAnchorQuat = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
        
        // 현재 프레임 앵커: [1, 0, 0] 이동 + y축 90도 회전
        val currentAnchorPos = floatArrayOf(1.0f, 0.0f, 0.0f)
        val currentAnchorQuat = floatArrayOf(0.0f, 0.7071068f, 0.0f, 0.7071068f)
        
        // W0 좌표: 첫 프레임 앵커 위치
        val pointW0 = floatArrayOf(0.0f, 0.0f, 0.0f)
        
        // 올바른 구현으로 변환
        val resultCorrect = transformW0ToCurrentWorldCorrect(
            pointW0, firstAnchorPos, firstAnchorQuat,
            currentAnchorPos, currentAnchorQuat
        )
        
        // 예상 결과: 현재 앵커 위치
        val expected = floatArrayOf(1.0f, 0.0f, 0.0f)
        
        println("\nTest 3 - Translation and Rotation:")
        println("  W0: [${pointW0[0]}, ${pointW0[1]}, ${pointW0[2]}]")
        println("  Expected: [${expected[0]}, ${expected[1]}, ${expected[2]}]")
        println("  Correct: [${resultCorrect[0]}, ${resultCorrect[1]}, ${resultCorrect[2]}]")
        
        assertFloatArrayEquals(expected, resultCorrect, 0.01f)
    }
    
    /**
     * 테스트 4: 현재 구현 vs 올바른 구현 비교
     * 문제가 있다면 두 구현의 결과가 다를 것
     */
    @Test
    fun testCurrentVsCorrectImplementation() {
        // 첫 프레임 앵커: [0, 0, 0], 회전 없음
        val firstAnchorPos = floatArrayOf(0.0f, 0.0f, 0.0f)
        val firstAnchorQuat = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
        
        // 현재 프레임 앵커: [1, 0, 0] 이동 + y축 90도 회전
        val currentAnchorPos = floatArrayOf(1.0f, 0.0f, 0.0f)
        val currentAnchorQuat = floatArrayOf(0.0f, 0.7071068f, 0.0f, 0.7071068f)
        
        // W0 좌표: 첫 프레임 앵커 기준 [0.5, 0, 0]
        val pointW0 = floatArrayOf(0.5f, 0.0f, 0.0f)
        
        // 현재 구현으로 변환
        val resultCurrent = transformW0ToCurrentWorldCurrent(
            pointW0, firstAnchorPos, firstAnchorQuat,
            currentAnchorPos, currentAnchorQuat
        )
        
        // 올바른 구현으로 변환
        val resultCorrect = transformW0ToCurrentWorldCorrect(
            pointW0, firstAnchorPos, firstAnchorQuat,
            currentAnchorPos, currentAnchorQuat
        )
        
        println("\nTest 4 - Current vs Correct Implementation:")
        println("  W0: [${pointW0[0]}, ${pointW0[1]}, ${pointW0[2]}]")
        println("  Current: [${resultCurrent[0]}, ${resultCurrent[1]}, ${resultCurrent[2]}]")
        println("  Correct: [${resultCorrect[0]}, ${resultCorrect[1]}, ${resultCorrect[2]}]")
        
        // 두 결과가 다른지 확인 (문제가 있다면 다를 것)
        val diff = sqrt(
            (resultCurrent[0] - resultCorrect[0]) * (resultCurrent[0] - resultCorrect[0]) +
            (resultCurrent[1] - resultCorrect[1]) * (resultCurrent[1] - resultCorrect[1]) +
            (resultCurrent[2] - resultCorrect[2]) * (resultCurrent[2] - resultCorrect[2])
        )
        
        println("  Difference: $diff")
        
        if (diff > EPSILON) {
            println("  ⚠️ WARNING: Current implementation differs from correct implementation!")
            println("  This indicates a bug in buildAnchorTransformMatrix")
        } else {
            println("  ✓ Current implementation matches correct implementation")
        }
    }
    
    /**
     * 테스트 5: 역변환 검증
     * 변환 후 다시 역변환하면 원래 좌표로 돌아와야 함
     */
    @Test
    fun testInverseTransform() {
        // 첫 프레임 앵커: [0, 0, 0], 회전 없음
        val firstAnchorPos = floatArrayOf(0.0f, 0.0f, 0.0f)
        val firstAnchorQuat = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f)
        
        // 현재 프레임 앵커: [1, 2, 3] 이동 + 복잡한 회전
        val currentAnchorPos = floatArrayOf(1.0f, 2.0f, 3.0f)
        val currentAnchorQuat = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f) // 정규화 필요
        val quatNorm = sqrt(
            currentAnchorQuat[0] * currentAnchorQuat[0] +
            currentAnchorQuat[1] * currentAnchorQuat[1] +
            currentAnchorQuat[2] * currentAnchorQuat[2] +
            currentAnchorQuat[3] * currentAnchorQuat[3]
        )
        val normalizedQuat = floatArrayOf(
            currentAnchorQuat[0] / quatNorm,
            currentAnchorQuat[1] / quatNorm,
            currentAnchorQuat[2] / quatNorm,
            currentAnchorQuat[3] / quatNorm
        )
        
        // W0 좌표
        val pointW0 = floatArrayOf(0.5f, 0.3f, -0.2f)
        
        // W0 → 현재 좌표로 변환
        val resultCorrect = transformW0ToCurrentWorldCorrect(
            pointW0, firstAnchorPos, firstAnchorQuat,
            currentAnchorPos, normalizedQuat
        )
        
        // 현재 좌표 → W0로 역변환
        val A0 = buildAnchorTransformMatrixCorrect(firstAnchorPos, firstAnchorQuat)
        val A_t = buildAnchorTransformMatrixCorrect(currentAnchorPos, normalizedQuat)
        val S = multiplyMatrices(invertMatrix(A_t), A0) // 역변환
        
        val resultHomogeneous = FloatArray(4).apply {
            resultCorrect.copyInto(this, 0, 0, 3)
            this[3] = 1.0f
        }
        
        val backToW0 = FloatArray(4)
        Matrix.multiplyMV(backToW0, 0, S, 0, resultHomogeneous, 0)
        val backToW0Result = floatArrayOf(backToW0[0], backToW0[1], backToW0[2])
        
        println("\nTest 5 - Inverse Transform:")
        println("  Original W0: [${pointW0[0]}, ${pointW0[1]}, ${pointW0[2]}]")
        println("  Transformed: [${resultCorrect[0]}, ${resultCorrect[1]}, ${resultCorrect[2]}]")
        println("  Back to W0: [${backToW0Result[0]}, ${backToW0Result[1]}, ${backToW0Result[2]}]")
        
        assertFloatArrayEquals(pointW0, backToW0Result, 0.01f)
    }
}
