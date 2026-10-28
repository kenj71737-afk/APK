package com.alad.app.core.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Supports both the ALAD companion API (/separate) and public Gradio Spaces.
 * For Gradio Spaces, the client discovers an audio-input/two-audio-output
 * function from /config, uploads the buffered clip, invokes that function, and
 * downloads the two returned audio files. This is not a mock: it calls the
 * Space's real inference endpoint. Public Spaces can still reject API traffic,
 * sleep, or change their Gradio schema; errors are surfaced to the app.
 */
class HuggingFaceSeparationClient(
    private val baseUrl: String,
    private val bearerToken: String? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(260, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(320, TimeUnit.SECONDS)
        .build()
) {
    data class Stems(val vocalsWav: ByteArray, val instrumentalWav: ByteArray, val model: String)
    private data class GradioFunction(val fnIndex: Int, val apiName: String?, val inputCount: Int)

    suspend fun separate(audioFile: File): Stems = withContext(Dispatchers.IO) {
        require(audioFile.isFile && audioFile.length() > 44) { "Audio file is missing or empty" }
        val base = normalizeBaseUrl(baseUrl)
        require(base.startsWith("https://")) { "Hugging Face Space URL must use HTTPS" }

        // Fast path for the companion Docker API, if the user supplied that URL.
        var customFailure: Exception? = null
        try {
            return@withContext separateCompanionApi(base, audioFile)
        } catch (e: Exception) {
            customFailure = e
        }

        // Public Hugging Face Spaces generally expose a Gradio API, not /separate.
        try {
            return@withContext separateViaGradio(base, audioFile)
        } catch (e: Exception) {
            val detail = e.message ?: "unknown Gradio error"
            throw IOException("Space API call failed. Companion API: ${customFailure?.message ?: "not available"}. Gradio API: $detail", e)
        }
    }

    private fun separateCompanionApi(base: String, audioFile: File): Stems {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = authorized(Request.Builder().url("$base/separate").post(body)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("/separate HTTP ${response.code}: ${text.take(400)}")
            val json = JSONObject(text)
            val vocals = json.optString("vocals_wav_base64")
            val instrumental = json.optString("instrumental_wav_base64")
            if (!json.optBoolean("ok", false) || vocals.isBlank() || instrumental.isBlank()) {
                throw IOException("/separate response has no two WAV stems")
            }
            return Stems(Base64.decode(vocals, Base64.DEFAULT), Base64.decode(instrumental, Base64.DEFAULT), json.optString("model", "Demucs"))
        }
    }

    private fun separateViaGradio(base: String, audioFile: File): Stems {
        val config = getJson("$base/config")
        val function = discoverAudioFunction(config)

        // Gradio 4/5 upload endpoint; some older Spaces use /upload.
        val uploadedPath = try {
            uploadFile("$base/gradio_api/upload", audioFile)
        } catch (_: Exception) {
            uploadFile("$base/upload", audioFile)
        }

        val fileData = JSONObject().put("path", uploadedPath)
            .put("orig_name", audioFile.name)
            .put("meta", JSONObject().put("_type", "gradio.FileData"))
        val data = JSONArray().put(fileData)
        // A few Spaces expose additional inputs. Fill unknown optional inputs with null.
        while (data.length() < function.inputCount) data.put(JSONObject.NULL)

        val result = if (!function.apiName.isNullOrBlank()) {
            try {
                callModernGradio(base, function.apiName, data)
            } catch (modernError: Exception) {
                callLegacyGradio(base, function.fnIndex, data, uploadedPath)
            }
        } else {
            callLegacyGradio(base, function.fnIndex, data, uploadedPath)
        }
        if (result.length() < 2) throw IOException("Gradio returned fewer than two outputs; expected vocals and accompaniment")
        val first = outputBytes(base, result.opt(0))
        val second = outputBytes(base, result.opt(1))
        if (first.isEmpty() || second.isEmpty()) throw IOException("Gradio returned an empty audio stem")

        // The abidlabs demo labels the first output Vocals. The second output is
        // the accompaniment/no-vocals stem. Keep this order for this Space.
        return Stems(first, second, "Gradio music-separation")
    }

    private fun discoverAudioFunction(config: JSONObject): GradioFunction {
        val components = config.optJSONArray("components") ?: JSONArray()
        val types = HashMap<Int, String>()
        for (i in 0 until components.length()) {
            val c = components.optJSONObject(i) ?: continue
            types[c.optInt("id", -1)] = c.optString("type", "")
        }
        val deps = config.optJSONArray("dependencies") ?: JSONArray()
        var fallback: GradioFunction? = null
        for (i in 0 until deps.length()) {
            val dep = deps.optJSONObject(i) ?: continue
            val inputs = dep.optJSONArray("inputs") ?: JSONArray()
            val outputs = dep.optJSONArray("outputs") ?: JSONArray()
            val hasAudioInput = (0 until inputs.length()).any { idx -> types[inputs.optInt(idx)] == "audio" }
            val audioOutputs = (0 until outputs.length()).count { idx -> types[outputs.optInt(idx)] == "audio" }
            if (hasAudioInput && audioOutputs >= 2) {
                val fn = GradioFunction(dep.optInt("id", i), dep.optString("api_name").takeIf { it.isNotBlank() && it != "null" }, inputs.length())
                if (fn.apiName != null) return fn
                fallback = fn
            }
        }
        return fallback ?: throw IOException("Could not find a Gradio function with audio input and two audio outputs in /config")
    }

    private fun uploadFile(url: String, file: File): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("files", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = authorized(Request.Builder().url(url).post(body)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Gradio upload HTTP ${response.code}: ${text.take(250)}")
            val arr = JSONArray(text)
            val first = arr.opt(0)
            val path = when (first) {
                is JSONObject -> first.optString("path", first.optString("name"))
                is String -> first
                else -> ""
            }
            if (path.isBlank()) throw IOException("Gradio upload returned no file path")
            return path
        }
    }

    private fun callModernGradio(base: String, apiName: String, data: JSONArray): JSONArray {
        val endpoint = URLEncoder.encode(apiName, "UTF-8")
        val body = JSONObject().put("data", data).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = authorized(Request.Builder().url("$base/gradio_api/call/$endpoint").post(body)).build()
        val eventId = client.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Gradio call HTTP ${response.code}: ${text.take(300)}")
            JSONObject(text).optString("event_id")
        }
        if (eventId.isBlank()) throw IOException("Gradio call returned no event_id")
        val poll = authorized(Request.Builder().url("$base/gradio_api/call/$endpoint/${URLEncoder.encode(eventId, "UTF-8")}").get()).build()
        client.newCall(poll).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Gradio result HTTP ${response.code}: ${text.take(300)}")
            // Response is server-sent events: event: complete\ndata: [...]
            val dataLine = text.lineSequence().lastOrNull { it.startsWith("data:") }
                ?: throw IOException("Gradio result did not contain data")
            val payload = dataLine.removePrefix("data:").trim()
            if (payload == "null" || payload.contains("error")) throw IOException("Gradio inference failed: ${payload.take(300)}")
            return JSONArray(payload)
        }
    }

    private fun callLegacyGradio(base: String, fnIndex: Int, data: JSONArray, uploadedPath: String): JSONArray {
        val legacyData = JSONArray().put(uploadedPath)
        while (legacyData.length() < data.length()) legacyData.put(JSONObject.NULL)
        val payload = JSONObject().put("data", legacyData).put("fn_index", fnIndex)
        val urls = listOf("$base/api/predict/", "$base/run/predict")
        var last: Exception? = null
        for (url in urls) {
            try {
                val req = authorized(Request.Builder().url(url).post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))).build()
                client.newCall(req).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IOException("$url HTTP ${response.code}: ${text.take(250)}")
                    val json = JSONObject(text)
                    val output = json.optJSONArray("data") ?: throw IOException("Legacy Gradio response has no data array")
                    return output
                }
            } catch (e: Exception) { last = e }
        }
        throw IOException("Legacy Gradio API failed: ${last?.message ?: "unknown error"}")
    }

    private fun outputBytes(base: String, value: Any?): ByteArray {
        if (value is JSONObject) {
            val dataUri = value.optString("data")
            if (dataUri.startsWith("data:") && dataUri.contains(",")) return Base64.decode(dataUri.substringAfter(','), Base64.DEFAULT)
            val url = value.optString("url")
            val path = value.optString("path")
            if (url.startsWith("http://") || url.startsWith("https://")) return download(url)
            if (url.isNotBlank()) return download(if (url.startsWith("/")) "$base$url" else "$base/$url")
            if (path.isNotBlank()) {
                val encoded = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
                val candidates = listOf("$base/gradio_api/file=$encoded", "$base/file=$encoded", "$base/file/$encoded")
                var last: Exception? = null
                for (candidate in candidates) try { return download(candidate) } catch (e: Exception) { last = e }
                throw IOException("Unable to download Gradio output file: ${last?.message}")
            }
        }
        if (value is String) {
            if (value.startsWith("data:") && value.contains(",")) return Base64.decode(value.substringAfter(','), Base64.DEFAULT)
            if (value.startsWith("http://") || value.startsWith("https://")) return download(value)
            if (value.startsWith("/")) return download("$base${filePath(value)}")
        }
        throw IOException("Unsupported Gradio audio output: ${value?.javaClass?.simpleName ?: "null"}")
    }

    private fun filePath(path: String): String = if (path.startsWith("/gradio_api/file=") || path.startsWith("/file=")) path else "/gradio_api/file=$path"

    private fun download(url: String): ByteArray {
        val req = authorized(Request.Builder().url(url).get()).build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Audio download HTTP ${response.code} from ${url.take(140)}")
            return response.body?.bytes() ?: throw IOException("Empty audio download")
        }
    }

    private fun getJson(url: String): JSONObject {
        val req = authorized(Request.Builder().url(url).get()).build()
        client.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("GET $url HTTP ${response.code}: ${text.take(250)}")
            return JSONObject(text)
        }
    }

    private fun authorized(builder: Request.Builder): Request.Builder {
        if (!bearerToken.isNullOrBlank()) builder.header("Authorization", "Bearer ${bearerToken.trim()}")
        return builder
    }

    private fun normalizeBaseUrl(input: String): String {
        var value = input.trim().trimEnd('/')
        // Accept either the HF page URL or its runtime host in Settings.
        val page = Regex("^https://huggingface\\.co/spaces/([^/]+)/([^/]+)$").find(value)
        if (page != null) {
            val owner = page.groupValues[1]
            val space = page.groupValues[2]
            value = "https://$owner-$space.hf.space"
        }
        return value
    }
}
