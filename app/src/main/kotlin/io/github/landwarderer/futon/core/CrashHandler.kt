package io.github.landwarderer.futon.core

  import android.content.Context
  import android.content.Intent
  import java.io.File
  import java.io.PrintWriter
  import java.io.StringWriter

  /**
   * Global uncaught-exception handler.
   *
   * When any thread crashes with an unhandled Throwable this handler:
   *  1. Formats the full stack trace to a string.
   *  2. Writes it to a private file so it survives process death.
   *  3. Starts [CrashReportActivity] in a new task so the user sees a
   *     "Copy log" dialog immediately after the crash.
   *  4. Kills the current process.
   *
   * Install once, early in Application.onCreate():
   *
   *     CrashHandler.install(this)
   */
  object CrashHandler {

      private const val CRASH_LOG_FILENAME = "last_crash.txt"

      fun install(context: Context) {
          val appContext = context.applicationContext
          val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
          Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
              runCatching {
                  val log = buildCrashLog(thread, throwable)
                  saveCrashLog(appContext, log)
                  launchCrashReportActivity(appContext)
                  Thread.sleep(400)
              }
              defaultHandler?.uncaughtException(thread, throwable)
              android.os.Process.killProcess(android.os.Process.myPid())
          }
      }

      fun readSavedCrashLog(context: Context): String? =
          crashLogFile(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

      fun clearSavedCrashLog(context: Context) {
          crashLogFile(context).delete()
      }

      private fun buildCrashLog(thread: Thread, throwable: Throwable): String {
          val sw = StringWriter()
          val pw = PrintWriter(sw)
          pw.println("Thread: ${thread.name}")
          pw.println()
          throwable.printStackTrace(pw)
          pw.flush()
          return sw.toString()
      }

      private fun saveCrashLog(context: Context, log: String) {
          runCatching { crashLogFile(context).writeText(log) }
      }

      private fun launchCrashReportActivity(context: Context) {
          runCatching {
              val intent = Intent(context, CrashReportActivity::class.java).apply {
                  addFlags(
                      Intent.FLAG_ACTIVITY_NEW_TASK or
                      Intent.FLAG_ACTIVITY_CLEAR_TASK or
                      Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                  )
              }
              context.startActivity(intent)
          }
      }

      private fun crashLogFile(context: Context): File =
          File(context.filesDir, CRASH_LOG_FILENAME)
  }
  