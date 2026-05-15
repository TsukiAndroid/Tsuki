package io.github.landwarderer.futon.core

  import android.content.ClipData
  import android.content.ClipboardManager
  import android.os.Bundle
  import android.widget.Button
  import android.widget.TextView
  import android.widget.Toast
  import androidx.appcompat.app.AppCompatActivity
  import io.github.landwarderer.futon.R

  /**
   * Full-screen activity shown immediately after an unhandled crash.
   *
   * Displays the saved stack trace with two actions:
   *  - **Copy log** — copies the raw text to the clipboard
   *  - **Close** — dismisses the activity
   *
   * The activity is started by [CrashHandler] with FLAG_ACTIVITY_NEW_TASK so it
   * appears before the system "App stopped" dialog. It is declared with
   * android:excludeFromRecents="true" so it does not pollute the recents list.
   */
  class CrashReportActivity : AppCompatActivity() {

      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          setContentView(R.layout.activity_crash_report)

          val log = CrashHandler.readSavedCrashLog(this) ?: run {
              finish()
              return
          }

          findViewById<TextView>(R.id.crash_log_text).text = log

          findViewById<Button>(R.id.btn_copy_log).setOnClickListener {
              val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
              cm.setPrimaryClip(ClipData.newPlainText("crash_log", log))
              Toast.makeText(this, R.string.crash_log_copied, Toast.LENGTH_SHORT).show()
          }

          findViewById<Button>(R.id.btn_close_crash).setOnClickListener {
              CrashHandler.clearSavedCrashLog(this)
              finish()
          }
      }

      override fun onDestroy() {
          super.onDestroy()
          CrashHandler.clearSavedCrashLog(this)
      }
  }
  