package app.lifeos.core.runtime.web

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class WebTransferRuntimeTest {
    @Test
    fun download_reuses_b392_receipt_and_never_grants_file_write_authority() = runTest {
        val transport = AcquisitionTransport(
            WebAcquisitionTransportResponse(
                statusCode = 200,
                contentType = "application/octet-stream",
                body = "download".encodeToByteArray(),
            )
        )
        val request = WebDownloadRequest.create(
            WebAcquisitionRequest.create(
                resource = WebResourceIdentity.parse("https://example.com/file.bin"),
                acceptedMediaTypes = listOf("application/octet-stream"),
            )
        )

        val result = WebDownloadRuntime(WebAcquisitionRuntime(transport)).download(request)

        assertEquals(WebDownloadOutcome.DOWNLOADED, result.outcome)
        assertContentEquals("download".encodeToByteArray(), result.payload?.bytes())
        assertEquals(result.payload?.sha256, result.acquisitionReceipt.payloadSha256)
        assertFalse(request.networkAuthority)
        assertFalse(request.fileWriteAuthority)
        assertFalse(result.fileWriteAuthority)
        assertFalse(result.permissionAuthority)
        assertFalse(result.executionAuthority)
    }

    @Test
    fun download_preserves_not_modified_and_http_error_without_payload() = runTest {
        val notModified = WebDownloadRuntime(
            WebAcquisitionRuntime(AcquisitionTransport(WebAcquisitionTransportResponse(304)))
        ).download(downloadRequest("https://example.com/a"))
        val failed = WebDownloadRuntime(
            WebAcquisitionRuntime(AcquisitionTransport(WebAcquisitionTransportResponse(503)))
        ).download(downloadRequest("https://example.com/b"))

        assertEquals(WebDownloadOutcome.NOT_MODIFIED, notModified.outcome)
        assertEquals(null, notModified.payload)
        assertEquals(WebDownloadOutcome.ACQUISITION_FAILED, failed.outcome)
        assertEquals(null, failed.payload)
    }

    @Test
    fun upload_payload_is_defensively_copied_and_redacted() {
        val source = "private-body".encodeToByteArray()
        val payload = WebUploadPayload.create(source)
        source.fill(0)

        assertContentEquals("private-body".encodeToByteArray(), payload.bytes())
        assertFalse(payload.toString().contains("private-body"))

        val copy = payload.bytes()
        copy.fill(0)
        assertContentEquals("private-body".encodeToByteArray(), payload.bytes())
    }

    @Test
    fun upload_request_identity_uses_operation_shape_while_fingerprint_binds_payload() {
        val target = WebResourceIdentity.parse("https://example.com/upload")
        val first = WebUploadRequest.create(
            operationKey = WebUploadOperationKey("op-1"),
            target = target,
            method = WebUploadMethod.POST,
            contentType = "application/json",
            payload = WebUploadPayload.create("""{"a":1}""".encodeToByteArray()),
        )
        val changedPayload = WebUploadRequest.create(
            operationKey = WebUploadOperationKey("op-1"),
            target = target,
            method = WebUploadMethod.POST,
            contentType = "application/json",
            payload = WebUploadPayload.create("""{"b":2}""".encodeToByteArray()),
        )

        assertEquals(first.id, changedPayload.id)
        assertNotEquals(first.fingerprint(), changedPayload.fingerprint())
        assertFalse(first.permissionAuthority)
        assertFalse(first.executionAuthority)
    }

    @Test
    fun accepted_upload_emits_exact_receipt_and_bounded_response() = runTest {
        val transport = UploadTransport(
            WebUploadTransportResponse(
                statusCode = 201,
                contentType = "application/json; charset=utf-8",
                body = """{"ok":true}""".encodeToByteArray(),
            )
        )
        val request = uploadRequest()

        val result = WebUploadRuntime(transport).upload(request)

        assertEquals(WebUploadOutcome.ACCEPTED, result.receipt.outcome)
        assertEquals(201, result.receipt.statusCode)
        assertEquals(request.target.id, result.receipt.targetResourceId)
        assertEquals(request.payload.sha256, result.receipt.payloadSha256)
        assertContentEquals("""{"ok":true}""".encodeToByteArray(), result.responseBytes())
        assertEquals(1, transport.requests.size)
        assertContentEquals(request.payload.bytes(), transport.requests.single().body)
        assertFalse(result.receipt.truthAuthority)
        assertFalse(result.receipt.permissionAuthority)
        assertFalse(result.executionAuthority)
    }

    @Test
    fun non_2xx_upload_is_explicit_http_error() = runTest {
        val transport = UploadTransport(
            WebUploadTransportResponse(
                statusCode = 409,
                contentType = "text/plain",
                body = "conflict".encodeToByteArray(),
            )
        )

        val result = WebUploadRuntime(transport).upload(uploadRequest())

        assertEquals(WebUploadOutcome.HTTP_ERROR, result.receipt.outcome)
        assertContentEquals("conflict".encodeToByteArray(), result.responseBytes())
    }

    @Test
    fun oversized_upload_response_fails_closed() = runTest {
        val request = uploadRequest(maxResponseBytes = 4)
        val transport = UploadTransport(
            WebUploadTransportResponse(
                statusCode = 200,
                contentType = "text/plain",
                body = "12345".encodeToByteArray(),
            )
        )

        assertFailsWith<IllegalArgumentException> {
            WebUploadRuntime(transport).upload(request)
        }
    }

    @Test
    fun upload_transport_has_no_redirect_semantics_or_session_secret_attachment() = runTest {
        val request = uploadRequest()
        val transport = UploadTransport(
            WebUploadTransportResponse(
                statusCode = 302,
                contentType = "text/plain",
                body = byteArrayOf(),
            )
        )

        val result = WebUploadRuntime(transport).upload(request)

        assertEquals(WebUploadOutcome.HTTP_ERROR, result.receipt.outcome)
        assertEquals(request.target, transport.requests.single().target)
        assertEquals(request.contentType, transport.requests.single().contentType)
    }

    private fun downloadRequest(url: String): WebDownloadRequest =
        WebDownloadRequest.create(
            WebAcquisitionRequest.create(
                resource = WebResourceIdentity.parse(url),
                acceptedMediaTypes = listOf("*/*"),
            )
        )

    private fun uploadRequest(
        maxResponseBytes: Int = 64 * 1024,
    ): WebUploadRequest =
        WebUploadRequest.create(
            operationKey = WebUploadOperationKey("upload-test"),
            target = WebResourceIdentity.parse("https://example.com/upload"),
            method = WebUploadMethod.PUT,
            contentType = "application/octet-stream",
            payload = WebUploadPayload.create("payload".encodeToByteArray()),
            maxResponseBytes = maxResponseBytes,
        )

    private class AcquisitionTransport(
        private val response: WebAcquisitionTransportResponse,
    ) : WebAcquisitionTransport {
        override suspend fun fetch(
            request: WebAcquisitionTransportRequest,
        ): WebAcquisitionTransportResponse = response
    }

    private class UploadTransport(
        private val response: WebUploadTransportResponse,
    ) : WebUploadTransport {
        val requests = mutableListOf<WebUploadTransportRequest>()

        override suspend fun send(
            request: WebUploadTransportRequest,
        ): WebUploadTransportResponse {
            requests += request
            return response
        }
    }
}
