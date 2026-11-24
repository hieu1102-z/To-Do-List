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
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class WorkspaceRequestError(val code: String) : IOException(code)

internal fun Throwable.businessCode(): String = when (this) {
    is WorkspaceRequestError -> code
    is InterruptedIOException, is TimeoutException -> "DEADLINE_EXCEEDED"
    else -> "NETWORK"
}

internal val Throwable.isTransientWorkspaceFailure: Boolean
    get() = businessCode() in setOf("NETWORK", "UNAVAILABLE", "INTERNAL", "ABORTED",
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

    fun call(data: Row): Row {
        val url = endpoint ?: throw WorkspaceRequestError("BACKEND_NOT_CONFIGURED")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        fun remaining(): Long = (deadline - System.nanoTime()).also {
            if (it <= 0) throw WorkspaceRequestError("DEADLINE_EXCEEDED")
        }
        val body = JSONObject(data + ("expected_uid" to uid)).toString().toRequestBody(json)
        for (attempt in 0..1) {
            val authorization = token(attempt > 0, remaining())
            val request = Request.Builder().url(url).header("Authorization", "Bearer $authorization")
                .header("Accept", "application/json").post(body).build()
            val call = client.newCall(request)
            call.timeout().timeout(remaining(), TimeUnit.NANOSECONDS)
            try {
                call.execute().use { response ->
                    val status = response.code
                    val payload = response.body?.takeIf {
                        it.contentType()?.type == "application" && it.contentType()?.subtype == "json"
                    }?.source()?.let { source ->
                        if (source.request(MAX_RESPONSE + 1)) throw WorkspaceRequestError("UNAVAILABLE")
                        try { JSONObject(source.readUtf8()) } catch (_: JSONException) { null }
                    }
                    if (status == 401 && payload?.optString("business_code") == "TOKEN_EXPIRED" && attempt == 0) {
                        return@use
                    }
                    if (status == 200 || status == 202) {
                        val result = payload?.optJSONObject("result") ?: throw WorkspaceRequestError("UNAVAILABLE")
                        return result.toRow()
                    }
                    throw WorkspaceRequestError(errorCode(status, payload))
                }
            } catch (error: InterruptedIOException) {
                if (Thread.currentThread().isInterrupted) throw error
                throw WorkspaceRequestError("DEADLINE_EXCEEDED")
            }
        }
        throw WorkspaceRequestError("UNAUTHENTICATED")
    }

    private fun errorCode(status: Int, body: JSONObject?): String {
        val business = body?.optString("business_code")?.takeIf { Regex("[A-Z][A-Z0-9_]{1,63}").matches(it) }
        // An intermediary's generic HTTP 403 must never lead to clearing local data.
        if (business == "PERMISSION_DENIED") return if (status == 403) business else "UNAVAILABLE"
        if (status == 429) return "RATE_LIMITED"
        if (status in 400..499 && business != null) return business
        if (status == 504) return "DEADLINE_EXCEEDED"
        if (status >= 500 && business in setOf("CATEGORY_BUSY", "UNAVAILABLE", "INTERNAL")) return checkNotNull(business)
        return "UNAVAILABLE"
    }

    private companion object {
        const val MAX_RESPONSE = 4L * 1024 * 1024
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
