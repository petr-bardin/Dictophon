package ru.example.meetingrecorder

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.text.InputFilter
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.example.meetingrecorder.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var recorderAdapter: RecorderAdapter
    private var service: RecordingService? = null
    private var bound = false
    private var bindingRequested = false
    private var starting = false
    private var archiveVisible = false
    private var favoritesOnly = false
    private var recordings: List<Recording> = emptyList()
    private val preferences by lazy { getSharedPreferences("aurel_archive", MODE_PRIVATE) }
    private var favoriteUris = mutableSetOf<String>()
    private var loadJob: Job? = null
    private var player: MediaPlayer? = null
    private var playingUri: Uri? = null
    private var playerPrepared = false
    private var seeking = false
    private val handler = Handler(Looper.getMainLooper())
    private var saveDialog: AlertDialog? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasRecordPermission()) startRecordingFlow() else message(R.string.permission_denied)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RecordingService.LocalBinder).getService(); bound = true; starting = false
            service?.onTickListener = { elapsed, amplitude -> binding.instrument.setSample(elapsed, amplitude) }
            service?.onStateListener = { starting = false; updateUi() }
            service?.onFinishedListener = { uri, error ->
                starting = false; updateUi()
                if (error != null) message(error)
                if (uri != null) {
                    message(if (uri.scheme == "file" && Build.VERSION.SDK_INT >= 29) R.string.saved_local else R.string.saved)
                    showArchive(true); refreshRecordings()
                }
            }
            updateUi()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; bound = false; starting = false; updateUi() }
    }

    private val playbackTicker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!playerPrepared) return
            if (!seeking) {
                binding.seekPlayback.progress = p.currentPosition
                binding.tvPlayTime.text = RecordingRepository.formatDuration(p.currentPosition / 1000L)
            }
            handler.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)
        favoriteUris = preferences.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        recorderAdapter = RecorderAdapter({ playRecording(it) }, { showRecordingActions(it) }, { toggleFavorite(it) })
        binding.rvRecordings.adapter = recorderAdapter
        binding.btnRecord.setOnClickListener { onRecordButtonClicked() }
        binding.btnStop.setOnClickListener { showSaveDialog() }
        binding.btnMarker.setOnClickListener {
            service?.addMarker()?.let { time -> message(getString(R.string.marker_added, service?.markerCount ?: 0, RecordingRepository.formatDuration(time / 1000))) }
        }
        binding.btnArchive.setOnClickListener { showArchive(true); refreshRecordings() }
        binding.btnStudio.setOnClickListener { showArchive(false) }
        binding.btnInfo.setOnClickListener { AlertDialog.Builder(this).setTitle("AUREL").setMessage(R.string.about_text).setPositiveButton(R.string.ok, null).show() }
        binding.etSearch.doAfterTextChanged { filterRecordings() }
        binding.btnAll.setOnClickListener { favoritesOnly = false; filterRecordings() }
        binding.btnFavorites.setOnClickListener { favoritesOnly = true; filterRecordings() }
        binding.btnPlayPause.setOnClickListener { togglePlayback() }
        binding.btnClosePlayer.setOnClickListener { releasePlayer() }
        binding.seekPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) binding.tvPlayTime.text = RecordingRepository.formatDuration(progress / 1000L) }
            override fun onStartTrackingTouch(seekBar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) { if(playerPrepared) player?.seekTo(seekBar.progress); seeking = false }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (archiveVisible) showArchive(false) else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        favoritesOnly = savedInstanceState?.getBoolean("favorites") ?: false
        showArchive(savedInstanceState?.getBoolean("archive") ?: false)
        updateUi()
        savedInstanceState?.getBundle("instrument")?.let { binding.instrument.restoreVisualState(it) }
        refreshRecordings()
    }

    override fun onStart() {
        super.onStart()
        bindingRequested = bindService(Intent(this, RecordingService::class.java), connection, BIND_AUTO_CREATE)
        refreshRecordings()
    }
    override fun onStop() {
        detachServiceListeners()
        if (bindingRequested) { unbindService(connection); bindingRequested = false }
        bound = false; service = null; releasePlayer()
        super.onStop()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); saveDialog?.dismiss(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBundle("instrument",binding.instrument.visualState()); outState.putBoolean("archive", archiveVisible); outState.putBoolean("favorites", favoritesOnly); super.onSaveInstanceState(outState) }

    private fun detachServiceListeners() { service?.apply { onTickListener = null; onStateListener = null; onFinishedListener = null } }
    private fun hasRecordPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun onRecordButtonClicked() {
        when (service?.state) {
            RecordingService.State.RECORDING -> service?.pauseRecording()
            RecordingService.State.PAUSED -> { releasePlayer(); service?.resumeRecording() }
            RecordingService.State.IDLE -> if (hasRecordPermission()) startRecordingFlow() else {
                val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                permissionLauncher.launch(permissions.toTypedArray())
            }
            else -> Unit
        }
    }
    private fun startRecordingFlow() {
        releasePlayer(); starting = true; updateUi()
        val intent = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
        try { ContextCompat.startForegroundService(this, intent) } catch (_: Exception) { starting = false; updateUi(); message(R.string.record_start_failed) }
    }
    private fun updateUi() {
        val state = service?.state ?: RecordingService.State.IDLE
        binding.instrument.setRecordingState(state)
        binding.instrument.setSample(service?.elapsedMs ?: 0, 0)
        binding.btnRecord.recordingState = state
        binding.btnRecord.isEnabled = bound && !starting && state != RecordingService.State.SAVING
        val active = state == RecordingService.State.RECORDING || state == RecordingService.State.PAUSED
        binding.btnStop.isEnabled = active; binding.btnStop.alpha = if(active) 1f else .28f
        binding.btnMarker.isEnabled = active; binding.btnMarker.alpha = if(active) 1f else .28f
        binding.btnMarker.contentDescription = getString(R.string.add_marker) + (service?.markerCount?.takeIf { it > 0 }?.let { " · $it" } ?: "")
    }
    private fun showSaveDialog() {
        val recorder = service ?: return
        if (saveDialog?.isShowing == true) return
        val wasRunning = recorder.state == RecordingService.State.RECORDING
        if (wasRunning && !recorder.pauseRecording()) return
        if (recorder.state != RecordingService.State.PAUSED) return
        val input = EditText(this).apply {
            hint = getString(R.string.name_hint); setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(80))
            val padding = (20 * resources.displayMetrics.density).toInt(); setPadding(padding,padding,padding,padding)
            setText(getString(R.string.recording_default_name, SimpleDateFormat("dd.MM HH-mm", Locale.getDefault()).format(Date()))); selectAll()
        }
        fun continueIfNeeded() { if(wasRunning && recorder.state == RecordingService.State.PAUSED) recorder.resumeRecording() }
        saveDialog = AlertDialog.Builder(this).setTitle(R.string.save_title).setView(input)
            .setPositiveButton(R.string.save) { _, _ -> recorder.finishRecording(input.text.toString().trim().ifEmpty { null }) }
            .setNegativeButton(R.string.cancel) { _, _ -> continueIfNeeded() }
            .setOnCancelListener { continueIfNeeded() }.create().also { it.show() }
    }
    private fun showArchive(show: Boolean) {
        archiveVisible = show
        binding.archiveScreen.visibility = if(show) View.VISIBLE else View.GONE
        binding.studioScreen.visibility = if(show) View.GONE else View.VISIBLE
        if(!show) { releasePlayer(); binding.etSearch.clearFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(binding.root.windowToken,0) }
    }
    private fun refreshRecordings() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { RecordingRepository.getAll(applicationContext) } }
            result.onSuccess { recordings = it; filterRecordings() }.onFailure { message(R.string.archive_failed) }
        }
    }
    private fun filterRecordings() {
        val query = binding.etSearch.text.toString().trim()
        val shown = recordings.filter { (!favoritesOnly || it.uri.toString() in favoriteUris) && it.title.contains(query, ignoreCase=true) }
        recorderAdapter.submitList(shown.map { RecordingItem(it,it.uri.toString() in favoriteUris) })
        binding.tvFileCount.text = String.format(Locale.US,"%02d",recordings.size)
        binding.tvEmpty.visibility = if(shown.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.setText(if(query.isNotEmpty()) R.string.no_results else if(favoritesOnly) R.string.no_favorites else R.string.list_empty)
        binding.btnAll.isSelected = !favoritesOnly; binding.btnFavorites.isSelected = favoritesOnly
        binding.btnAll.setTextColor(ContextCompat.getColor(this, if(favoritesOnly) R.color.muted else R.color.brass))
        binding.btnFavorites.setTextColor(ContextCompat.getColor(this, if(favoritesOnly) R.color.brass else R.color.muted))
    }
    private fun toggleFavorite(recording: Recording) {
        val uri = recording.uri.toString()
        if(!favoriteUris.add(uri)) favoriteUris.remove(uri)
        preferences.edit().putStringSet("favorites",favoriteUris.toSet()).apply(); filterRecordings()
    }
    private fun showRecordingActions(recording: Recording) {
        val markers = RecordingRepository.markers(this, recording.uri)
        if(markers.isEmpty()) confirmDelete(recording) else AlertDialog.Builder(this).setTitle(recording.title)
            .setItems(arrayOf(getString(R.string.show_markers),getString(R.string.delete_ok))) { _, which -> if(which==1) confirmDelete(recording) else {
                AlertDialog.Builder(this).setTitle(R.string.show_markers).setItems(markers.mapIndexed { index, ms -> getString(R.string.marker_list_item,index+1,RecordingRepository.formatDuration(ms/1000)) }.toTypedArray()) { _, index -> playRecording(recording,markers[index].coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }.setNegativeButton(R.string.cancel,null).show()
            } }.show()
    }
    private fun confirmDelete(recording: Recording) {
        AlertDialog.Builder(this).setTitle(R.string.delete_title).setMessage(R.string.delete_message)
            .setPositiveButton(R.string.delete_ok) { _, _ ->
                if(playingUri==recording.uri) releasePlayer()
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) { RecordingRepository.delete(applicationContext,recording.uri) }
                    if(deleted) { favoriteUris.remove(recording.uri.toString()); preferences.edit().putStringSet("favorites",favoriteUris.toSet()).apply(); refreshRecordings() } else message(R.string.delete_failed)
                }
            }.setNegativeButton(R.string.cancel,null).show()
    }
    private fun playRecording(recording: Recording, seekTo: Int? = null) {
        if(service?.state == RecordingService.State.RECORDING) { message(R.string.pause_before_play); return }
        if(playingUri==recording.uri && playerPrepared) {
            if(seekTo!=null) { player?.seekTo(seekTo); player?.start(); updatePlaybackButton() } else togglePlayback()
            return
        }
        releasePlayer()
        val p = MediaPlayer(); player = p; playingUri = recording.uri
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
            p.setDataSource(this,recording.uri)
            p.setOnPreparedListener {
                if(player !== p) return@setOnPreparedListener
                playerPrepared = true; binding.tvPlaying.text = recording.title; binding.seekPlayback.max = p.duration
                binding.playerPanel.visibility = View.VISIBLE
                if(seekTo!=null) p.seekTo(seekTo.coerceAtMost(p.duration))
                p.start(); updatePlaybackButton(); handler.post(playbackTicker)
            }
            p.setOnCompletionListener { updatePlaybackButton() }
            p.setOnErrorListener { _, _, _ -> if(player===p) { releasePlayer(); message(R.string.play_failed) }; true }
            p.prepareAsync()
        } catch (_: Exception) { releasePlayer(); message(R.string.play_failed) }
    }
    private fun togglePlayback() {
        val p = player ?: return
        if(!playerPrepared) return
        if(p.isPlaying) p.pause() else { if(p.currentPosition>=p.duration) p.seekTo(0); p.start() }
        updatePlaybackButton()
    }
    private fun updatePlaybackButton() {
        val playing = playerPrepared && player?.isPlaying == true
        binding.btnPlayPause.setImageResource(if(playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnPlayPause.contentDescription = getString(if(playing) R.string.btn_pause else R.string.btn_resume)
    }
    private fun releasePlayer() {
        handler.removeCallbacks(playbackTicker)
        player?.apply { setOnPreparedListener(null); setOnCompletionListener(null); setOnErrorListener(null); release() }
        player = null; playingUri = null; playerPrepared = false; seeking = false
        binding.playerPanel.visibility = View.GONE
    }
    private fun message(resource: Int) = message(getString(resource))
    private fun message(text: String) { Toast.makeText(this,text,Toast.LENGTH_SHORT).show() }
}
