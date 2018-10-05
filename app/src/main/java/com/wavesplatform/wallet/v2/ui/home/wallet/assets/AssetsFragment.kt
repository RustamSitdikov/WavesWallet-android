package com.wavesplatform.wallet.v2.ui.home.wallet.assets

import android.content.Intent
import android.os.Bundle
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import com.arellomobile.mvp.presenter.InjectPresenter
import com.arellomobile.mvp.presenter.ProvidePresenter
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.entity.MultiItemEntity
import com.ethanhua.skeleton.RecyclerViewSkeletonScreen
import com.ethanhua.skeleton.Skeleton
import com.oushangfeng.pinnedsectionitemdecoration.PinnedHeaderItemDecoration
import com.oushangfeng.pinnedsectionitemdecoration.callback.OnHeaderClickAdapter
import com.wavesplatform.wallet.R
import com.wavesplatform.wallet.v2.data.Constants
import com.wavesplatform.wallet.v2.data.Events
import com.wavesplatform.wallet.v2.data.model.remote.response.AssetBalance
import com.wavesplatform.wallet.v2.data.service.UpdateApiDataService
import com.wavesplatform.wallet.v2.ui.base.view.BaseFragment
import com.wavesplatform.wallet.v2.ui.home.MainActivity
import com.wavesplatform.wallet.v2.ui.home.history.tab.HistoryTabFragment
import com.wavesplatform.wallet.v2.ui.home.wallet.address.MyAddressQRActivity
import com.wavesplatform.wallet.v2.ui.home.wallet.assets.details.AssetDetailsActivity
import com.wavesplatform.wallet.v2.ui.home.wallet.assets.sorting.AssetsSortingActivity
import com.wavesplatform.wallet.v2.util.isMyServiceRunning
import com.wavesplatform.wallet.v2.util.launchActivity
import com.wavesplatform.wallet.v2.util.notNull
import kotlinx.android.synthetic.main.fragment_assets.*
import kotlinx.android.synthetic.main.wallet_header_item.view.*
import pers.victor.ext.dp2px
import pers.victor.ext.gone
import pers.victor.ext.isVisible
import pers.victor.ext.toast
import pyxis.uzuki.live.richutilskt.utils.runOnUiThread
import javax.inject.Inject


class AssetsFragment : BaseFragment(), AssetsView {

    @Inject
    @InjectPresenter
    lateinit var presenter: AssetsPresenter

    @ProvidePresenter
    fun providePresenter(): AssetsPresenter = presenter

    @Inject
    lateinit var adapter: AssetsAdapter

    private var skeletonScreen: RecyclerViewSkeletonScreen? = null
    private var headerItemDecoration: PinnedHeaderItemDecoration? = null
    var changeTabBarVisibilityListener: HistoryTabFragment.ChangeTabBarVisibilityListener? = null

    companion object {
        const val RESULT_NEED_UPDATE = "need_update"
        const val REQUEST_SORTING = 111
        const val REQUEST_ASSET_DETAILS = 112

        fun newInstance(): AssetsFragment {
            return AssetsFragment()
        }
    }

    override fun configLayoutRes(): Int = R.layout.fragment_assets

    override fun onViewReady(savedInstanceState: Bundle?) {
        setupUI()

        eventSubscriptions.add(rxEventBus.filteredObservable(Events.ScrollToTopEvent::class.java)
                .subscribe {
                    if (it.position == MainActivity.WALLET_SCREEN) {
                        recycle_assets.scrollToPosition(0)
                        changeTabBarVisibilityListener?.changeTabBarVisibility(true)
                    }
                })

        skeletonScreen.notNull { it.show() }
        presenter.loadAliases()
        presenter.loadAssetsBalance()

        eventSubscriptions.add(rxEventBus.filteredObservable(Events.SpamFilterStateChanged::class.java)
                .subscribe {
                    swipe_container.isRefreshing = true
                    presenter.reloadAssetsAfterSpamFilterStateChanged()
                })

        eventSubscriptions.add(rxEventBus.filteredObservable(Events.SpamFilterUrlChanged::class.java)
                .subscribe {
                    if (!it.updateTransaction) {
                        swipe_container.isRefreshing = true
                        presenter.reloadAssetsAfterSpamUrlChanged()
                    }
                })
    }

    private fun setupUI() {
        swipe_container.setColorSchemeResources(R.color.submit400)
        swipe_container.setOnRefreshListener {
            presenter.loadAssetsBalance()
        }

        recycle_assets.layoutManager = LinearLayoutManager(baseActivity)
        adapter.bindToRecyclerView(recycle_assets)

        val headerClickListener = object : OnHeaderClickAdapter() {
            override fun onHeaderClick(view: View, id: Int, position: Int) {
                presenter.needToScroll = true
                recycle_assets.smoothScrollToPosition(position)
            }
        }

        headerItemDecoration = PinnedHeaderItemDecoration.Builder(AssetsAdapter.TYPE_HEADER)
                .disableHeaderClick(false)
                .setHeaderClickListener(headerClickListener)
                .create()
        recycle_assets.addItemDecoration(headerItemDecoration)

        recycle_assets.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView?, dx: Int, dy: Int) {
                if (headerItemDecoration?.pinnedHeaderView != null) {
                    swipe_container.isEnabled = false
                    if (headerItemDecoration?.pinnedHeaderView?.image_arrow?.isVisible() == true) {
                        headerItemDecoration?.pinnedHeaderView?.image_arrow?.gone()
                    }
                } else {
                    swipe_container.isEnabled = true
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView?, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (presenter.needToScroll) {
                        presenter.needToScroll = false
                        recycle_assets.smoothScrollBy(0, -dp2px(1))
                    }
                }
            }
        })

        skeletonScreen = Skeleton.bind(recycle_assets)
                .adapter(recycle_assets.adapter)
                .color(R.color.basic100)
                .load(R.layout.item_skeleton_wallet)
                .frozen(false)
                .show()

        adapter.onItemClickListener = BaseQuickAdapter.OnItemClickListener { adapter, view, position ->
            val item = this.adapter.getItem(position) as AssetBalance
            val positionWithoutSection = when {
                item.isHidden -> position - 1 // minus hidden section header
                item.isSpam -> position - 2 // minus hidden and spam sections header
                else -> position // no changes
            }
            launchActivity<AssetDetailsActivity>(REQUEST_ASSET_DETAILS) {
                putExtra(AssetDetailsActivity.BUNDLE_ASSET_BALANCE_ITEM, item)
                putExtra(AssetDetailsActivity.BUNDLE_ASSET_POSITION, positionWithoutSection)
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val item = menu.findItem(R.id.action_sorting)
        item.isVisible = true
    }

    override fun afterFailedUpdateAssets() {
        swipe_container?.isRefreshing = false
    }

    override fun startServiceToLoadData(assets: ArrayList<AssetBalance>) {
        runOnUiThread {
            if (!baseActivity.isMyServiceRunning(UpdateApiDataService::class.java)) {
                val intent = Intent(baseActivity, UpdateApiDataService::class.java)
                baseActivity.startService(intent)
            }
        }
    }

    override fun afterSuccessLoadAssets(assets: ArrayList<MultiItemEntity>, fromDB: Boolean, withApiUpdate: Boolean) {
        if (fromDB) {
            swipe_container?.isRefreshing = true
            skeletonScreen.notNull { it.hide() }
        } else {
            swipe_container?.isRefreshing = false
        }

        adapter.setNewData(assets)
    }

    override fun afterFailedLoadAssets() {
        swipe_container?.isRefreshing = false
        toast(getString(R.string.unexpected_error))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_SORTING, REQUEST_ASSET_DETAILS -> {
                if (resultCode == Constants.RESULT_OK) {
                    val needToUpdate = data?.getBooleanExtra(RESULT_NEED_UPDATE, false)
                    if (needToUpdate == true) {
                        swipe_container?.isRefreshing = true
                        presenter.loadAssetsBalance(false)
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_assets, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_sorting -> {
                launchActivity<AssetsSortingActivity>(REQUEST_SORTING)
            }
            R.id.action_your_address -> {
                launchActivity<MyAddressQRActivity>()
            }
        }

        return super.onOptionsItemSelected(item)
    }
}
