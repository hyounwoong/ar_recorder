package com.ar.recorder.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit 클라이언트 싱글톤
 */
object RetrofitClient {
    // 서버 IP 주소 설정 (Wi-Fi 어댑터의 IP)
    // VPN이 켜져있어도 Wi-Fi 어댑터의 IP를 사용해야 함
    private const val BASE_URL = "http://192.168.123.100:5000"
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)  // GPU 서버 모델 실행 시간이 길 수 있으므로 10분 (600초)
        .writeTimeout(120, TimeUnit.SECONDS)  // 파일 업로드 시간 고려하여 2분
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val apiService: ApiService = retrofit.create(ApiService::class.java)
    
    /**
     * 서버 URL을 동적으로 변경할 수 있는 메서드
     */
    fun setBaseUrl(url: String) {
        // 필요시 동적으로 URL 변경하는 로직 추가 가능
    }
}
