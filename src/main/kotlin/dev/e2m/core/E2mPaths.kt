package dev.e2m.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Layout of the per-project `.e2m` folder:
 *
 * ```
 * .e2m/
 *   notes/<id>.md          shadow files referenced by `// e2m :f <id>`
 *   sessions/<name>.json   recorded, replayable debugger walkthroughs
 * ```
 */
object E2mPaths {
    const val DIR = ".e2m"
    const val NOTES = "notes"
    const val SESSIONS = "sessions"

    fun root(project: Project): Path =
        Paths.get(project.basePath ?: error("Project ${project.name} has no base path")).resolve(DIR)

    fun notesDir(project: Project): Path = root(project).resolve(NOTES)
    fun sessionsDir(project: Project): Path = root(project).resolve(SESSIONS)

    /** `regression/slope` -> `.e2m/notes/regression/slope.md`; refs with an extension are used verbatim. */
    fun notePath(project: Project, ref: String): Path {
        val clean = ref.trim().trimStart('/').replace('\\', '/')
        require(clean.isNotEmpty() && !clean.split('/').contains("..")) { "Invalid e2m note reference: '$ref'" }
        val withExt = if (clean.substringAfterLast('/').contains('.')) clean else "$clean.md"
        return notesDir(project).resolve(withExt)
    }

    fun sessionPath(project: Project, name: String): Path {
        val clean = name.trim().removeSuffix(".json")
        require(clean.matches(Regex("[A-Za-z0-9._-]+"))) { "Session names may only contain letters, digits, '.', '_' and '-'" }
        return sessionsDir(project).resolve("$clean.json")
    }

    fun findVirtual(path: Path): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)

    fun write(path: Path, text: String): VirtualFile? {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return findVirtual(path)?.also { it.refresh(false, false) }
    }

    /** Resolves an absolute or project-relative path to a file. */
    fun resolveFile(project: Project, file: String): VirtualFile? {
        val p = Paths.get(file)
        val abs = if (p.isAbsolute) p else Paths.get(project.basePath ?: return null).resolve(file)
        return findVirtual(abs.normalize())
    }

    /** Project-relative path when possible, absolute otherwise (always with '/'). */
    fun relative(project: Project, file: VirtualFile): String {
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        return base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
    }
}
