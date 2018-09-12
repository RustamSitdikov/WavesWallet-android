package com.wavesplatform.wallet.v2.ui.auth.new_account

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.support.v7.widget.AppCompatImageView
import com.arellomobile.mvp.InjectViewState
import com.vicpin.krealmextensions.queryAsFlowable
import com.wavesplatform.wallet.v1.data.auth.WalletManager
import com.wavesplatform.wallet.v1.data.auth.WavesWallet
import com.wavesplatform.wallet.v1.util.AddressUtil
import com.wavesplatform.wallet.v2.ui.base.presenter.BasePresenter
import com.wavesplatform.wallet.v2.ui.custom.Identicon
import com.wavesplatform.wallet.v2.ui.home.profile.address_book.AddressBookUser
import com.wavesplatform.wallet.v2.util.RxUtil
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.Action
import io.reactivex.functions.BiFunction
import io.reactivex.functions.Function3
import kotlinx.android.synthetic.main.activity_edit_address.*
import org.apache.commons.io.Charsets
import java.util.*
import javax.inject.Inject

@InjectViewState
class NewAccountPresenter @Inject constructor() : BasePresenter<NewAccountView>() {
    var accountNameFieldValid = false
    var avatarValid = false
    var createPasswordFieldValid = false
    var confirmPasswordFieldValid = false

    fun isAllFieldsValid(): Boolean {
        return accountNameFieldValid && createPasswordFieldValid && confirmPasswordFieldValid && avatarValid
    }

    @SuppressLint("CheckResult")
    fun generateSeeds(context: Context, children: List<AppCompatImageView>) {
        Observable.fromIterable(children)
                .map {
                    val seed = WalletManager.createWalletSeed(context)
                    val wallet = WavesWallet(seed.toByteArray(Charsets.UTF_8))
                    val bitmap = Identicon.create(wallet.address,
                            Identicon.Options.Builder()
                                    .setRandomBlankColor()
                                    .create())
                    return@map Triple(seed, bitmap, it)
                }
                .compose(RxUtil.applyObservableDefaultSchedulers())
                .subscribe { t ->
                    viewState.afterSuccessGenerateAvatar(t.first, t.second, t.third)
                }
    }
}