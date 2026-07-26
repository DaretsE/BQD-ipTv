package ru.bqd.iptv

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/**
 * Список каналов.
 *
 * У ВЫДЕЛЕННОГО канала справа показывается квадратная кнопка действия:
 *  - канала нет в избранном — звезда с салатовым свечением (добавить);
 *  - канал в избранном — корзина с красным свечением (убрать).
 *
 * Кнопка намеренно НЕ focusable: в ListView на Android TV focusable-элементы
 * внутри строк ломают навигацию по самому списку. Поэтому фокус кнопки
 * эмулируется: PlayerActivity выставляет actionFocused, а адаптер рисует
 * подсветку через state_activated.
 *
 * ИСПРАВЛЕНИЕ СКРОЛЛА: вместо notifyDataSetChanged() при смене selectedPos
 * и actionFocused используется точечная перерисовка только затронутых строк
 * через invalidatePosition(). Это убирает дёрганье/мерцание при прокрутке,
 * потому что ListView больше не пересоздаёт ВСЕ видимые строки на каждый шаг.
 */
class ChannelAdapter(
    private val ctx: Context,
    private var items: List<Channel>,
    private val showNow: Boolean
) : BaseAdapter() {

    /** Ссылка на ListView, нужна для точечной перерисовки. Устанавливается снаружи. */
    var listView: ListView? = null

    /** Позиция выделенной строки (у неё показывается кнопка). -1 — нет. */
    var selectedPos: Int = -1
        set(v) {
            if (field != v) {
                val old = field
                field = v
                // Перерисовываем только старую и новую строку, а не весь список
                invalidatePosition(old)
                invalidatePosition(v)
            }
        }

    /** Фокус переведён на кнопку действия выделенной строки. */
    var actionFocused: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                // Перерисовываем только строку с кнопкой
                invalidatePosition(selectedPos)
            }
        }

    /**
     * Перерисовать одну строку в ListView без пересоздания всего списка.
     * Находим View строки среди видимых детей и вызываем getView() только для неё.
     */
    private fun invalidatePosition(pos: Int) {
        val lv = listView ?: return
        val first = lv.firstVisiblePosition
        val last = lv.lastVisiblePosition
        if (pos < first || pos > last) return
        val child = lv.getChildAt(pos - first) ?: return
        getView(pos, child, lv)
    }

    fun update(list: List<Channel>) { items = list; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(p: Int) = items[p]
    override fun getItemId(p: Int) = p.toLong()

    /** ViewHolder — кэшируем findViewById, чтобы не дёргать его при каждой прокрутке. */
    private class VH(v: View) {
        val num: TextView = v.findViewById(R.id.rowNum)
        val logo: ImageView = v.findViewById(R.id.rowLogo)
        val name: TextView = v.findViewById(R.id.rowName)
        val now: TextView = v.findViewById(R.id.rowNow)
        val star: TextView = v.findViewById(R.id.rowStar)
        val action: TextView = v.findViewById(R.id.rowAction)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v: View
        val h: VH
        if (convertView?.tag is VH) {
            v = convertView
            h = convertView.tag as VH
        } else {
            v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_channel, parent, false)
            h = VH(v)
            v.tag = h
        }
        val ch = items[position]

        h.num.text = if (ch.chno.isNotEmpty()) ch.chno else (position + 1).toString()
        h.name.text = ch.name

        val logoUrl = if (ch.logo.isNotEmpty()) ch.logo else EpgManager.iconFor(ch)
        ImageLoader.load(logoUrl, h.logo)

        if (showNow) {
            val cur = EpgManager.currentFor(ch)
            if (cur != null) { h.now.text = cur.title; h.now.visibility = View.VISIBLE }
            else h.now.visibility = View.GONE
        } else h.now.visibility = View.GONE

        val fav = Store.isFavorite(ch.url)
        // звёздочку-признак прячем у выделенной строки: там уже есть кнопка
        h.star.visibility = if (fav && position != selectedPos) View.VISIBLE else View.GONE

        if (position == selectedPos) {
            // кнопка нейтральная; цвет и свечение появляются только когда на ней фокус
            h.action.visibility = View.VISIBLE
            if (fav) {
                h.action.setBackgroundResource(R.drawable.rowbtn_del)
                IconFont.apply(h.action, "delete")
                h.action.setTextColor(if (actionFocused) 0xFFFF8A8A.toInt() else 0xFFC0D3DA.toInt())
            } else {
                h.action.setBackgroundResource(R.drawable.rowbtn_add)
                IconFont.apply(h.action, "star")
                h.action.setTextColor(if (actionFocused) 0xFFA8E05F.toInt() else 0xFFC0D3DA.toInt())
            }
            h.action.isActivated = actionFocused
        } else {
            h.action.visibility = View.GONE
            h.action.isActivated = false
        }
        return v
    }
}
