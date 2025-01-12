package com.arn.scrobble.charts

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateFormat
import android.view.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.arn.scrobble.*
import com.arn.scrobble.Stuff.dp
import com.arn.scrobble.databinding.ChipsChartsPeriodBinding
import com.arn.scrobble.databinding.ContentChartsBinding
import com.arn.scrobble.databinding.FrameChartsListBinding
import com.arn.scrobble.ui.EndlessRecyclerViewScrollListener
import com.arn.scrobble.ui.SimpleHeaderDecoration
import com.google.android.material.chip.Chip
import de.umass.lastfm.*
import kotlin.math.roundToInt


open class ChartsBaseFragment: ChartsPeriodFragment() {

    lateinit var adapter: ChartsAdapter

    private var _chartsBinding: FrameChartsListBinding? = null
    private val chartsBinding
        get() = _chartsBinding!!
    private var _periodChipsBinding: ChipsChartsPeriodBinding? = null
    override val periodChipsBinding
        get() = _periodChipsBinding!!


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        setHasOptionsMenu(true)
        val binding = ContentChartsBinding.inflate(inflater, container, false)
        _chartsBinding = binding.frameChartsList
        _periodChipsBinding = binding.chipsChartsPeriod
        return binding.root
    }

    override fun onDestroyView() {
        _chartsBinding = null
        _periodChipsBinding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        if (chartsBinding.chartsList.adapter == null)
            postInit()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val glm = chartsBinding.chartsList.layoutManager as GridLayoutManager?
        glm?.spanCount = getNumColumns()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.charts_menu, menu)
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_share)
            share()
        return super.onOptionsItemSelected(item)
    }

    override fun loadFirstPage() {
        loadCharts(1)
    }

    override fun loadWeeklyCharts() {
        viewModel.loadWeeklyCharts()
    }

    override fun postInit() {
        super.postInit()
        adapter = ChartsAdapter(chartsBinding)

        val glm = GridLayoutManager(context!!, getNumColumns())
        chartsBinding.chartsList.layoutManager = glm
        (chartsBinding.chartsList.itemAnimator as SimpleItemAnimator?)?.supportsChangeAnimations = false
        chartsBinding.chartsList.adapter = adapter
        chartsBinding.chartsList.addItemDecoration(SimpleHeaderDecoration(0, 25.dp))

        var itemDecor = DividerItemDecoration(context!!, DividerItemDecoration.HORIZONTAL)
        itemDecor.setDrawable(ContextCompat.getDrawable(context!!, R.drawable.shape_divider_chart)!!)
        chartsBinding.chartsList.addItemDecoration(itemDecor)

        itemDecor = DividerItemDecoration(context!!, DividerItemDecoration.VERTICAL)
        itemDecor.setDrawable(ContextCompat.getDrawable(context!!, R.drawable.shape_divider_chart)!!)
        chartsBinding.chartsList.addItemDecoration(itemDecor)

        val loadMoreListener = EndlessRecyclerViewScrollListener(glm) {
            loadCharts(it)
        }
        loadMoreListener.currentPage = viewModel.page
        chartsBinding.chartsList.addOnScrollListener(loadMoreListener)
        adapter.loadMoreListener = loadMoreListener
        adapter.clickListener = this
        adapter.viewModel = viewModel

        viewModel.chartsReceiver.observe(viewLifecycleOwner) {
            if (it == null && !MainActivity.isOnline && viewModel.chartsData.size == 0)
                adapter.populate()
            it ?: return@observe
            viewModel.totalCount = it.total
            if (it.page >= it.totalPages)
                viewModel.reachedEnd = true
            synchronized(viewModel.chartsData) {
                if (it.page == 1)
                    viewModel.chartsData.clear()
                viewModel.chartsData.addAll(it.pageResults)
            }
            loadMoreListener.currentPage = it.page
            adapter.populate()

            // sometimes does somersaults
//            if (it.page == 1)
//                chartsBinding.chartsList.smoothScrollToPosition(0)
            viewModel.chartsReceiver.value = null
        }

        if (viewModel.chartsData.isNotEmpty())
            adapter.populate()
    }

    private fun loadCharts(page: Int) {
        _chartsBinding ?: return
        if (viewModel.reachedEnd) {
            adapter.loadMoreListener.isAllPagesLoaded = true
            return
        }
        viewModel.loadCharts(page)
    }

    private fun share() {
        val entries = viewModel.chartsData
        if (entries.isNullOrEmpty())
            return
        val topType = when (entries[0]) {
            is Artist -> getString(R.string.top_artists)
            is Album -> getString(R.string.top_albums)
            else -> getString(R.string.top_tracks)
        }
        val checkedChip = periodChipsBinding.chartsPeriod.findViewById<Chip>(periodChipsBinding.chartsPeriod.checkedChipId)
        val period = when (checkedChip.id) {
            R.id.charts_choose_week -> {
                viewModel.weeklyChart ?: return
                getString(
                    R.string.weekly_range,
                    DateFormat.getMediumDateFormat(context).format(viewModel.weeklyChart!!.from.time),
                    DateFormat.getMediumDateFormat(context).format(viewModel.weeklyChart!!.to.time)
                )
            }
            R.id.charts_7day -> getString(R.string.weekly)
            R.id.charts_1month -> getString(R.string.monthly)
            else -> checkedChip.text.toString()
        }
        var pos = 1
        val list = entries.take(10).joinToString(separator = "\n") {
            when (it) {
                is Track -> getString(
                    R.string.charts_num_text,
                    pos++,
                    getString(R.string.artist_title, it.artist, it.name)
                )
                is Album -> getString(
                    R.string.charts_num_text,
                    pos++,
                    getString(R.string.artist_title, it.artist, it.name)
                )
                else -> getString(R.string.charts_num_text, pos++, it.name)
            }
        }

        var shareText = if (username != null)
                getString(R.string.charts_share_username, period.lowercase(), topType.lowercase(), list, username)
            else
                getString(R.string.charts_share, period.lowercase(), topType.lowercase(), list)

        if ((activity as MainActivity).billingViewModel.proStatus.value != true)
            shareText += "\n\n" + getString(R.string.share_sig)
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_SUBJECT, shareText)
        i.putExtra(Intent.EXTRA_TEXT, shareText)
        startActivity(Intent.createChooser(i, getString(R.string.share_this_chart)))
    }

    private fun getNumColumns(): Int {
        return resources.displayMetrics.widthPixels /
                resources.getDimension(R.dimen.big_grid_size).roundToInt()
    }
}