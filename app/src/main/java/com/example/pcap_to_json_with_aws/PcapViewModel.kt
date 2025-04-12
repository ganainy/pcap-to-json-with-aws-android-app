package com.example.pcap_to_json.ui // Or your ui package

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pcap_to_json_with_aws.JobStatusResponse
import com.example.pcap_to_json_with_aws.PcapProcessingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.buffer // Import buffer extension function
import okio.BufferedSink
import okio.ForwardingSink
import okio.IOException // Use okio.IOException if needed, or java.io.IOException
import okio.Sink
import java.io.File
import java.io.FileOutputStream
// import java.io.IOException // Use okio's or java's, be consistent
import kotlin.time.Duration.Companion.seconds

class PcapViewModel(application: Application) : AndroidViewModel(application) {

    // --- API Configuration ---
    // IMPORTANT: Replace with your server's actual URL (public IP or domain)
    private val SERVER_UPLOAD_URL = "https://13.61.79.152:3100/upload" // Node API port
    private val SERVER_STATUS_URL_BASE = "https://13.61.79.152:3100/status/" // Node API port

    private val _processingState =
        MutableStateFlow<PcapProcessingState>(PcapProcessingState.Idle)
    val processingState: StateFlow<PcapProcessingState> = _processingState.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private var pollingJob: Job? = null

    override fun onCleared() {
        pollingJob?.cancel("ViewModel cleared")
        super.onCleared()
    }

    // --- Upload Function ---
    fun uploadPcapToServer(uri: Uri) {
        pollingJob?.cancel("Starting new upload")
        _processingState.value = PcapProcessingState.Uploading(0f)

        viewModelScope.launch(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                tempFile = copyUriToTempFile(uri, getApplication())
                if (tempFile == null) throw IOException("Failed to create temp file.")

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "pcap_file",
                        tempFile.name,
                        tempFile.asRequestBody("application/vnd.tcpdump.pcap".toMediaTypeOrNull()
                            ?: "application/octet-stream".toMediaTypeOrNull())
                    )
                    .build()

                val countingRequestBody = CountingRequestBody(requestBody) { bytesWritten, contentLength ->
                    if (contentLength > 0) {
                        val progress = (bytesWritten.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                        viewModelScope.launch(Dispatchers.Main) {
                            if (_processingState.value is PcapProcessingState.Uploading) {
                                _processingState.value = PcapProcessingState.Uploading(progress)
                            }
                        }
                    }
                }

                val request = Request.Builder().url(SERVER_UPLOAD_URL)
                    // .post(countingRequestBody)
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Upload failed: ${response.code} ${response.message} - ${response.body?.string()?.take(200)}")
                    }

                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val result = jsonParser.decodeFromString<Map<String, String>>(responseBody)
                        val jobId = result["job_id"]
                        if (jobId != null) {
                            Log.i("PcapViewModel", "Upload successful, Job ID: $jobId")
                            startPollingForJob(jobId) // Start Polling after successful upload
                        } else {
                            throw IOException("Server response missing job_id.")
                        }
                    } else {
                        throw IOException("Server returned empty response body.")
                    }
                }
            } catch (e: Exception) {
                // Catch CancellationException separately if needed
                if (e is CancellationException) throw e // Re-throw cancellation
                Log.e("PcapViewModel", "Upload/Processing initiation failed", e)
                withContext(Dispatchers.Main) {
                    _processingState.value = PcapProcessingState.Error("Upload failed: ${e.message}")
                }
            } finally {
                tempFile?.delete()
            }
        }
    }

    // --- Polling Logic ---
    private fun startPollingForJob(jobId: String, initialDelayMillis: Long = 3000L) {
        pollingJob?.cancel("Starting new poll")
        var attempt = 0
        val maxAttempts = 30
        val pollInterval = 5.seconds

        _processingState.value = PcapProcessingState.Processing(jobId)
        Log.i("PcapViewModel", "Starting polling for job: $jobId")

        pollingJob = viewModelScope.launch(Dispatchers.IO) pollLoop@ { // Add label
            delay(initialDelayMillis)

            while (isActive && attempt < maxAttempts) { // Check isActive from coroutine scope
                attempt++
                Log.d("PcapViewModel", "Polling attempt $attempt for job $jobId")
                withContext(Dispatchers.Main) { // Switch to Main for state update
                    val currentState = _processingState.value
                    if ((currentState is PcapProcessingState.Processing && currentState.jobId == jobId) ||
                        (currentState is PcapProcessingState.Polling && currentState.jobId == jobId)) {
                        _processingState.value = PcapProcessingState.Polling(jobId, attempt)
                    } else {
                        Log.w("PcapViewModel", "Polling $jobId stopping: State changed externally.")
                        this@pollLoop.cancel("State changed externally") // Cancel the coroutine using the label
                        return@withContext
                    }
                }

                try {
                    val statusUrl = "$SERVER_STATUS_URL_BASE$jobId"
                    val request = Request.Builder().url(statusUrl).get().build()

                    httpClient.newCall(request).execute().use checkStatus@{ response -> // Add label to use block
                        if (response.code == 404) {
                            Log.e("PcapViewModel", "Polling $jobId: Job ID not found on server (404)")
                            throw IOException("Job ID not found on server.") // Throw to enter catch block below
                        }
                        if (!response.isSuccessful) {
                            Log.w("PcapViewModel", "Polling $jobId: Status check failed with code ${response.code}")
                            // Decide action on non-404 error: Continue polling or fail?
                            // Let's continue for now, wait for next interval.
                            return@checkStatus // Like 'continue' for the current attempt/use block
                        }

                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            val statusResponse = jsonParser.decodeFromString<JobStatusResponse>(responseBody)
                            if (handleJobUpdate(jobId, statusResponse)) { // handleJobUpdate will update state
                                this@pollLoop.cancel("Job finished") // Cancel the coroutine (like break)
                                return@checkStatus
                            }
                            // If still processing, loop will continue after delay
                        } else {
                            Log.w("PcapViewModel", "Polling $jobId: Empty status response body")
                            // Continue polling? Let loop continue.
                        }
                    } // End response.use
                } catch (e: Exception) {
                    if (e is CancellationException) throw e // Re-throw cancellation
                    Log.e("PcapViewModel", "Polling $jobId: Error during status check", e)
                    withContext(Dispatchers.Main){
                        _processingState.value = PcapProcessingState.Error("Error checking status: ${e.message}", jobId)
                    }
                    this@pollLoop.cancel("Polling error") // Cancel the coroutine (like break)
                    return@pollLoop // Exit launch block as well
                }

                delay(pollInterval) // Wait before next poll
            } // End while loop

            // Handle timeout if loop finished due to maxAttempts and wasn't cancelled
            if (isActive && attempt >= maxAttempts) { // Check isActive to ensure it wasn't cancelled
                Log.w("PcapViewModel", "Polling $jobId: Max attempts reached.")
                withContext(Dispatchers.Main) {
                    val currentState = _processingState.value
                    if ((currentState is PcapProcessingState.Polling && currentState.jobId == jobId) ||
                        (currentState is PcapProcessingState.Processing && currentState.jobId == jobId)) {
                        _processingState.value = PcapProcessingState.Error("Processing timed out.", jobId)
                    }
                }
            }
        } // End coroutine launch
    }

    // --- Handle Status Update ---
    // Returns true if processing is finished (completed/failed), false otherwise
    private fun handleJobUpdate(jobId: String, statusResponse: JobStatusResponse): Boolean {
        Log.d("PcapViewModel", "Handling update for job $jobId: status=${statusResponse.status}")
        var isFinished = false
        viewModelScope.launch(Dispatchers.Main) { // Ensure state updates on Main
            val currentState = _processingState.value
            val isCurrentJob = (currentState is PcapProcessingState.Processing && currentState.jobId == jobId) ||
                    (currentState is PcapProcessingState.Polling && currentState.jobId == jobId)

            // Only process update if it's for the job we are currently tracking
            if (!isCurrentJob) {
                Log.w("PcapViewModel", "Ignoring status update for $jobId, current state is different.")
                return@launch
            }

            when (statusResponse.status) {
                "completed" -> {
                    statusResponse.url?.let { url ->
                        Log.i("PcapViewModel", "Job $jobId completed. State set to DownloadingJson.")
                        _processingState.value = PcapProcessingState.DownloadingJson(jobId, url)
                        // Consider triggering download automatically, or let UI do it
                         downloadJsonResult(jobId, url)
                    } ?: run {
                        Log.e("PcapViewModel", "Job $jobId completed but URL missing!")
                        _processingState.value = PcapProcessingState.Error("Result URL missing.", jobId)
                    }
                    isFinished = true
                }
                "failed" -> {
                    Log.e("PcapViewModel", "Job $jobId failed: ${statusResponse.error}")
                    _processingState.value = PcapProcessingState.Error(
                        "Processing failed: ${statusResponse.error ?: "Unknown server error"}", jobId
                    )
                    isFinished = true
                }
                "processing" -> {
                    // No state change needed, polling will continue
                    isFinished = false
                }
                else -> {
                    Log.w("PcapViewModel", "Job $jobId: Unknown status: ${statusResponse.status}")
                    isFinished = false // Treat unknown as not finished
                }
            }
        }
        return isFinished
    }


    // --- Download Function ---
    fun downloadJsonResult(jobId: String, url: String) {
        // This function can be called by the UI when state is DownloadingJson
        // Or called automatically by handleJobUpdate if desired
        viewModelScope.launch(Dispatchers.Main) { // Update state on main thread first
            // Verify we are still in the correct state for this job before starting download
            val currentState = _processingState.value
            if (currentState is PcapProcessingState.DownloadingJson && currentState.jobId == jobId) {
                Log.i("PcapViewModel", "Attempting download for job $jobId from $url")
            } else {
                Log.w("PcapViewModel", "Download requested for $jobId but current state is $currentState. Ignoring.")
                return@launch
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Download failed: ${response.code} ${response.message} - ${response.body?.string()?.take(200)}")

                    val jsonContent = response.body?.string()
                    if (jsonContent != null) {
                        Log.i("PcapViewModel", "JSON for job $jobId downloaded.")
                        withContext(Dispatchers.Main) {
                            // Final state update
                            _processingState.value = PcapProcessingState.Success(jobId, jsonContent)
                        }
                    } else {
                        throw IOException("Response body was null")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("PcapViewModel", "Failed to download JSON result for job $jobId", e)
                withContext(Dispatchers.Main) {
                    _processingState.value = PcapProcessingState.Error("Download failed: ${e.message}", jobId)
                }
            }
        }
    }


    fun downloadJsonResultFixedUrl() {
        val jobId = "d118fd2e-12bf-4e55-a3a9-491fd7d7009a" // Replace with actual job ID
        val url = "https://pcap-results.ganainy.online/results/d118fd2e-12bf-4e55-a3a9-491fd7d7009a.json" // Replace with actual URL
        // This function can be called by the UI when state is DownloadingJson
        // Or called automatically by handleJobUpdate if desired
        viewModelScope.launch(Dispatchers.Main) { // Update state on main thread first
            // Verify we are still in the correct state for this job before starting download
            val currentState = _processingState.value
            if (currentState is PcapProcessingState.DownloadingJson && currentState.jobId == jobId) {
                Log.i("PcapViewModel", "Attempting download for job $jobId from $url")
            } else {
                Log.w("PcapViewModel", "Download requested for $jobId but current state is $currentState. Ignoring.")
                return@launch
            }
        }

        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS }

        val testHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .cache(null) // Explicitly disable caching for this client
            .build()
        Log.d("PcapViewModel", "[Fixed URL Test] Using non-cached OkHttpClient")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                testHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Download failed: ${response.code} ${response.message} - ${response.body?.string()?.take(200)}")

                    val jsonContent = response.body?.string()
                    if (jsonContent != null) {
                        Log.i("PcapViewModel", "JSON for job $jobId downloaded.")
                        withContext(Dispatchers.Main) {
                            // Final state update
                            _processingState.value = PcapProcessingState.Success(jobId, jsonContent)
                        }
                    } else {
                        throw IOException("Response body was null")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("PcapViewModel", "Failed to download JSON result for job $jobId", e)
                withContext(Dispatchers.Main) {
                    _processingState.value = PcapProcessingState.Error("Download failed: ${e.message}", jobId)
                }
            }
        }
    }

    // --- Helper: Copy Uri to Temp File ---
    private suspend fun copyUriToTempFile(uri: Uri, context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val tempFile = File.createTempFile("upload_", ".pcap", context.cacheDir)
            FileOutputStream(tempFile).use { output -> inputStream.use { input -> input.copyTo(output) } }
            Log.d("PcapViewModel","Copied Uri to temp file: ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            Log.e("PcapViewModel", "Error copying Uri to temp file", e)
            null
        }
    }
}

// --- OkHttp Progress Listener Helper ---
class CountingRequestBody(
    private val delegate: RequestBody,
    private val listener: (bytesWritten: Long, contentLength: Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = try {
        delegate.contentLength()
    } catch (e: java.io.IOException) { // Specify java.io.IOException if needed
        -1
    }

    override fun writeTo(sink: BufferedSink) { // Use BufferedSink directly
        val countingSink = CountingSink(sink, contentLength(), listener)
        // No need to buffer again if sink is already buffered, write delegate directly
        delegate.writeTo(countingSink.buffer()) // Use buffer() extension on the CountingSink
    }

    private class CountingSink(
        delegate: Sink, // Use base Sink
        private val contentLength: Long,
        private val listener: (bytesWritten: Long, contentLength: Long) -> Unit
    ) : ForwardingSink(delegate) { // ForwardingSink takes Sink
        private var bytesWritten = 0L

        override fun write(source: okio.Buffer, byteCount: Long) {
            super.write(source, byteCount)
            bytesWritten += byteCount
            if (contentLength > 0) {
                listener(bytesWritten, contentLength)
            }
        }
    }
}