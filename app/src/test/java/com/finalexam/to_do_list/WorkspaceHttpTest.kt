package com.finalexam.to_do_list

import android.app.Application
import com.finalexam.to_do_list.model.repository.WorkspaceHttp
import com.finalexam.to_do_list.model.repository.WorkspaceRequestError
import com.finalexam.to_do_list.model.repository.businessCode
import com.finalexam.to_do_list.model.repository.isTransientWorkspaceFailure
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
