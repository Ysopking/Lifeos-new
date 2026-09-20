package app.lifeos.core.data.security

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class VaultAssociatedDataTest {
    @Test
    fun `aad is stable across root relocation but changes with physical id`() {
        val rootA = File("/tmp/install-a/vault")
        val rootB = File("/data/install-b/vault")
        val a = VaultAssociatedData.forPath("resource-budget/v2", rootA, rootA.resolve("abc.rbudget"))
        val relocated = VaultAssociatedData.forPath("resource-budget/v2", rootB, rootB.resolve("abc.rbudget"))
        val other = VaultAssociatedData.forPath("resource-budget/v2", rootA, rootA.resolve("def.rbudget"))

        assertContentEquals(a, relocated)
        assertFalse(a.contentEquals(other))
    }
}
