package com.finalexam.to_do_list.model.repository

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object WorkspaceTiming {
    private val uuid = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    private val diagnosticCode = Regex("[A-Z][A-Z0-9_]{0,63}")

    fun elapsedMillis(startNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    fun record(
        stage: String,
        operationId: String? = null,
        categoryId: String? = null,
        serverSeq: Long? = null,
        elapsedMillis: Long? = null,
        code: String? = null
    ) {
        val fields = buildList {
            add("stage=$stage")
            operationId?.takeIf(uuid::matches)?.let { add("operation_id=$it") }
            categoryId?.let { add("category_hash=${categoryHash(it)}") }
            serverSeq?.let { add("server_seq=$it") }
            elapsedMillis?.let { add("elapsed_ms=$it") }
            code?.takeIf(diagnosticCode::matches)?.let { add("code=$it") }
        }
        Log.i("TodoListSync", fields.joinToString(" "))
    }

    private fun categoryHash(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
