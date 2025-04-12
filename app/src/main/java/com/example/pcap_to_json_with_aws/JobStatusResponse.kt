package com.example.pcap_to_json_with_aws

import kotlinx.serialization.Serializable

@Serializable
data class JobStatusResponse(
    val status: String,
    val url: String? = null, // Nullable if status is not 'completed'
    val error: String? = null // Nullable if status is not 'failed'
)