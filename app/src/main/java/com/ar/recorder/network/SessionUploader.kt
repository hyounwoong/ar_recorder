package com.ar.recorder.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 세션 폴더를 ZIP으로 압축하고 서버에 업로드하는 클래스
 */
class SessionUploader {
    companion object {
        private const val TAG = "SessionUploader"
    }
    
    /**
     * 세션 폴더를 ZIP 파일로 압축
     */
    suspend fun zipSessionFolder(sessionFolder: File): File = withContext(Dispatchers.IO) {
        val zipFile = File(sessionFolder.parent, "${sessionFolder.name}.zip")
        
        ZipOutputStream(zipFile.outputStream()).use { zipOut ->
            sessionFolder.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(sessionFolder).path
                    val entry = ZipEntry(relativePath)
                    zipOut.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        }
        
        Log.i(TAG, "ZIP 파일 생성 완료: ${zipFile.absolutePath}, 크기: ${zipFile.length()} bytes")
        zipFile
    }
    
    /**
     * ZIP 파일을 서버에 업로드하고 컵 좌표를 받아옴
     */
    suspend fun uploadAndProcess(zipFile: File): Result<ProcessSessionResponse> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "서버에 업로드 시작: ${zipFile.absolutePath}")
            
            // MultipartBody.Part 생성
            val requestFile = zipFile.asRequestBody("application/zip".toMediaType())
            val body = MultipartBody.Part.createFormData("file", zipFile.name, requestFile)
            
            // API 호출
            val response = RetrofitClient.apiService.processSession(body)
            
            if (response.isSuccessful) {
                val result = response.body()
                if (result != null && result.success) {
                    Log.i(TAG, "업로드 및 처리 성공: ${result.cup_coordinates}")
                    Result.success(result)
                } else {
                    val errorMsg = result?.error ?: "Unknown error"
                    Log.e(TAG, "처리 실패: $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorMsg = "HTTP ${response.code()}: ${response.message()}"
                Log.e(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "업로드 중 오류 발생", e)
            Result.failure(e)
        }
    }
    
    /**
     * 세션 폴더를 압축하고 업로드하는 전체 프로세스
     */
    suspend fun uploadSessionFolder(sessionFolder: File): Result<ProcessSessionResponse> = withContext(Dispatchers.IO) {
        try {
            // 1. ZIP 압축
            val zipFile = zipSessionFolder(sessionFolder)
            
            try {
                // 2. 업로드
                val result = uploadAndProcess(zipFile)
                
                // 3. ZIP 파일 삭제 (선택사항)
                zipFile.delete()
                
                result
            } catch (e: Exception) {
                // 업로드 실패 시에도 ZIP 파일 삭제
                zipFile.delete()
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "세션 업로드 실패", e)
            Result.failure(e)
        }
    }
}
