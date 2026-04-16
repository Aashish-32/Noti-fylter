package com.notifylter.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.notifilter.R

class InsightsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_insights, container, false)
        val main = activity as MainActivity
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewInsights)
        recyclerView.layoutManager = LinearLayoutManager(context)

        val logs = main.appPriorityManager.getLogs()
        val counts = logs.groupingBy { it.packageName to it.appName }.eachCount()
        val sortedInsights = counts.map { (info, count) ->
            InsightItem(info.second, info.first, count)
        }.sortedByDescending { it.count }

        recyclerView.adapter = InsightsAdapter(sortedInsights)
        return view
    }

    data class InsightItem(val appName: String, val packageName: String, val count: Int)

    class InsightsAdapter(private val items: List<InsightItem>) : RecyclerView.Adapter<InsightsAdapter.ViewHolder>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_insight, p, false))
        override fun onBindViewHolder(h: ViewHolder, p: Int) {
            val item = items[p]
            h.name.text = item.appName
            h.packageName.text = item.packageName
            h.count.text = item.count.toString()
        }
        override fun getItemCount() = items.size
        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.insightAppName)
            val packageName: TextView = v.findViewById(R.id.insightPackageName)
            val count: TextView = v.findViewById(R.id.insightCount)
        }
    }
}
