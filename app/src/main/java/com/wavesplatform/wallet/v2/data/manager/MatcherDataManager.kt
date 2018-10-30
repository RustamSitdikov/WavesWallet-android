package com.wavesplatform.wallet.v2.data.manager

import com.google.common.primitives.Bytes
import com.google.common.primitives.Longs
import com.vicpin.krealmextensions.queryAllAsSingle
import com.wavesplatform.wallet.App
import com.wavesplatform.wallet.v1.crypto.Base58
import com.wavesplatform.wallet.v1.crypto.CryptoProvider
import com.wavesplatform.wallet.v1.util.PrefsUtil
import com.wavesplatform.wallet.v2.data.manager.base.BaseDataManager
import com.wavesplatform.wallet.v2.data.model.remote.response.GlobalConfiguration
import com.wavesplatform.wallet.v2.data.model.remote.response.MarketResponse
import com.wavesplatform.wallet.v2.data.model.remote.response.SpamAsset
import com.wavesplatform.wallet.v2.util.notNull
import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import io.reactivex.functions.Function3
import pers.victor.ext.currentTimeMillis
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MatcherDataManager @Inject constructor() : BaseDataManager() {
    var allMarketsList = mutableListOf<MarketResponse>()

    companion object {
        var OTHER_GROUP = "other"
    }

    fun loadReservedBalances(): Observable<Map<String, Long>> {
        val timestamp = currentTimeMillis
        var signature = ""
        App.getAccessManager().getWallet()?.privateKey.notNull { privateKey ->
            val bytes = Bytes.concat(Base58.decode(getPublicKeyStr()),
                    Longs.toByteArray(timestamp))
            signature = Base58.encode(CryptoProvider.sign(privateKey, bytes))
        }
        return matcherService.loadReservedBalances(getPublicKeyStr(), timestamp, signature)
    }

    fun getAllMarkets(): Observable<MutableList<MarketResponse>> {
        if (allMarketsList.isEmpty()) {
            return Observable.zip(apiService.loadGlobalConfiguration()
                    .map {
                        return@map it.generalAssetIds.associateBy { it.assetId }
                    },
                    matcherService.getAllMarkets()
                            .map { it.markets },
                    BiFunction { configure: Map<String, GlobalConfiguration.GeneralAssetId>, apiMarkets: List<MarketResponse> ->
                        return@BiFunction Pair(configure, apiMarkets)
                    })
                    .flatMap {
                        // configure hash groups
                        val hashGroup = linkedMapOf<String, MutableList<MarketResponse>>()

                        it.first.keys.forEach {
                            hashGroup[it] = mutableListOf()
                        }
                        hashGroup[OTHER_GROUP] = mutableListOf()

                        // fill information and sort by group
                        it.second.forEach { market ->
                            market.id = market.amountAsset + market.priceAsset

                            market.amountAssetLongName = it.first[market.amountAsset]?.displayName ?: market.amountAssetName
                            market.priceAssetLongName = it.first[market.priceAsset]?.displayName ?: market.priceAssetName

                            market.amountAssetShortName = it.first[market.amountAsset]?.gatewayId ?: market.amountAssetName
                            market.priceAssetShortName = it.first[market.priceAsset]?.gatewayId ?: market.priceAssetName

                            market.popular = it.first[market.amountAsset] != null && it.first[market.priceAsset] != null

                            market.amountAssetDecimals = market.amountAssetInfo.decimals
                            market.priceAssetDecimals = market.priceAssetInfo.decimals

                            val group = hashGroup[market.amountAsset]
                            if (group != null) {
                                group.add(market)
                            } else {
                                hashGroup[OTHER_GROUP]?.add(market)
                            }
                        }

                        hashGroup.values.forEach {
                            allMarketsList.addAll(it)
                        }

                        return@flatMap filterMarketsBySpamAndSelect(allMarketsList)
                    }
        } else {
            return filterMarketsBySpamAndSelect(allMarketsList)
        }
    }

    private fun filterMarketsBySpamAndSelect(markets: List<MarketResponse>): Observable<MutableList<MarketResponse>> {
        return Observable.zip(Observable.just(markets), queryAllAsSingle<SpamAsset>().toObservable()
                .map {
                    val map = it.associateBy { it.assetId }
                    return@map map
                },
                queryAllAsSingle<MarketResponse>().toObservable()
                        .map {
                            val map = it.associateBy { it.id }
                            return@map map
                        }
                , Function3 { apiMarkets: List<MarketResponse>, spamAssets: Map<String?, SpamAsset>, dbMarkets: Map<String?, MarketResponse> ->
            val filteredSpamList = if (prefsUtil.getValue(PrefsUtil.KEY_DISABLE_SPAM_FILTER, false)) {
                apiMarkets.toMutableList()
            } else {
                apiMarkets.filter { market -> spamAssets[market.amountAsset] == null && spamAssets[market.priceAsset] == null }
            }.toMutableList()

            filteredSpamList.forEach { market ->
                market.checked = dbMarkets[market.id] != null
            }

            return@Function3 filteredSpamList
        })
    }
}
