# PCAP to JSON Converter App

This Android app allows users to select a PCAP (packet capture) file, upload it to a remote server for processing, and then download the resulting JSON data.  It leverages Kotlin, Jetpack Compose for the UI, and OkHttp for network requests.  The server-side component is not included in this repository; this app is the client-side component.

The server side component and the steps to set it up can be found in this repo:
https://github.com/ganainy/aws-server-for-pcap-to-json-conversion

## Setup and Usage

1.  **Clone the repository:**  `git clone <repository_url>`
2.  **Configure Server URL:**  **IMPORTANT:** Modify the `SERVER_UPLOAD_URL` and `SERVER_STATUS_URL_BASE` constants in `PcapViewModel.kt` to point to *your* server's address.

