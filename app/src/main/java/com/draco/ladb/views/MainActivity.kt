package com.draco.ladb.views

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import com.draco.ladb.BuildConfig
import com.draco.ladb.R
import com.draco.ladb.viewmodels.MainActivityViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch

class MainActivity : AppCompatActivity() {
    /* View Model */
    private lateinit var viewModel: MainActivityViewModel

    /* UI components */
    private lateinit var command: TextInputEditText
    private lateinit var output: MaterialTextView
    private lateinit var outputScrollView: ScrollView
    private lateinit var progress: ProgressBar

    /* Alert dialogs */
    private lateinit var helpDialog: MaterialAlertDialogBuilder
    private lateinit var pairDialog: MaterialAlertDialogBuilder

    /* Held when pairing */
    private var pairingLatch = CountDownLatch(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this).get(MainActivityViewModel::class.java)

        command = findViewById(R.id.command)
        output = findViewById(R.id.output)
        outputScrollView = findViewById(R.id.output_scrollview)
        progress = findViewById(R.id.progress)

        helpDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_message)
            .setPositiveButton(R.string.dismiss, null)
            .setNegativeButton(R.string.reset) { _, _ ->
                progress.visibility = View.VISIBLE
                command.isEnabled = false

                lifecycleScope.launch(Dispatchers.IO) {
                    viewModel.getAdb().reset()
                    with(getPreferences(MODE_PRIVATE).edit()) {
                        putBoolean("paired", false)
                        apply()
                    }
                    finishAffinity()
                }
            }

        pairDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pair_title)
            .setMessage(R.string.pair_message)
            .setCancelable(false)
            .setView(R.layout.dialog_pair)

        command.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                val text = command.text.toString()
                command.text = null

                lifecycleScope.launch(Dispatchers.IO) {
                    viewModel.getAdb().sendToAdbShellProcess(text)
                }

                return@setOnKeyListener true
            }

            return@setOnKeyListener false
        }

        viewModel.getOutputText().observe(this, Observer {
            output.text = it
            outputScrollView.post {
                outputScrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        })
        viewModel.getAdbReady().observe(this, Observer {
            if (it == false)
                return@Observer

            lifecycleScope.launch(Dispatchers.IO) {
                pairingLatch.await()

                runOnUiThread {
                    command.isEnabled = true
                    progress.visibility = View.INVISIBLE
                }

                if (viewModel.getScriptFromIntent(intent) != null)
                    executeFromScript()

                viewModel.getAdb().shellProcess.waitFor()
                viewModel.getAdb().debug("Shell has died")

                runOnUiThread {
                    command.isEnabled = false
                }
            }
        })

        progress.visibility = View.VISIBLE
        command.isEnabled = false

        with (getPreferences(Context.MODE_PRIVATE)) {
            if (viewModel.shouldWePair(this)) {
                pairingLatch = CountDownLatch(1)
                viewModel.getAdb().debug("Requesting pairing information")
                askToPair {
                    with (edit()) {
                        putBoolean("paired", true)
                        apply()
                    }
                    pairingLatch.countDown()
                }
            }
        }
    }

    private fun executeFromScript() {
        val code = viewModel.getScriptFromIntent(intent) ?: return

        /* Invalidate intent */
        intent.type = ""

        Snackbar.make(output, getString(R.string.snackbar_file_opened), Snackbar.LENGTH_SHORT)
            .setAction(getString(R.string.dismiss)) {}
            .show()

        viewModel.getAdb().sendScript(code)
    }

    private fun askToPair(callback: Runnable? = null) {
        pairDialog
            .create()
            .apply {
                setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.okay)) { _, _ ->
                    val port = findViewById<TextInputEditText>(R.id.port)!!.text.toString()
                    val code = findViewById<TextInputEditText>(R.id.code)!!.text.toString()

                    lifecycleScope.launch(Dispatchers.IO) {
                        viewModel.getAdb().debug("Requesting additional pairing information")
                        viewModel.getAdb().pair(port, code)

                        callback?.run()
                    }
                }
            }
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.help -> {
                helpDialog.show()
                true
            }
            R.id.share -> {
                try {
                    val uri = FileProvider.getUriForFile(
                        this,
                        BuildConfig.APPLICATION_ID + ".provider",
                        viewModel.getAdb().outputBufferFile
                    )
                    val intent = Intent(Intent.ACTION_SEND)
                    with (intent) {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "file/*"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Snackbar.make(output, getString(R.string.snackbar_intent_failed), Snackbar.LENGTH_SHORT)
                        .setAction(getString(R.string.dismiss)) {}
                        .show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }
}