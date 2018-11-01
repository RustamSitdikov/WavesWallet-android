package com.wavesplatform.wallet.v2.ui.home.quick_action.send.confirmation

import com.arellomobile.mvp.InjectViewState
import com.vicpin.krealmextensions.queryAsSingle
import com.vicpin.krealmextensions.queryFirst
import com.wavesplatform.wallet.App
import com.wavesplatform.wallet.R
import com.wavesplatform.wallet.v1.api.NodeManager
import com.wavesplatform.wallet.v1.crypto.Base58
import com.wavesplatform.wallet.v1.data.rxjava.RxUtil
import com.wavesplatform.wallet.v1.util.MoneyUtil
import com.wavesplatform.wallet.v1.util.PrefsUtil
import com.wavesplatform.wallet.v2.data.Constants
import com.wavesplatform.wallet.v2.data.manager.CoinomatManager
import com.wavesplatform.wallet.v2.data.model.remote.request.TransactionsBroadcastRequest
import com.wavesplatform.wallet.v2.data.model.remote.response.AssetBalance
import com.wavesplatform.wallet.v2.data.model.remote.response.AssetInfo
import com.wavesplatform.wallet.v2.ui.base.presenter.BasePresenter
import com.wavesplatform.wallet.v2.ui.home.profile.address_book.AddressBookUser
import com.wavesplatform.wallet.v2.ui.home.quick_action.send.SendPresenter
import com.wavesplatform.wallet.v2.util.clearAlias
import com.wavesplatform.wallet.v2.util.makeAsAlias
import io.reactivex.Single
import pyxis.uzuki.live.richutilskt.utils.runAsync
import pyxis.uzuki.live.richutilskt.utils.runOnUiThread
import javax.inject.Inject

@InjectViewState
class SendConfirmationPresenter @Inject constructor() : BasePresenter<SendConfirmationView>() {

    @Inject
    lateinit var coinomatManager: CoinomatManager
    private var nodeManager = NodeManager.createInstance(
            App.getAccessManager().getWallet()!!.publicKeyStr)

    var recipient: String? = ""
    var amount: String? = ""
    var attachment: String = ""
    var selectedAsset: AssetBalance? = null
    var assetInfo: AssetInfo? = null
    var moneroPaymentId: String? = null
    var type: SendPresenter.Type = SendPresenter.Type.UNKNOWN


    fun confirmSend() {
        val singed = signTransaction()
        if (singed != null) {
            submitPayment(singed)
        } else {
            viewState.requestPassCode()
        }
    }

    fun getTicker(): String {
        return if (assetInfo == null) {
            ""
        } else if (assetInfo!!.ticker.equals(null)
                || assetInfo!!.ticker.equals("")) {
            assetInfo!!.name
        } else {
            assetInfo!!.ticker ?: ""
        }
    }

    private fun signTransaction(): TransactionsBroadcastRequest? {
        val pk = App.getAccessManager().getWallet()!!.privateKey
        return if (pk != null) {
            val signed = getTxRequest()
            signed.sign(pk)
            signed
        } else {
            null
        }
    }

    private fun submitPayment(signedTransaction: TransactionsBroadcastRequest) {
        if (type == SendPresenter.Type.GATEWAY) {
            createGateAndPayment()
        } else {
            signedTransaction.attachment = Base58.encode(
                    (signedTransaction.attachment ?: "").toByteArray())
            if (signedTransaction.recipient.length <= 30) {
                signedTransaction.recipient = signedTransaction.recipient.makeAsAlias()
            }
            addSubscription(nodeManager.transactionsBroadcast(signedTransaction)
                    .compose(RxUtil.applySchedulersToObservable()).subscribe({ tx ->
                        tx.recipient = tx.recipient.clearAlias()
                        saveLastSentAddress(tx.recipient)
                        viewState.onShowTransactionSuccess(tx)
                    }, { _ ->
                        viewState.onShowError(R.string.transaction_failed)
                    }))
        }
    }

    private fun getTxRequest(): TransactionsBroadcastRequest {
        if (recipient == null || recipient!!.length < 4) {
            recipient = ""
        } else if (recipient!!.length <= 30) {
            recipient = recipient!!.makeAsAlias()
        }

        return TransactionsBroadcastRequest(
                selectedAsset!!.assetId ?: "",
                App.getAccessManager().getWallet()!!.publicKeyStr,
                recipient!!,
                MoneyUtil.getUnscaledValue(amount, selectedAsset),
                System.currentTimeMillis(),
                Constants.WAVES_FEE,
                attachment)
    }

    fun getAddressName(address: String) {
        val addressBookUser = queryFirst<AddressBookUser> {
            equalTo("address", address)
        }
        if (addressBookUser == null) {
            viewState.hideAddressBookUser()
        } else {
            viewState.showAddressBookUser(addressBookUser.name)
        }
    }

    private fun createGateAndPayment() {
        runAsync {
            val assetId = selectedAsset!!.assetId
            val findAsset: Single<List<AssetInfo>> = queryAsSingle {
                equalTo("id", assetId)
            }

            val moneroPaymentId = if (type == SendPresenter.Type.GATEWAY
                    && this.moneroPaymentId != null && this.moneroPaymentId!!.isNotEmpty()) {
                this.moneroPaymentId
            } else {
                null
            }

            addSubscription(
                    findAsset.toObservable()
                            .flatMap {
                                val currencyTo = it[0].ticker
                                val currencyFrom = "W$currencyTo"
                                coinomatManager.createTunnel(
                                        currencyFrom,
                                        currencyTo,
                                        recipient,
                                        moneroPaymentId)
                            }
                            .flatMap { createTunnel ->
                                coinomatManager.getTunnel(
                                        createTunnel.tunnelId,
                                        createTunnel.k1,
                                        createTunnel.k2,
                                        SendPresenter.LANG)
                            }
                            .flatMap {
                                recipient = it.tunnel!!.walletFrom
                                val signedTransaction = signTransaction()
                                if (signedTransaction == null) {
                                    null
                                } else {
                                    signedTransaction.attachment = Base58.encode(
                                            (signedTransaction.attachment ?: "").toByteArray())
                                    if (signedTransaction.recipient.length <= 30) {
                                        signedTransaction.recipient = signedTransaction
                                                .recipient.makeAsAlias()
                                    }
                                    nodeManager.transactionsBroadcast(signedTransaction)
                                }
                            }
                            .subscribe(
                                    { tx
                                        ->
                                        tx.recipient = tx.recipient.clearAlias()
                                        saveLastSentAddress(tx.recipient)
                                        runOnUiThread {
                                            viewState.onShowTransactionSuccess(tx)
                                        }
                                    },
                                    {
                                        runOnUiThread {
                                            viewState.onShowError(R.string.receive_error_network)
                                        }
                                    }))
        }
    }

    private fun saveLastSentAddress(newAddress: String) {
        val addresses = prefsUtil.getGlobalValueList(PrefsUtil.KEY_LAST_SENT_ADDRESSES)
        var needAdd = true
        for (address in addresses) {
            if (newAddress == address) {
                needAdd = false
            }
        }
        if (needAdd) {
            val addressesList = addresses.toMutableList()
            if (addresses.size < 5) {
                addressesList.add(newAddress)
            } else {
                addressesList.removeAt(0)
                addressesList.add(newAddress)
            }
            prefsUtil.setGlobalValue(PrefsUtil.KEY_LAST_SENT_ADDRESSES, addressesList.toTypedArray())
        }
    }
}
