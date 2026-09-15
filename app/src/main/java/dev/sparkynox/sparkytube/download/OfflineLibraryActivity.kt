package dev.sparkynox.sparkytube.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.sparkynox.sparkytube.databinding.ActivityOfflineLibraryBinding
import dev.sparkynox.sparkytube.databinding.ItemOfflineVideoBinding
import java.io.File
import java.util.Locale

/**
 * Offline playlist: lists every video file SparkyTube has downloaded to
 * the public Downloads folder and lets the user play them back without
 * any network connection, or delete ones they no longer want. Reuses
 * OfflinePlayerActivity (a minimal ExoPlayer wrapper) for actual
 * playback rather than routing local files through MainActivity's
 * WebView+extractor pipeline, since none of that applies to a file
 * that's already sitting on disk.
 */
class OfflineLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfflineLibraryBinding
    private lateinit var adapter: OfflineVideoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfflineLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.offlineLibraryBackBtn.setOnClickListener { finish() }

        adapter = OfflineVideoAdapter(
            onPlay = { file -> playFile(file) },
            onDelete = { file -> confirmDelete(file) }
        )
        binding.offlineLibraryList.layoutManager = LinearLayoutManager(this)
        binding.offlineLibraryList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    /**
     * Scans the public Downloads folder for video files SparkyTube would
     * have produced (.mp4/.webm/.mkv), sorted newest-first. Simple
     * directory listing rather than a MediaStore query -- Downloads is a
     * small, flat folder in practice, and this avoids needing
     * READ_MEDIA_VIDEO permission handling just to show a handful of
     * files the app itself just wrote.
     */
    private fun refreshList() {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val videoExtensions = setOf("mp4", "webm", "mkv")

        val files = downloadsDir.listFiles { f ->
            f.isFile && f.extension.lowercase(Locale.US) in videoExtensions
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        adapter.submitList(files)
        binding.offlineLibraryEmptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun playFile(file: File) {
        val intent = Intent(this, OfflinePlayerActivity::class.java).apply {
            putExtra(OfflinePlayerActivity.EXTRA_FILE_PATH, file.absolutePath)
            putExtra(OfflinePlayerActivity.EXTRA_TITLE, file.nameWithoutExtension)
        }
        startActivity(intent)
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete this download?")
            .setMessage(file.nameWithoutExtension)
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    refreshList()
                } else {
                    Toast.makeText(this, "Couldn't delete file", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class OfflineVideoAdapter(
    private val onPlay: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<OfflineVideoAdapter.ViewHolder>() {

    private var items: List<File> = emptyList()

    fun submitList(newItems: List<File>) {
        items = newItems
        notifyDataSetChanged() // small local list -- a full library rescan is cheap and simple here
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOfflineVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onPlay, onDelete)
    }

    override fun getItemCount() = items.size

    class ViewHolder(private val binding: ItemOfflineVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File, onPlay: (File) -> Unit, onDelete: (File) -> Unit) {
            binding.itemOfflineTitle.text = file.nameWithoutExtension
            binding.itemOfflineMeta.text = "${formatBytes(file.length())}  •  ${file.extension.uppercase(Locale.US)}"
            binding.root.setOnClickListener { onPlay(file) }
            binding.itemOfflineDeleteBtn.setOnClickListener { onDelete(file) }
        }
    }
}
