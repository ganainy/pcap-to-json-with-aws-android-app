package com.example.pcap_to_json_with_aws
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.pcap_to_json.ui.PcapViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PcapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

// --- Main Screen Composable ---
@Composable
fun MainScreen(viewModel: PcapViewModel) {

    val pcapFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                Log.d("MainScreen", "PCAP file selected: $uri")
                viewModel.uploadPcapToServer(uri) // Call ViewModel upload
            } else {
                Log.w("MainScreen", "No PCAP file selected")
            }
        }
    )

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(onClick = { pcapFilePickerLauncher.launch(arrayOf("application/vnd.tcpdump.pcap", "application/octet-stream", "*/*")) }) { // More specific MIME types + fallback
                Text("Select PCAP File and Upload")
            }

            Button(onClick = { viewModel.downloadJsonResultFixedUrl() }) { Text("Testing: Download fixed pcap") }


            Spacer(modifier = Modifier.height(24.dp))

            // --- Result Display Area ---
            PcapResultDisplay(viewModel = viewModel)
        }
    }
}


// --- Result Display Composable ---
@Composable
fun PcapResultDisplay(viewModel: PcapViewModel) {
    val processingState by viewModel.processingState.collectAsState() // Observe the state

    // Use a Box to allow content to potentially overlay or fill space better
    Box(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        // Display Processing State based on PcapProcessingState
        when (val state = processingState) {
            is PcapProcessingState.Idle -> {
                Text("Select a PCAP file to begin.", textAlign = TextAlign.Center)
            }
            is PcapProcessingState.Uploading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(
                        progress = { state.progress }, // Updated for Material 3
                        modifier = Modifier.fillMaxWidth(0.8f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Uploading: ${(state.progress * 100).toInt()}%")
                }
            }
            is PcapProcessingState.Processing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Processing on server (Job: ${state.jobId.take(8)}...)") // Show part of Job ID
                }
            }
            is PcapProcessingState.Polling -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Checking status... (Attempt ${state.attempt}, Job: ${state.jobId.take(8)}...)")
                }
            }
            is PcapProcessingState.DownloadingJson -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Downloading result...")
                }
            }
            is PcapProcessingState.Success -> {
                Column(modifier = Modifier.fillMaxSize()) { // Column fills the Box
                    Text(
                        "Processing Successful!",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Scrollable TextField for JSON
                    OutlinedTextField(
                        value = state.jsonContent,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Result JSON (Job: ${state.jobId.take(8)}...)") },
                        modifier = Modifier
                            .weight(1f) // TextField takes remaining space in Column
                            .fillMaxWidth()
                    )
                }
            }
            is PcapProcessingState.Error -> {
                Text(
                    "Error: ${state.message}${state.jobId?.let { " (Job: ${it.take(8)}...)" } ?: ""}",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// --- Preview ---
@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
        // Previewing state is best done by mocking the state directly
        // in the PcapResultDisplay for easier visualization.
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { /* */ }) { Text("Select PCAP File and Upload") }
            Spacer(Modifier.height(24.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                // Example previewing the Success state
                val successJson = """
                    [
                      { "packet": 1, "info": "details..." },
                      { "packet": 2, "info": "more details..." }
                    ]
                """.trimIndent()
                Column(modifier = Modifier.fillMaxSize()) {
                    Text("Processing Successful!", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = successJson,
                        onValueChange = {}, readOnly = true, label={Text("Result JSON (Job: abc123def...)")},
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
            }
        }
    }
}