package ru.bqd.iptv

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/** Пункт левого меню. type: SETTINGS / ALL / GROUP */
data class CatItem(val title: String, val count: Int, val type: String, val group: String? = null)

class CategoryAdapter(
    private val ctx: Context,
    private var items: List<CatItem>
) : BaseAdapter() {

    fun update(list: List<CatItem>) { items = list; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(p: Int) = items[p]
    override fun getItemId(p: Int) = p.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_category, parent, false)
        val item = items[position]
        val left = v.findViewById<TextView>(R.id.catName)
        val right = v.findViewById<TextView>(R.id.catCount)
        val root = v.findViewById<View>(R.id.catRoot)

        left.text = item.title
        if (item.type == "SETTINGS") {
            right.text = "›"
            root.setBackgroundColor(Color.parseColor("#243447"))
            left.setTextColor(Color.parseColor("#FFC107"))
        } else {
            right.text = if (item.count > 0) item.count.toString() else ""
            root.setBackgroundColor(Color.TRANSPARENT)
            left.setTextColor(Color.WHITE)
        }
        return v
    }
}
