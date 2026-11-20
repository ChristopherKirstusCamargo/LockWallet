package com.lockwallet.app.connect

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.solana.mobilewalletadapter.common.ProtocolContract
import com.solana.mobilewalletadapter.walletlib.association.AssociationUri
import com.solana.mobilewalletadapter.walletlib.association.LocalAssociationUri
import com.solana.mobilewalletadapter.walletlib.authorization.AuthIssuerConfig
import com.solana.mobilewalletadapter.walletlib.protocol.MobileWalletAdapterConfig
import com.solana.mobilewalletadapter.walletlib.scenario.AuthorizeRequest
import com.solana.mobilewalletadapter.walletlib.scenario.AuthorizedAccount
import com.solana.mobilewalletadapter.walletlib.scenario.DeauthorizedEvent
import com.solana.mobilewalletadapter.walletlib.scenario.LocalScenario
import com.solana.mobilewalletadapter.walletlib.scenario.ReauthorizeRequest
import com.solana.mobilewalletadapter.walletlib.scenario.Scenario
import com.solana.mobilewalletadapter.walletlib.scenario.SignAndSendTransactionsRequest
import com.solana.mobilewalletadapter.walletlib.scenario.SignInResult
import com.solana.mobilewalletadapter.walletlib.scenario.SignMessagesRequest
import com.solana.mobilewalletadapter.walletlib.scenario.SignPayloadsRequest
import com.solana.mobilewalletadapter.walletlib.scenario.SignTransactionsRequest

/**
 * Glue between the official MWA 2.x walletlib (wallet side) and [ConnectModel]. Only LOCAL association
 * (dApp on this device over localhost) is accepted; remote/reflector association is refused (spec §23.4:
 * the reflector is an adversary — not offered in v1). Walletlib callbacks arrive on its own thread and are
 * posted to the main thread. No deprecated methods (clone_authorization) are offered.
 */
class MwaBridge(private val model: ConnectModel, private val callingPackage: String?) {
    private val main = Handler(Looper.getMainLooper())
    private var scenario: Scenario? = null

    enum class Start { OK, NOT_MWA, REMOTE_REFUSED }

    fun start(context: Context, uri: Uri?): Start {
        if (uri == null || uri.scheme != "solana-wallet") return Start.NOT_MWA
        val assoc = try { AssociationUri.parse(uri) } catch (_: RuntimeException) { null } ?: return Start.NOT_MWA
        if (assoc !is LocalAssociationUri) return Start.REMOTE_REFUSED
        val config = MobileWalletAdapterConfig(
            MAX_PAYLOADS, MAX_PAYLOADS,
            arrayOf<Any>(MobileWalletAdapterConfig.LEGACY_TRANSACTION_VERSION),
            NO_CONNECTION_WARNING_MS,
            arrayOf(ProtocolContract.FEATURE_ID_SIGN_TRANSACTIONS, ProtocolContract.FEATURE_ID_SIGN_IN_WITH_SOLANA),
        )
        // Short authorization; reauthorization is still decided by ConnectModel against our own registry.
        val auth = AuthIssuerConfig("Lock Wallet", 1, 15L * 60 * 1000, ConnectRegistry.VALIDITY_MS, 0L)
        val s = assoc.createScenario(context.applicationContext, config, auth, callbacks)
        scenario = s
        model.live = true
        s.start()
        return Start.OK
    }

    fun close() { scenario?.close(); scenario = null }

    private fun post(block: () -> Unit) { main.post { runCatching(block) } }

    private fun identity(name: String?, uri: Uri?) = DappIdentity(name?.take(64) ?: "", uri?.toString()?.take(200), callingPackage)

    private val callbacks = object : LocalScenario.Callbacks {
        override fun onScenarioReady() {}
        override fun onScenarioServingClients() {}
        override fun onScenarioServingComplete() { post { close() } }
        override fun onScenarioComplete() {}
        override fun onScenarioError() { post { model.ended() } }
        override fun onScenarioTeardownComplete() { post { model.ended() } }
        override fun onLowPowerAndNoConnection() {}

        override fun onAuthorizeRequest(r: AuthorizeRequest) = post {
            val chain = r.chain ?: r.cluster
            val p = r.signInPayload
            val siws = p?.let { pl ->
                SiwsRequest(pl.domain, pl.statement, pl.uri?.toString(), pl.chainId, pl.address, pl.issuedAt, pl.expirationTime, pl.nonce) { addr -> pl.prepareMessage(addr) }
            }
            model.enqueue(AuthorizeRequestModel(identity(r.identityName, r.identityUri), chain, ConnectModel.clusterOf(chain), siws, object : AuthorizeAnswer {
                override fun approve(address: ByteArray, label: String, scope: ByteArray, siwsMessage: ByteArray?, siwsSignature: ByteArray?) {
                    val account = AuthorizedAccount(address, label.take(32), null, null, null)
                    val sir = if (siwsMessage != null && siwsSignature != null) SignInResult(address, siwsMessage, siwsSignature, "ed25519") else null
                    r.completeWithAuthorize(arrayOf(account), null, scope, sir)
                }
                override fun decline() = r.completeWithDecline()
                override fun clusterNotSupported() = r.completeWithClusterNotSupported()
            }))
        }

        override fun onReauthorizeRequest(r: ReauthorizeRequest) = post {
            if (model.reauthorize(r.authorizationScope, identity(r.identityName, r.identityUri), r.chain ?: r.cluster)) r.completeWithReauthorize()
            else r.completeWithDecline()
        }

        override fun onSignTransactionsRequest(r: SignTransactionsRequest) = post { sign(r, SignKind.TRANSACTIONS) }
        override fun onSignMessagesRequest(r: SignMessagesRequest) = post { sign(r, SignKind.MESSAGES) }

        override fun onSignAndSendTransactionsRequest(r: SignAndSendTransactionsRequest) = post {
            val dapp = identity(r.identityName, r.identityUri)
            val s = model.sessionFor(r.authorizationScope, r.publicKey, r.chain ?: r.cluster, dapp)
            if (s == null) { r.completeWithAuthorizationNotValid(); return@post }
            // One transaction per request: the coordinator never has two unresolved sends per wallet (no double send).
            if (r.payloads.size != 1) { r.completeWithTooManyPayloads(); return@post }
            model.enqueue(SignRequestModel(dapp, s, SignKind.SIGN_AND_SEND, r.payloads.toList(), object : SignAnswer {
                override fun signedPayloads(p: Array<ByteArray>) = r.completeWithDecline()
                override fun signatures(s: Array<ByteArray>) = r.completeWithSignatures(s)
                override fun notSubmitted(s: Array<ByteArray>) = r.completeWithNotSubmitted(s)
                override fun decline() = r.completeWithDecline()
                override fun invalid(valid: BooleanArray) = r.completeWithInvalidSignatures(valid)
                override fun authorizationNotValid() = r.completeWithAuthorizationNotValid()
                override fun tooMany() = r.completeWithTooManyPayloads()
            }))
        }

        override fun onDeauthorizedEvent(e: DeauthorizedEvent) = post { model.deauthorized(e.authorizationScope); e.complete() }
    }

    private fun sign(r: SignPayloadsRequest, kind: SignKind) {
        val dapp = identity(r.identityName, r.identityUri)
        val s = model.sessionFor(r.authorizationScope, r.authorizedPublicKey, r.chain ?: r.cluster, dapp)
        if (s == null) { r.completeWithAuthorizationNotValid(); return }
        if (r.payloads.isEmpty() || r.payloads.size > MAX_PAYLOADS) { r.completeWithTooManyPayloads(); return }
        model.enqueue(SignRequestModel(dapp, s, kind, r.payloads.toList(), object : SignAnswer {
            override fun signedPayloads(p: Array<ByteArray>) = r.completeWithSignedPayloads(p)
            override fun signatures(s: Array<ByteArray>) = r.completeWithDecline()
            override fun notSubmitted(s: Array<ByteArray>) = r.completeWithDecline()
            override fun decline() = r.completeWithDecline()
            override fun invalid(valid: BooleanArray) = r.completeWithInvalidPayloads(valid)
            override fun authorizationNotValid() = r.completeWithAuthorizationNotValid()
            override fun tooMany() = r.completeWithTooManyPayloads()
        }))
    }

    companion object {
        const val MAX_PAYLOADS = 1
        const val NO_CONNECTION_WARNING_MS = 3_000L
        /** Mainnet identifiers come from the library constants (never typed here). */
        val MAINNET_IDS = setOf(ProtocolContract.CHAIN_SOLANA_MAINNET, ProtocolContract.CLUSTER_MAINNET_BETA)
    }
}
