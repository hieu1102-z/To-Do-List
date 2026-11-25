package com.finalexam.to_do_list.model.repository

import com.finalexam.to_do_list.BuildConfig
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class WorkspaceRequestError(
    val code: String,
    val retryAfterMillis: Long? = null,
    val isTransportFailure: Boolean = false,
    cause: Throwable? = null
) : IOException(code, cause)

internal fun Throwable.businessCode(): String = when (this) {
    is WorkspaceRequestError -> code
    is InterruptedIOException, is TimeoutException -> "DEADLINE_EXCEEDED"
    else -> "NETWORK"
}

internal val Throwable.isTransientWorkspaceFailure: Boolean
    get() = this is WorkspaceRequestError && code in setOf("NETWORK", "UNAVAILABLE", "INTERNAL", "ABORTED",
        "DEADLINE_EXCEEDED", "CATEGORY_BUSY", "UNAUTHENTICATED", "TOKEN_EXPIRED", "RATE_LIMITED")

internal object WorkspaceApiConfig {
    val url: String = BuildConfig.WORKSPACE_API_BASE_URL
    val configured get() = url.isNotEmpty()
}

private val sharedHttp = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

internal class WorkspaceHttp(
    baseUrl: String,
    private val uid: String,
    private val token: (forceRefresh: Boolean, remainingNanos: Long) -> String,
    private val client: OkHttpClient = sharedHttp
) {
    private val endpoint = baseUrl.takeIf { it.isNotBlank() }?.trimEnd('/')?.let {
        (it + "/v1/workspace").toHttpUrl().also { url ->
            require(url.isHttps && url.query == null && url.fragment == null &&
                url.username.isEmpty() && url.password.isEmpty())
        }
    }
    private val json = "application/json; charset=utf-8".toMediaType()

    fun call(data: Row, forceRefresh: Boolean = false): Row {
        val url = endpoint ?: throw WorkspaceRequestError("BACKEND_NOT_CONFIGURED")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        val operationId = data["operation_id"] as? String
        val categoryId = data["category_id"] as? String
        val requestedSeq = data.optionalLong("seq")
        fun remaining(): Long = (deadline - System.nanoTime()).also {
            if (it <= 0) throw WorkspaceRequestError("DEADLINE_EXCEEDED")
        }
        val body = JSONObject(data + ("expected_uid" to uid)).toString().toRequestBody(json)
        for (attempt in 0..1) {
            val tokenStarted = System.nanoTime()
            var tokenCode = "CANCELLED"
            val authorization = try {
                token(forceRefresh || attempt > 0, remaining()).also { tokenCode = "OK" }
            } catch (error: WorkspaceRequestError) {
                tokenCode = error.code
                throw error
            } catch (error: TimeoutException) {
                tokenCode = "DEADLINE_EXCEEDED"
                throw WorkspaceRequestError(tokenCode, cause = error)
            } finally {
                WorkspaceTiming.record("android_token_finished", operationId, categoryId, requestedSeq,
                    WorkspaceTiming.elapsedMillis(tokenStarted), tokenCode)
            }
            val request = Request.Builder().url(url).header("Authorization", "Bearer $authorization")
                .header("Accept", "application/json").post(body).build()
            val call = client.newCall(request)
            call.timeout().timeout(remaining(), TimeUnit.NANOSECONDS)
            val requestStarted = System.nanoTime()
            var resultCode = "CANCELLED"
            var resultSeq = requestedSeq
            WorkspaceTiming.record("android_http_send", operationId, categoryId, requestedSeq)
            try {
                call.execute().use { response ->
                    val status = response.code
                    resultCode = "HTTP_$status"
                    val retryAfter = if (status == 429 || status == 503) {
                        retryAfterMillis(response.header("Retry-After"), System.currentTimeMillis())
                    } else null
                    if (status == 429) throw WorkspaceRequestError("RATE_LIMITED", retryAfter)
                    val payload = response.body?.takeIf {
                        it.contentType()?.type == "application" && it.contentType()?.subtype == "json"
                    }?.source()?.let { source ->
                        if (source.request(MAX_RESPONSE + 1)) throw WorkspaceRequestError("UNAVAILABLE", retryAfter)
                        try { JSONObject(source.readUtf8()) } catch (_: JSONException) { null }
                    }
                    if (status == 401 && payload?.optString("business_code") == "TOKEN_EXPIRED" && attempt == 0) {
                        return@use
                    }
                    if (status == 200 || status == 202) {
                        val result = payload?.optJSONObject("result")?.toRow()
                            ?: throw WorkspaceRequestError("UNAVAILABLE")
                        resultSeq = result.optionalLong("seq") ?: requestedSeq
                        return result
                    }
                    throw WorkspaceRequestError(errorCode(status, payload), retryAfter)
                }
            } catch (error: WorkspaceRequestError) {
                resultCode = error.code
                throw error
            } catch (error: IOException) {
                if (Thread.currentThread().isInterrupted) throw error
                resultCode = if (error is InterruptedIOException) "DEADLINE_EXCEEDED" else "NETWORK"
                throw WorkspaceRequestError(resultCode, isTransportFailure = true, cause = error)
            } finally {
                WorkspaceTiming.record("android_http_finished", operationId, categoryId, resultSeq,
                    WorkspaceTiming.elapsedMillis(requestStarted), resultCode)
            }
        }
        throw WorkspaceRequestError("UNAUTHENTICATED")
    }

    private fun errorCode(status: Int, body: JSONObject?): String {
        val business = body?.optString("business_code")?.takeIf { Regex("[A-Z][A-Z0-9_]{1,63}").matches(it) }
        // An intermediary's generic HTTP 403 must never lead to clearing local data.
        if (business == "PERMISSION_DENIED") return if (status == 403) business else "UNAVAILABLE"
        if (status in 400..499 && business != null) return business
        if (status == 504) return "DEADLINE_EXCEEDED"
        if (status >= 500 && business in setOf("CATEGORY_BUSY", "UNAVAILABLE", "INTERNAL")) return checkNotNull(business)
        return "UNAVAILABLE"
    }

    private companion object {
        const val MAX_RESPONSE = 4L * 1024 * 1024
    }
}

internal fun retryAfterMillis(header: String?, nowMillis: Long): Long? {
    val value = header?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (value.all { it in '0'..'9' }) {
        val seconds = value.toLongOrNull() ?: Long.MAX_VALUE
        return TimeUnit.SECONDS.toMillis(seconds).coerceAtMost(Long.MAX_VALUE - nowMillis)
    }
    return try {
        val until = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (until - nowMillis).coerceAtLeast(0)
    } catch (_: DateTimeParseException) {
        null
    }
}

internal fun workspaceHttp(app: FirebaseApp, uid: String, active: () -> Boolean): WorkspaceHttp =
    WorkspaceHttp(WorkspaceApiConfig.url, uid, { force, remaining ->
        val auth = FirebaseAuth.getInstance(app)
        val current = auth.currentUser?.takeIf { it.uid == uid && active() }
            ?: throw WorkspaceRequestError("UNAUTHENTICATED")
        val result = try { Tasks.await(current.getIdToken(force), remaining, TimeUnit.NANOSECONDS) }
        catch (error: ExecutionException) {
            if (error.cause is FirebaseAuthInvalidUserException) throw WorkspaceRequestError("UNAUTHENTICATED")
            throw WorkspaceRequestError("UNAVAILABLE")
        }
        result.token?.takeIf { auth.currentUser?.uid == uid && active() }
            ?: throw WorkspaceRequestError("UNAUTHENTICATED")
    })

private fun JSONObject.toRow(): Row = keys().asSequence().associateWith { key -> unwrap(get(key)) }

private fun unwrap(value: Any?): Any? = when {
    value == null || value === JSONObject.NULL -> null
    value is JSONObject -> value.toRow()
    value is JSONArray -> List(value.length()) { unwrap(value.get(it)) }
    else -> value
}
