package ru.lightair.iptv

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/** Строка канала: номер + логотип + название + "сейчас идёт" из EPG. */
class ChannelAdapter(
    private val ctx: Context,
    private var items: List<Channel>,
    private val showNow: Boolean
) : BaseAdapter() {

    fun update(list: List<Channel>) { items = list; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(p: Int) = items[p]
    override fun getItemId(p: Int) = p.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_channel, parent, false)
        val ch = items[position]

        val num = v.findViewById<TextView>(R.id.rowNum)
        val logo = v.findViewById<ImageView>(R.id.rowLogo)
        val name = v.findViewById<TextView>(R.id.rowName)
        val now = v.findViewById<TextView>(R.id.rowNow)
        val star = v.findViewById<TextView>(R.id.rowStar)

        num.text = if (ch.chno.isNotEmpty()) ch.chno else (position + 1).toString()
        name.text = ch.name

        val logoUrl = if (ch.logo.isNotEmpty()) ch.logo else EpgManager.iconFor(ch)
        ImageLoader.load(logoUrl, logo)

        if (showNow) {
            val cur = EpgManager.currentFor(ch)
            if (cur != null) { now.text = cur.title; now.visibility = View.VISIBLE }
            else now.visibility = View.GONE
        } else now.visibility = View.GONE

        star.visibility = if (Store.isFavorite(ch.url)) View.VISIBLE else View.GONE
        return v
    }
}
