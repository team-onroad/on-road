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

    /** 이 헤더가 있는 요청은 읽기 타임아웃을 그 초만큼 (사진 양식 읽기 등). 서버로는 보내지 않는다 */
    const val TIMEOUT_HEADER = "X-OnRoad-Timeout"

    /** cacheDir 를 주면 서버가 Cache-Control 로 허락한 조회(상황 목록 · 기준표 · 양식 목록)를 휴대폰에 잠깐 저장해 다시 쓴다 */
    fun create(baseUrl: String, cacheDir: java.io.File? = null): OnRoadService {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS) // 검색(RAG) 권장 타임아웃 30초 + 여유
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply { cacheDir?.let { cache(okhttp3.Cache(java.io.File(it, "http"), 10L * 1024 * 1024)) } }
            .addInterceptor { chain ->
                val seconds = chain.request().header(TIMEOUT_HEADER)?.toIntOrNull()
                if (seconds == null) chain.proceed(chain.request())
                else chain.withReadTimeout(seconds, TimeUnit.SECONDS)
                    .proceed(chain.request().newBuilder().removeHeader(TIMEOUT_HEADER).build())
            }
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

/** 화면에 보여 줄 오류 문구 (api.md 1.1 에러 코드 기준). 모르는 오류는 fallback */
fun Throwable.userMessage(fallback: String = "잠시 후 다시 시도해 주세요."): String =
    when ((this as? ApiException)?.code) {
        "AI_UNAVAILABLE" -> "지금은 AI 답변을 잠시 쓸 수 없어요. 잠시 후 다시 시도해 주세요."
        "RAG_UNAVAILABLE" -> "지금은 공식 자료 검색을 잠시 쓸 수 없어요. 잠시 후 다시 시도해 주세요."
        "SCENARIO_NOT_FOUND" -> "이 연습 상황을 찾지 못했어요. 목록에서 다시 골라 주세요."
        "FORM_NOT_FOUND" -> "이 양식을 찾지 못했어요. 목록에서 다시 골라 주세요."
        "CHECKLIST_NOT_FOUND" -> "체크리스트를 찾지 못했어요."
        "POLICY_NOT_FOUND" -> "더 이상 제공되지 않는 정책이에요."
        "SIMULATION_NOT_FOUND" -> "지난 계산 결과를 찾지 못했어요."
        "USER_NOT_FOUND" -> "사용자 정보를 찾지 못했어요. 앱을 다시 시작해 주세요."
        // 올린 양식: 서버 문구가 무엇을 다시 하면 되는지 알려 준다
        "UPLOAD_INVALID", "FORM_UNREADABLE" -> message ?: fallback
        else -> fallback
    }
