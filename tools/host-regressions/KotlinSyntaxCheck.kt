import java.io.File
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtPsiFactory

@OptIn(org.jetbrains.kotlin.K1Deprecation::class, CompilerConfiguration.Internals::class)
fun main(args: Array<String>) {
    val disposable = Disposer.newDisposable()
    try {
        val environment = KotlinCoreEnvironment.createForProduction(disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
        val factory = KtPsiFactory(environment.project, false)
        var count = 0
        val errors = mutableListOf<String>()
        File(args.single()).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            count++
            val parsed = factory.createFile(file.name, file.readText())
            PsiTreeUtil.findChildrenOfType(parsed, PsiErrorElement::class.java).forEach { error -> errors += "${file.name}:${error.textOffset}: ${error.errorDescription}" }
        }
        check(errors.isEmpty()) { errors.joinToString("\n") }
        println("KOTLIN_SYNTAX_CHECK_OK files=$count; syntax only; Android type resolution remains a separate gate")
    } finally { Disposer.dispose(disposable) }
}
