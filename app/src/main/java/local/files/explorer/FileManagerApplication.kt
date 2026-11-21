package local.files.explorer

import android.app.Application
import local.files.explorer.fs.Backends
import local.files.explorer.ops.FileOperationService

/** Process start-up: registers the file-system backends and the file-operation notification channel. */
class FileManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Backends.init(this)
        FileOperationService.ensureChannel(this)
    }
}
