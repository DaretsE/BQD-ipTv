package ru.bqd.iptv

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class SearchRow
class SearchHeader(val text: String) : SearchRow()
class SearchItem(val hit: EpgManager.SearchHit) : SearchRow()

class SearchAdapter(
    private val ctx: Context,
    private var rows: List<SearchRow>
) : BaseAdapter() {

    private val dayFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

    fun update(list: List<SearchRow>) { rows = list; notifyDataSetChanged() }

    override fun getCount() = rows.size
    override fun getItem(p: Int) = rows[p]
    override fun getItemId(p: Int) = p.toLong()
    override fun areAllItemsEnabled() = false
    override fun isEnabled(p: Int) = rows[p] is SearchItem

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_channel, parent, false)
        val num = v.findViewById<TextView>(R.id.rowNum)
        val logo = v.findViewById<ImageView>(R.id.rowLogo)
        val name = v.findViewById<TextView>(R.id.rowName)
        val sub = v.findViewById<TextView>(R.id.rowNow)
        val star = v.findViewById<TextView>(R.id.rowStar)

        when (val row = rows[position]) {
            is SearchHeader -> {
                num.text = ""
                logo.visibility = View.GONE
                name.text = row.text
                name.setTextColor(Color.parseColor("#F5B50A"))
                sub.visibility = View.GONE
                star.visibility = View.GONE
            }
            is SearchItem -> {
                val h = row.hit
                logo.visibility = View.VISIBLE
                num.text = if (h.channel.chno.isNotEmpty()) h.channel.chno else ""
                name.setTextColor(Color.WHITE)
                name.text = h.prog.title
                ImageLoader.load(
                    if (h.channel.logo.isNotEmpty()) h.channel.logo else EpgManager.iconFor(h.channel),
                    logo
                )
                val whenStr = when (h.state) {
                    EpgManager.HitState.NOW -> "● сейчас · ${remain(h.prog.stop)}"
                    EpgManager.HitState.FUTURE -> dayFmt.format(Date(h.prog.start))
                    EpgManager.HitState.ARCHIVE -> "архив · ${dayFmt.format(Date(h.prog.start))}"
                }
                sub.visibility = View.VISIBLE
                sub.text = "${h.channel.name}  ·  $whenStr"
                if (h.state == EpgManager.HitState.NOW) {
                    star.visibility = View.VISIBLE
                    star.text = "●"
                    star.setTextColor(Color.parseColor("#1DB954"))
                } else star.visibility = View.GONE
            }
        }
        return v
    }

    private fun remain(stop: Long): String {
        val m = ((stop - System.currentTimeMillis()) / 60000).toInt()
        return if (m > 0) "осталось $m мин" else "заканчивается"
    }
}
