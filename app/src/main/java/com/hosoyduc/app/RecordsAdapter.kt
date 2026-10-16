package com.hosoyduc.app

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Chỉ giữ các dòng đã tải (mỗi lần 100). Thêm trang mới bằng notifyItemRangeInserted
 * thay vì notifyDataSetChanged() trên toàn bộ danh sách.
 */
class RecordsAdapter(
    private val onDelete: (RecordRow) -> Unit
) : RecyclerView.Adapter<RecordsAdapter.VH>() {

    private val items = ArrayList<RecordRow>()

    class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val tvOriginal: TextView = itemView.findViewById(R.id.tvOriginal)
        val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
        val tvTimes: TextView = itemView.findViewById(R.id.tvTimes)
        val btnDelete: Button = itemView.findViewById(R.id.btnDelete)
    }

    fun replaceAll(rows: List<RecordRow>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    fun append(rows: List<RecordRow>) {
        if (rows.isEmpty()) return
        val start = items.size
        items.addAll(rows)
        notifyItemRangeInserted(start, rows.size)
    }

    fun remove(key: String) {
        val i = items.indexOfFirst { it.key == key }
        if (i >= 0) { items.removeAt(i); notifyItemRemoved(i) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.tvOriginal.text = row.original
        val seal = when (row.seal) {
            true -> " · 🔒 hash thời gian hợp lệ"
            false -> " · ⚠️ hash thời gian KHÔNG khớp"
            null -> ""
        }
        holder.tvMeta.text = "${row.device} · nguồn: ${row.source}$seal"
        holder.tvTimes.text = row.timesText
        holder.btnDelete.setOnClickListener { onDelete(row) }
    }
}
