package app.dustpan.solana

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

class RpcException(message: String, val code: Int? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)

sealed interface SignatureStatus {
    data object Pending : SignatureStatus
    data object Confirmed : SignatureStatus
    data class Failed(val error: String) : SignatureStatus
}

/** A tiny JSON-RPC client. Calls block, so run them on Dispatchers.IO. */
class SolanaRpc(val endpoint: String) {
    private val nextId = AtomicLong(1)

    fun call(method: String, params: JSONArray = JSONArray()): Any? {
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId.getAndIncrement())
            .put("method", method)
            .put("params", params)
        val body = try {
            post(request.toString())
        } catch (e: IOException) {
            throw RpcException("Network error: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw RpcException("Unexpected RPC response: ${body.take(160)}", cause = e)
        }
        json.optJSONObject("error")?.let { error ->
            throw RpcException(error.optString("message", "RPC error"), error.optInt("code"))
        }
        return if (json.isNull("result")) null else json.get("result")
    }

    private fun post(body: String): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 429) {
                throw RpcException("The RPC is rate limiting requests. Wait a moment or set your own RPC URL.", code)
            }
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                throw RpcException(message?.takeIf { it.isNotBlank() } ?: "RPC returned HTTP $code", code)
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    fun getBalance(address: PublicKey): Long =
        (call("getBalance", JSONArray().put(address.toBase58()).put(confirmed())) as JSONObject).getLong("value")

    fun getLatestBlockhash(): String =
        (call("getLatestBlockhash", JSONArray().put(confirmed())) as JSONObject)
            .getJSONObject("value")
            .getString("blockhash")

    fun getMinimumBalanceForRentExemption(space: Int): Long =
        (call("getMinimumBalanceForRentExemption", JSONArray().put(space)) as Number).toLong()

    /** Raw `jsonParsed` token accounts owned by [owner] under one token program. */
    fun getTokenAccountsByOwner(owner: PublicKey, programId: PublicKey): JSONArray {
        val params = JSONArray()
            .put(owner.toBase58())
            .put(JSONObject().put("programId", programId.toBase58()))
            .put(JSONObject().put("encoding", "jsonParsed").put("commitment", COMMITMENT))
        return (call("getTokenAccountsByOwner", params) as JSONObject).getJSONArray("value")
    }

    fun requestAirdrop(address: PublicKey, lamports: Long): String =
        call("requestAirdrop", JSONArray().put(address.toBase58()).put(lamports)) as String

    fun sendTransaction(signedTransaction: ByteArray): String =
        call(
            "sendTransaction",
            JSONArray()
                .put(Base64.getEncoder().encodeToString(signedTransaction))
                .put(JSONObject().put("encoding", "base64").put("preflightCommitment", COMMITMENT)),
        ) as String

    fun getSignatureStatuses(signatures: List<String>): List<SignatureStatus> {
        val value = (call("getSignatureStatuses", JSONArray().put(JSONArray(signatures))) as JSONObject)
            .getJSONArray("value")
        return List(signatures.size) { i ->
            val status = value.optJSONObject(i)
            when {
                status == null -> SignatureStatus.Pending
                !status.isNull("err") -> SignatureStatus.Failed(status.get("err").toString())
                status.optString("confirmationStatus") in DONE -> SignatureStatus.Confirmed
                else -> SignatureStatus.Pending
            }
        }
    }

    /** Polls until every signature is confirmed or failed, or [timeoutMs] runs out. */
    suspend fun awaitSignatures(
        signatures: List<String>,
        timeoutMs: Long = 60_000,
        pollMs: Long = 1_500,
    ): List<SignatureStatus> = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var statuses: List<SignatureStatus> = signatures.map { SignatureStatus.Pending }
        while (true) {
            statuses = runCatching { getSignatureStatuses(signatures) }.getOrDefault(statuses)
            if (statuses.none { it == SignatureStatus.Pending } || System.currentTimeMillis() > deadline) break
            delay(pollMs)
        }
        statuses
    }

    private fun confirmed() = JSONObject().put("commitment", COMMITMENT)

    private companion object {
        const val COMMITMENT = "confirmed"
        val DONE = setOf("confirmed", "finalized")
    }
}
