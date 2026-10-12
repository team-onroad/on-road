package com.project.on_road.data.remote


import com.google.gson.Gson
import com.project.on_road.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** 백엔드 공통 에러. code는 api.md 1.1의 에러 코드(USER_NOT_FOUND, SUM_EXCEEDS_INCOME 등). */
class ApiException(
    val httpStatus: Int,
    val code: String,
    override val message: String,
) : Exception(message)

object Network {
    private val gson = Gson()

    fun create(baseUrl: String): OnRoadService {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS) // 검색(RAG) 권장 타임아웃 30초 + 여유
            .writeTimeout(15, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(OnRoadService::class.java)
    }

    /** HttpException을 ApiException으로 바꿔서 던진다. 네트워크 오류(IOException)는 그대로 던진다. */
    suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (e: HttpException) {
            val body = runCatching {
                gson.fromJson(e.response()?.errorBody()?.string(), ErrorBodyDto::class.java)
            }.getOrNull()
            throw ApiException(
                httpStatus = e.code(),
                code = body?.error?.code ?: "HTTP_${e.code()}",
                message = body?.error?.message ?: e.message(),
            )
        }
}
