package it.liberofiles.app

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveAuthorizationTest {
    @Test fun developerErrorExplainsClientConfigurationWithoutLeakingGoogleMessage() {
        val message = DriveAccounts.authorizationError(ApiException(Status(10, "private-account@example.com token=secret")))
        assertTrue(message.contains("client OAuth Android"))
        assertTrue(message.contains("Codice Google: 10"))
        assertFalse(message.contains("private-account"))
        assertFalse(message.contains("secret"))
    }
    @Test fun missingResultDoesNotClaimUserCancelled() {
        val message = DriveAccounts.authorizationError(null)
        assertTrue(message.contains("non ha completato"))
        assertFalse(message.contains("annullato"))
    }
    @Test fun networkFailureIsDistinguishedFromInterruptedConsent() {
        assertTrue(DriveAccounts.authorizationError(ApiException(Status(7))).contains("connessione"))
        assertTrue(DriveAccounts.authorizationError(ApiException(Status(16))).contains("interrotto"))
    }
}
