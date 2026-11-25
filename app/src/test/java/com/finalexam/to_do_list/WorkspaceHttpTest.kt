package com.finalexam.to_do_list

import android.app.Application
import com.finalexam.to_do_list.model.repository.WorkspaceHttp
import com.finalexam.to_do_list.model.repository.WorkspaceRequestError
import com.finalexam.to_do_list.model.repository.businessCode
import com.finalexam.to_do_list.model.repository.isTransientWorkspaceFailure
import com.finalexam.to_do_list.model.repository.retryAfterMillis
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class WorkspaceHttpTest {
    private val endpoint = "https://workspace.example.test"
    private val command = mapOf("kind" to "PURGE", "operation_id" to "fixed-op", "payload" to null)
    private fun client(respond: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor { respond(it.request()) }.build()

    private fun reply(request: Request, status: Int, json: String, media: String = "application/json") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Test")
            .body(json.toResponseBody(media.toMediaType())).build()

    @Test fun refreshesExpiredTokenAndResendsIdenticalOperationWithinOneDeadline() {
        val refresh = mutableListOf<Boolean>()
        val serialized = mutableListOf<String>()
        val auth = mutableListOf<String?>()
        val budgets = mutableListOf<Long>()
        val service = WorkspaceHttp(endpoint, "account", { force, remaining ->
            refresh += force
            budgets += remaining
            if (force) "new-token" else "old-token"
        }, client { response ->
            assertEquals("/v1/workspace", response.url.encodedPath)
            serialized += Buffer().also { response.body!!.writeTo(it) }.readUtf8()
            auth += response.header("Authorization")
            if (serialized.size == 1) reply(response, 401, """{"business_code":"TOKEN_EXPIRED"}""")
            else reply(response, 202, """{"result":{"pending":true}}""")
        })
        assertEquals(mapOf("pending" to true), service.call(command))
        assertEquals(listOf(false, true), refresh)
        assertTrue(budgets[1] < budgets[0])
        assertEquals(listOf("Bearer old-token", "Bearer new-token"), auth)
        assertEquals(serialized.first(), serialized.last())
        JSONObject(serialized.first()).apply {
            assertEquals("fixed-op", getString("operation_id"))
            assertEquals("account", getString("expected_uid"))
            assertTrue(has("payload") && isNull("payload"))
        }
    }

    @Test fun onlyStructuredBusinessDenialCanClearCache() {
        fun failure(status: Int, body: String, media: String): WorkspaceRequestError {
            return assertThrows(WorkspaceRequestError::class.java) {
                WorkspaceHttp(endpoint, "account", { _, _ -> "token" },
                    client { reply(it, status, body, media) }).call(command)
            }
        }
        val denial = failure(403, """{"business_code":"PERMISSION_DENIED"}""", "application/json")
        assertEquals("PERMISSION_DENIED", denial.businessCode())
        assertFalse(denial.isTransientWorkspaceFailure)
        val proxyFailure = failure(403, "<html>proxy refused this request</html>", "text/html")
        assertEquals("UNAVAILABLE", proxyFailure.businessCode())
        assertTrue(proxyFailure.isTransientWorkspaceFailure)
        assertTrue(failure(503, "Waking up", "text/html").isTransientWorkspaceFailure)
        assertTrue(failure(200, "<html>Waking up</html>", "text/html").isTransientWorkspaceFailure)
        assertEquals("RATE_LIMITED", failure(429, "Too many requests", "text/plain").businessCode())
        assertTrue(failure(504, "Gateway timeout", "text/plain").isTransientWorkspaceFailure)
    }

    @Test fun absentEndpointRequiresNoTokenAndPlainHttpIsRejected() {
        val noToken = WorkspaceHttp("", "account", { _, _ -> error("should not request token") })
        assertEquals("BACKEND_NOT_CONFIGURED", assertThrows(WorkspaceRequestError::class.java) {
            noToken.call(command)
        }.businessCode())
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceHttp("http://workspace.example.test", "account", { _, _ -> "" })
        }
    }

    @Test fun retryAfterFromServiceSurvivesErrorDecoding() {
        for (status in listOf(429, 503)) {
            val workspace = WorkspaceHttp(endpoint, "account", { _, _ -> "token" }, client {
                reply(it, status, "Wait", "text/plain").newBuilder().header("Retry-After", "75").build()
            })
            val failure = assertThrows(WorkspaceRequestError::class.java) { workspace.call(command) }
            assertEquals(if (status == 429) "RATE_LIMITED" else "UNAVAILABLE", failure.code)
            assertEquals(75_000L, failure.retryAfterMillis)
            assertFalse(failure.isTransportFailure)
        }
        val now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertEquals(12_000L, retryAfterMillis("Thu, 1 Jan 2026 00:00:12 GMT", now))
        assertEquals(0L, retryAfterMillis("Wed, 31 Dec 2025 23:59:59 GMT", now))
        assertNull(retryAfterMillis("-5", now))
        assertNull(retryAfterMillis("broken", now))
    }

    @Test fun separatesNetworkFailuresFromAuthAndInvalidResponses() {
        fun transportError(error: IOException): WorkspaceRequestError {
            val workspace = WorkspaceHttp(endpoint, "account", { _, _ -> "token" }, client { throw error })
            return assertThrows(WorkspaceRequestError::class.java) { workspace.call(command) }
        }
        val disconnected = transportError(IOException("disconnected"))
        assertEquals("NETWORK", disconnected.code)
        assertTrue(disconnected.isTransportFailure)
        val timeout = transportError(SocketTimeoutException())
        assertEquals("DEADLINE_EXCEEDED", timeout.code)
        assertTrue(timeout.isTransportFailure)
        val authError = WorkspaceHttp(endpoint, "account", { _, _ -> throw WorkspaceRequestError("UNAVAILABLE") },
            client { error("no API call before receiving a token") })
        assertFalse(assertThrows(WorkspaceRequestError::class.java) { authError.call(command) }.isTransportFailure)
        val invalidResponse = WorkspaceHttp(endpoint, "account", { _, _ -> "token" },
            client { reply(it, 200, "<html>waking up</html>", "text/html") })
        assertFalse(assertThrows(WorkspaceRequestError::class.java) { invalidResponse.call(command) }.isTransportFailure)
        assertFalse(IllegalStateException("Room failure").isTransientWorkspaceFailure)
    }

    @Test fun refreshesProfileTokenOnRequestAndLogsOnlyCorrelationFields() {
        var forceRefresh = false
        val operationId = "f1e9f181-7671-426c-8243-65c760223cd8"
        val categoryId = "2fdc82fc-418b-4bc9-affe-786d4c2a3f78"
        val workspace = WorkspaceHttp(endpoint, "account-private", { force, _ ->
            forceRefresh = force
            "token-private"
        }, client { reply(it, 200, """{"result":{"seq":13,"title":"response-private"}}""") })
        workspace.call(mapOf("kind" to "PROFILE", "operation_id" to operationId, "category_id" to categoryId,
            "payload" to "payload-private"), forceRefresh = true)
        assertTrue(forceRefresh)
        val logs = ShadowLog.getLogsForTag("TodoListSync").map { it.msg }
            .filter { it.contains(operationId) }
        assertTrue(logs.any { it.contains("stage=android_http_send") })
        val finished = logs.single { it.contains("stage=android_http_finished") }
        assertTrue(finished.contains("server_seq=13"))
        assertTrue(finished.contains(Regex("category_hash=[0-9a-f]{12} ")))
        for (secret in listOf(categoryId, "account-private", "token-private", "payload-private", "response-private")) {
            assertTrue(logs.none { it.contains(secret) })
        }
    }

    @Test fun numericRevisionsAndNestedNullableRowsSurviveDecoding() {
        val service = WorkspaceHttp(endpoint, "account", { _, _ -> "token" }, client {
            reply(it, 200, """{"result":{"seq":4294967297,"rows":[{"title":"Đọc","note":null}],"next":null}}""")
        })
        val result = service.call(mapOf("kind" to "SNAPSHOT"))
        assertEquals(4_294_967_297L, result["seq"])
        assertEquals(listOf(mapOf("title" to "Đọc", "note" to null)), result["rows"])
        assertTrue("next" in result && result["next"] == null)
    }
}
