package ru.example.meetingrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.example.meetingrecorder.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recorderAdapter: RecorderAdapter

    private var service: RecordingService? = null
    private var bound = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                startRecordingFlow()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RecordingService.LocalBinder).getService()
            bound = true
            updateUi()
            // Обновляем таймер каждую секунду, пока сервис живёт
            service?.onTickListener = { elapsedMs -> binding.tvTimer.text = formatTime(elapsedMs) }
            service?.onFinishedListener = {
                service?.onTickListener = null
                service?.onFinishedListener = null
                updateUi()
                refreshRecordings()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recorderAdapter = RecorderAdapter(
            onClick = { rec -> playRecording(rec.uri) },
            onLongClick = { rec -> confirmDelete(rec.uri) }
        )
        binding.rvRecordings.adapter = recorderAdapter

        binding.btnRecord.setOnClickListener { onRecordButtonClicked() }
        binding.btnStop.setOnClickListener { stopRecording() }

        updateUi()
        refreshRecordings()
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, RecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun onRecordButtonClicked() {
        if (hasRecordPermission()) {
            startRecordingFlow()
        } else {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun startRecordingFlow() {
        val title = binding.etTitle.text?.toString()?.trim().orEmpty()
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_TITLE, title.ifEmpty { null })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.etTitle.setText("")
        updateUi()
    }

    private fun stopRecording() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun isRecording(): Boolean =
        service?.isRecording() == true || RecordingService.isRecordingNow

    private fun updateUi() {
        val recording = isRecording()
        binding.btnRecord.visibility = if (recording) View.GONE else View.VISIBLE
        binding.btnStop.visibility = if (recording) View.VISIBLE else View.GONE
        binding.tvStatus.text = getString(
            if (recording) R.string.status_recording else R.string.status_idle
        )
        if (!recording) {
            binding.tvTimer.text = formatTime(0)
        }
    }

    private fun refreshRecordings() {
        val items = RecordingRepository.getAll(this)
        recorderAdapter.submitList(items)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun playRecording(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(uri: Uri) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_message)
            .setPositiveButton(R.string.delete_ok) { _, _ ->
                if (RecordingRepository.delete(this, uri)) {
                    refreshRecordings()
                } else {
                    Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }
}
