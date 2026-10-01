package android.content


class Context(val filesDir: java.io.File) {
    val applicationContext get() = this
}
