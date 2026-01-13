package com.ar.recorder.network

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * 서버 API 인터페이스
 */
interface ApiService {
    @GET("/api/health")
    suspend fun healthCheck(): Response<HealthResponse>
    
    @Multipart
    @POST("/api/process-session")
    suspend fun processSession(
        @Part file: MultipartBody.Part
    ): Response<ProcessSessionResponse>
}

/**
 * Health check 응답
 */
data class HealthResponse(
    val status: String,
    val message: String
)

/**
 * 세션 처리 응답
 */
data class ProcessSessionResponse(
    val success: Boolean,
    val cup_coordinates: List<Float>? = null,
    val error: String? = null,
    val message: String? = null
)
