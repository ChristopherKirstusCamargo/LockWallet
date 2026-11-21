package com.lockwallet.custody.local.api

import com.lockwallet.core.crypto.lwrp.LwrpCipher
import com.lockwallet.core.crypto.lwrp.LwrpPasswordError
import com.lockwallet.core.crypto.lwrp.LwrpPasswordException

/** Recovery-package failures visible to the UI. OPEN_FAILED covers wrong password ≡ corruption ≡ tampering. */
class RecoveryException(val reason: Reason) : Exception("recovery: $reason") {
    enum class Reason { PASSWORD_TOO_SHORT, PASSWORD_TOO_LONG, PASSWORD_INVALID, OPEN_FAILED, NOT_A_PACKAGE }

    companion object {
        internal fun of(e: LwrpPasswordException) = RecoveryException(
            when (e.error) {
                LwrpPasswordError.TOO_SHORT -> Reason.PASSWORD_TOO_SHORT
                LwrpPasswordError.TOO_LONG -> Reason.PASSWORD_TOO_LONG
                LwrpPasswordError.INVALID_UNICODE -> Reason.PASSWORD_INVALID
            },
        )
    }
}

object RecoveryPasswords {
    const val MIN_BYTES = LwrpCipher.MIN_EXPORT_PASSWORD_BYTES
    const val MAX_BYTES = LwrpCipher.MAX_PASSWORD_BYTES
    /** 8 random BIP-39 words (88 bits). Caller wipes the result. */
    fun generate(): CharArray = LwrpCipher.generatePassword()
}
