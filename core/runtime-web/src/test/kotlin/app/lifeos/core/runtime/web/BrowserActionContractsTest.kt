package app.lifeos.core.runtime.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class BrowserActionContractsTest {
    @Test
    fun plan_identity_does_not_become_a_stable_hash_of_sensitive_text() {
        val origin = WebResourceIdentity.parse("https://example.com/").origin
        val first = BrowserActionPlan.create(
            operationKey = BrowserActionOperationKey("login-flow"),
            origin = origin,
            steps = listOf(
                BrowserActionContracts.inputText(
                    0,
                    BrowserElementSelector("#password"),
                    BrowserSensitiveText.create("secret-one"),
                )
            ),
        )
        val changedSecret = BrowserActionPlan.create(
            operationKey = BrowserActionOperationKey("login-flow"),
            origin = origin,
            steps = listOf(
                BrowserActionContracts.inputText(
                    0,
                    BrowserElementSelector("#password"),
                    BrowserSensitiveText.create("secret-two"),
                )
            ),
        )

        assertEquals(first.id, changedSecret.id)
        assertNotEquals(first.fingerprint(), changedSecret.fingerprint())
        assertFalse(first.toString().contains("secret-one"))
        assertFalse(changedSecret.toString().contains("secret-two"))
    }

    @Test
    fun sensitive_text_is_redacted() {
        val text = BrowserSensitiveText.create("private-value")

        assertEquals("private-value", text.utf8())
        assertFalse(text.toString().contains("private-value"))
    }

    @Test
    fun steps_must_be_contiguous_and_canonical() {
        val origin = WebResourceIdentity.parse("https://example.com/").origin

        val canonical = BrowserActionPlan.create(
            operationKey = BrowserActionOperationKey("canonical"),
            origin = origin,
            steps = listOf(
                BrowserActionContracts.click(0, BrowserElementSelector("#a")),
                BrowserActionContracts.submit(1, BrowserElementSelector("form")),
            ),
        )
        assertEquals(listOf(0, 1), canonical.steps.map { it.ordinal })

        assertFailsWith<IllegalArgumentException> {
            BrowserActionPlan.create(
                operationKey = BrowserActionOperationKey("gap"),
                origin = origin,
                steps = listOf(
                    BrowserActionContracts.click(0, BrowserElementSelector("#a")),
                    BrowserActionContracts.submit(2, BrowserElementSelector("form")),
                ),
            )
        }
    }

    @Test
    fun cross_origin_navigation_download_and_upload_fail_closed() {
        val origin = WebResourceIdentity.parse("https://example.com/").origin
        val other = WebResourceIdentity.parse("https://other.example/resource")
        val download = WebDownloadRequest.create(
            WebAcquisitionRequest.create(
                resource = other,
                acceptedMediaTypes = listOf("*/*"),
            )
        )
        val upload = WebUploadRequest.create(
            operationKey = WebUploadOperationKey("cross-origin"),
            target = other,
            method = WebUploadMethod.POST,
            contentType = "application/octet-stream",
            payload = WebUploadPayload.create("x".encodeToByteArray()),
        )

        assertFailsWith<IllegalArgumentException> {
            BrowserActionPlan.create(
                operationKey = BrowserActionOperationKey("nav"),
                origin = origin,
                steps = listOf(BrowserActionContracts.navigate(0, other)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserActionPlan.create(
                operationKey = BrowserActionOperationKey("download"),
                origin = origin,
                steps = listOf(BrowserActionContracts.download(0, download)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserActionPlan.create(
                operationKey = BrowserActionOperationKey("upload"),
                origin = origin,
                steps = listOf(BrowserActionContracts.upload(0, upload)),
            )
        }
    }

    @Test
    fun valid_same_origin_plan_links_b402_and_b403_without_granting_authority() {
        val resource = WebResourceIdentity.parse("https://example.com/start")
        val origin = resource.origin
        val session = WebSessionSnapshot.create(
            origin = origin,
            entries = listOf(
                WebSessionEntry(
                    kind = WebSessionEntryKind.COOKIE,
                    name = "sid",
                    secret = WebSessionSecret.fromUtf8("opaque-cookie"),
                )
            ),
        )
        val download = WebDownloadRequest.create(
            WebAcquisitionRequest.create(
                resource = WebResourceIdentity.parse("https://example.com/file"),
                acceptedMediaTypes = listOf("*/*"),
            )
        )
        val upload = WebUploadRequest.create(
            operationKey = WebUploadOperationKey("upload"),
            target = WebResourceIdentity.parse("https://example.com/api"),
            method = WebUploadMethod.PUT,
            contentType = "application/octet-stream",
            payload = WebUploadPayload.create("payload".encodeToByteArray()),
        )
        val plan = BrowserActionPlan.create(
            operationKey = BrowserActionOperationKey("flow"),
            origin = origin,
            sessionRevisionId = session.revisionId,
            steps = listOf(
                BrowserActionContracts.navigate(0, resource),
                BrowserActionContracts.click(1, BrowserElementSelector("#open")),
                BrowserActionContracts.inputText(
                    2,
                    BrowserElementSelector("#value"),
                    BrowserSensitiveText.create("private"),
                ),
                BrowserActionContracts.submit(3, BrowserElementSelector("form")),
                BrowserActionContracts.download(4, download),
                BrowserActionContracts.upload(5, upload),
            ),
        )

        assertEquals(6, plan.steps.size)
        assertEquals(session.revisionId, plan.sessionRevisionId)
        assertFalse(plan.executionAuthority)
        assertFalse(plan.networkAuthority)
        assertFalse(plan.formSubmissionAuthority)
        assertFalse(plan.fileWriteAuthority)
        assertFalse(plan.permissionAuthority)
        assertFalse(plan.toString().contains("private"))
        assertFalse(plan.toString().contains("opaque-cookie"))
    }

    @Test
    fun malformed_step_shapes_fail_closed() {
        assertFailsWith<IllegalArgumentException> {
            BrowserActionStep(
                ordinal = 0,
                kind = BrowserActionKind.CLICK,
                selector = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserActionStep(
                ordinal = 0,
                kind = BrowserActionKind.NAVIGATE,
                targetResource = WebResourceIdentity.parse("https://example.com/"),
                selector = BrowserElementSelector("#illegal"),
            )
        }
    }
}
