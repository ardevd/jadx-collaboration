package uk.oshawk.jadx.collaboration

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import jadx.api.JavaClass
import jadx.api.JavaNode
import jadx.api.data.ICodeRename
import jadx.api.data.IJavaNodeRef
import jadx.api.data.impl.JadxCodeData
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import jadx.api.plugins.events.types.NodeRenamedByUser
import jadx.gui.treemodel.JRenameNode
import jadx.gui.ui.MainWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import java.io.File
import java.io.FileNotFoundException
import java.util.*
import javax.swing.Icon
import javax.swing.JOptionPane
import kotlin.math.max

open class Plugin(
    // Use remote? Pluggable for testing.
    val conflictResolver: (context: JadxPluginContext, remote: RepositoryItem, local: RepositoryItem) -> Boolean? = ::dialogConflictResolver,
    val showDialogs: Boolean = true
) : JadxPlugin {
    private var cachedPassphrase: CharArray? = null

    private inner class JadxCredentialsProvider : CredentialsProvider() {
        override fun isInteractive() = showDialogs
        override fun supports(vararg items: CredentialItem): Boolean = true
        override fun get(uri: URIish?, vararg items: CredentialItem): Boolean {
            if (!showDialogs) return true
            var ok = true
            for (item in items) {
                if (item is CredentialItem.InformationalMessage) {
                    LOG.info { "SSH Info: ${item.promptText}" }
                } else if (item is CredentialItem.YesNoType) {
                    val result = JOptionPane.showConfirmDialog(null, item.promptText, "JADX Collaboration", JOptionPane.YES_NO_OPTION)
                    item.value = result == JOptionPane.YES_OPTION
                } else if (item is CredentialItem.CharArrayType) {
                    val isPassword = item is CredentialItem.Password || item.promptText?.contains("Passphrase", ignoreCase = true) == true || item.promptText?.contains("Password", ignoreCase = true) == true
                    
                    if (cachedPassphrase != null && isPassword) {
                        item.value = cachedPassphrase
                    } else {
                        val textField = if (isPassword) javax.swing.JPasswordField() else javax.swing.JTextField()
                        var displayPrompt = item.promptText ?: ""
                        if (displayPrompt == "keyEncryptedPrompt") displayPrompt = "Enter passphrase for SSH key"
                        val result = JOptionPane.showConfirmDialog(null, arrayOf(displayPrompt, textField), "JADX Collaboration", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
                        if (result == JOptionPane.OK_OPTION) {
                            val textChars = if (textField is javax.swing.JPasswordField) textField.password else textField.text.toCharArray()
                            item.value = textChars
                            if (isPassword) {
                                cachedPassphrase = textChars
                            }
                        } else {
                            ok = false
                        }
                    }
                } else if (item is CredentialItem.StringType) {
                    val isPassword = item.promptText?.contains("Passphrase", ignoreCase = true) == true || item.promptText?.contains("Password", ignoreCase = true) == true
                    
                    if (cachedPassphrase != null && isPassword) {
                        item.value = String(cachedPassphrase!!)
                    } else {
                        val textField = if (isPassword) javax.swing.JPasswordField() else javax.swing.JTextField()
                        var displayPrompt = item.promptText ?: ""
                        if (displayPrompt == "keyEncryptedPrompt") displayPrompt = "Enter passphrase for SSH key"
                        val result = JOptionPane.showConfirmDialog(null, arrayOf(displayPrompt, textField), "JADX Collaboration", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
                        if (result == JOptionPane.OK_OPTION) {
                            val text = if (textField is javax.swing.JPasswordField) String(textField.password) else textField.text
                            item.value = text
                            if (isPassword) {
                                cachedPassphrase = if (textField is javax.swing.JPasswordField) textField.password else textField.text.toCharArray()
                            }
                        } else {
                            ok = false
                        }
                    }
                } else {
                    ok = false
                }
            }
            return ok
        }
    }

    companion object {
        const val ID = "jadx-collaboration"
        val LOG = KotlinLogging.logger(ID)
        val GSON: Gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    }

    private val options = Options()
    private var context: JadxPluginContext? = null
    private val pluginScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    override fun getPluginInfo() = JadxPluginInfo(ID, "JADX Collaboration", "Collaboration support for JADX")

    private fun bypassJGitNLS() {
        try {
            val cacheField = Class.forName("org.eclipse.jgit.nls.GlobalBundleCache").getDeclaredField("cachedBundles")
            cacheField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cachedBundles = cacheField.get(null) as MutableMap<java.util.Locale, MutableMap<Class<*>, org.eclipse.jgit.nls.TranslationBundle>>
            
            // JGit uses NLS.ROOT_LOCALE as well as the default locale
            val localesToStub = listOf(java.util.Locale.getDefault(), java.util.Locale("", "", ""))
            
            val classesToStub = listOf(
                "org.eclipse.jgit.internal.JGitText",
                "org.eclipse.jgit.internal.transport.sshd.SshdText",
                "org.eclipse.jgit.transport.ssh.apache.internal.SshdText"
            )
            
            for (locale in localesToStub) {
                val localeBundles = cachedBundles.getOrPut(locale) { mutableMapOf() }
                for (className in classesToStub) {
                    try {
                        val clazz = Class.forName(className)
                        if (!localeBundles.containsKey(clazz)) {
                            val dummyBundle = clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as org.eclipse.jgit.nls.TranslationBundle
                            for (field in clazz.fields) {
                                if (field.type == String::class.java) {
                                    field.set(dummyBundle, field.name)
                                }
                            }
                            localeBundles[clazz] = dummyBundle
                        }
                    } catch (e: Exception) {
                        // Ignore missing classes
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn(e) { "Could not bypass JGit NLS" }
        }
    }

    override fun init(context: JadxPluginContext?) {
        this.context = context

        // Remove security providers added by previous plugin classloaders
        java.security.Security.removeProvider("EdDSA")
        java.security.Security.removeProvider("BC")

        bypassJGitNLS()
        SshSessionFactory.setInstance(SshdSessionFactory())

        this.context?.registerOptions(options)

        this.context?.guiContext?.addMenuAction("Pull") { runInBackground("JADX Collaboration: Pulling...") { this@Plugin.pull() } }
        this.context?.guiContext?.addMenuAction("Push") { runInBackground("JADX Collaboration: Pushing...") { this@Plugin.push() } }

        this.context?.guiContext?.registerGlobalKeyBinding(
            "$ID.pull",
            "ctrl BACK_SLASH"
        ) { runInBackground("JADX Collaboration: Pulling...") { this@Plugin.pull() } }
        this.context?.guiContext?.registerGlobalKeyBinding(
            "$ID.push",
            "ctrl shift BACK_SLASH"
        ) { runInBackground("JADX Collaboration: Pushing...") { this@Plugin.push() } }
    }

    private fun runInBackground(title: String, action: suspend () -> Unit) {
        val guiContext = context?.guiContext
        val mainWindow = guiContext?.mainFrame as? MainWindow
        val backgroundExecutor = mainWindow?.backgroundExecutor

        if (backgroundExecutor != null) {
            backgroundExecutor.execute(title) {
                val job = pluginScope.launch { action() }
                runBlocking { job.join() }
            }
        } else {
            pluginScope.launch { action() }
        }
    }

    private suspend fun uiRun(action: () -> Unit) {
        val guiContext = context?.guiContext
        if (guiContext != null) {
            suspendCancellableCoroutine { cont ->
                guiContext.uiRun(Runnable {
                    try {
                        action()
                        cont.resume(Unit)
                    } catch (e: Throwable) {
                        cont.resumeWithException(e)
                    }
                })
            }
        } else {
            withContext(Dispatchers.Main) {
                action()
            }
        }
    }

    private suspend fun showError(message: String, title: String = "JADX Collaboration") {
        if (!showDialogs) return
        uiRun { JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE) }
    }

    private suspend fun showInfo(message: String, title: String = "JADX Collaboration") {
        if (!showDialogs) return
        uiRun { JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE) }
    }

    private fun <R> readRepository(suffix: String, default: R): R? {
        if (options.repository.isEmpty()) {
            LOG.error { "No repository file is set. Configure it in settings." }
            return null
        }

        val repositoryFile = File("${options.repository}$suffix")
        return try {
            val repository = repositoryFile.reader().use {
                GSON.fromJson(it, default!!::class.java) ?: default
            }

            LOG.info { "readRepository: read ${options.repository}$suffix" }

            repository
        } catch (_: FileNotFoundException) {
            LOG.info { "readRepository: using empty ${options.repository}$suffix" }
            default
        } catch (e: Exception) {
            LOG.info { e }
            LOG.error { "Repository file (${options.repository}$suffix) corrupt. Requires manual fixing." }
            null
        }
    }

    private fun readLocalRepository() = readRepository(".local", LocalRepository())
    private fun readRemoteRepository() = readRepository("", RemoteRepository())

    private fun <R> writeRepository(suffix: String, repository: R): Unit? {
        if (options.repository.isEmpty()) {
            LOG.error { "No repository file is set. Configure it in settings." }
            return null
        }

        val repositoryFile = File("${options.repository}$suffix")
        return try {
            repositoryFile.writer().use {
                GSON.toJson(repository, it)
            }

            LOG.info { "writeRepository: written ${options.repository}$suffix" }
        } catch (_: FileNotFoundException) {
            LOG.error { "Repository file (${options.repository}$suffix) invalid path." }
            null
        } catch (e: Exception) {
            LOG.info { e }
            LOG.error { "Repository file (${options.repository}$suffix) corrupt. Requires manual fixing." }
            null
        }
    }

    private fun writeLocalRepository(repository: LocalRepository) = writeRepository(".local", repository)
    private fun writeRemoteRepository(repository: RemoteRepository) = writeRepository("", repository)

    private fun getProjectRenames(): List<ProjectRename> {
        return this.context!!.args.codeData.renames
            .map { ProjectRename(it) }
            .sortedBy { it.identifier }
    }

    private fun setProjectRenames(projectRenames: List<ProjectRename>) {
        (this.context!!.args.codeData as JadxCodeData).renames =
            projectRenames.map { it.convert() }  // The convert is needed due to interface replacement. I wish it wasn't.
    }

    private fun getProjectComments(): List<ProjectComment> {
        return this.context!!.args.codeData.comments
            .map { ProjectComment(it) }
            .sortedBy { it.identifier }
    }

    private fun setProjectComments(projectComments: List<ProjectComment>) {
        (this.context!!.args.codeData as JadxCodeData).comments =
            projectComments.map { it.convert() }  // The convert is needed due to interface replacement. I wish it wasn't.
    }

    private fun projectToLocalRepositoryInternal(
        localRepositoryUuid: UUID,
        projectItems: List<ProjectItem>,
        oldLocalRepositoryItems: List<RepositoryItem>
    ): List<RepositoryItem> {
        var projectItemsIndex = 0
        var oldLocalRepositoryItemsIndex = 0
        val newLocalRepositoryItems = mutableListOf<RepositoryItem>()

        while (projectItemsIndex != projectItems.size || oldLocalRepositoryItemsIndex != oldLocalRepositoryItems.size) {
            val projectItem = projectItems.getOrNull(projectItemsIndex)
            val oldLocalRepositoryItem = oldLocalRepositoryItems.getOrNull(oldLocalRepositoryItemsIndex)

            // We will need this in all but one case. Not the most efficient (especially with the clone), but this is not going to be the bottleneck.
            val updatedVersionVector = oldLocalRepositoryItem?.versionVector?.toMutableMap() ?: mutableMapOf()
            updatedVersionVector.merge(localRepositoryUuid, 1L, Long::plus)

            newLocalRepositoryItems.add(
                when {
                    projectItem == null || (oldLocalRepositoryItem != null && projectItem.identifier > oldLocalRepositoryItem.identifier) -> {
                        // Local repository item not present in project. Must have been deleted.
                        oldLocalRepositoryItemsIndex++
                        oldLocalRepositoryItem!!.deleted(updatedVersionVector)
                    }

                    oldLocalRepositoryItem == null || (projectItem != null && projectItem.identifier < oldLocalRepositoryItem.identifier) -> {
                        // Project item not present in local repository. Add it.
                        projectItemsIndex++
                        projectItem.repositoryItem(updatedVersionVector)
                    }

                    else -> {
                        projectItemsIndex++
                        oldLocalRepositoryItemsIndex++

                        if (projectItem.matches(oldLocalRepositoryItem)) {
                            // No change. Keep the local repository item (no version vector change).
                            oldLocalRepositoryItem
                        } else {
                            // Change. Replace with the project item.
                            projectItem.repositoryItem(updatedVersionVector)
                        }
                    }
                }
            )
        }

        return newLocalRepositoryItems
    }

    private fun projectToLocalRepository(localRepository: LocalRepository) {
        if (options.username.isNotEmpty()) {
            localRepository.users[localRepository.uuid] = options.username
        }

        val projectRenames = getProjectRenames()
        LOG.info { "projectToLocalRepository: ${projectRenames.size} project renames" }
        LOG.info { "projectToLocalRepository: ${localRepository.renames.size} old local repository renames" }

        localRepository.renames =
            projectToLocalRepositoryInternal(localRepository.uuid, projectRenames, localRepository.renames)
                .map { it as RepositoryRename }
                .toMutableList()

        LOG.info { "projectToLocalRepository: ${localRepository.renames.size} new local repository renames" }

        val projectComments = getProjectComments()
        LOG.info { "projectToLocalRepository: ${projectComments.size} project comments" }

        LOG.info { "projectToLocalRepository: ${localRepository.comments.size} old local repository comments" }

        localRepository.comments =
            projectToLocalRepositoryInternal(localRepository.uuid, projectComments, localRepository.comments)
                .map { it as RepositoryComment }
                .toMutableList()

        LOG.info { "projectToLocalRepository: ${localRepository.comments.size} new local repository comments" }
    }

    private fun remoteRepositoryToLocalRepositoryInternal(
        remoteRepositoryItems: List<RepositoryItem>,
        oldLocalRepositoryItems: List<RepositoryItem>
    ): Pair<List<RepositoryItem>, Boolean>? {
        var remoteRepositoryItemsIndex = 0
        var oldLocalRepositoryItemsIndex = 0
        val newLocalRepositoryItems = mutableListOf<RepositoryItem>()
        var conflict = false

        while (remoteRepositoryItemsIndex != remoteRepositoryItems.size || oldLocalRepositoryItemsIndex != oldLocalRepositoryItems.size) {
            val remoteRepositoryItem = remoteRepositoryItems.getOrNull(remoteRepositoryItemsIndex)
            val oldLocalRepositoryItem = oldLocalRepositoryItems.getOrNull(oldLocalRepositoryItemsIndex)

            newLocalRepositoryItems.add(when {
                remoteRepositoryItem == null || (oldLocalRepositoryItem != null && remoteRepositoryItem.identifier > oldLocalRepositoryItem.identifier) -> {
                    // Local repository item not present in remote repository. Keep it as is.
                    oldLocalRepositoryItemsIndex++
                    assert(oldLocalRepositoryItem!!.versionVector.size == 1) // If an item was deleted on remote, an entry should still be present, but with a null new name.
                    oldLocalRepositoryItem
                }

                oldLocalRepositoryItem == null || (remoteRepositoryItem != null && remoteRepositoryItem.identifier < oldLocalRepositoryItem.identifier) -> {
                    // Remote repository item not present in local repository. Add it (again, deletions would be explicit),
                    remoteRepositoryItemsIndex++
                    remoteRepositoryItem
                }

                else -> {
                    remoteRepositoryItemsIndex++
                    oldLocalRepositoryItemsIndex++

                    // Compare version vectors and calculate the new one.
                    var remoteRepositoryGreater = 0
                    var oldLocalRepositoryGreater = 0
                    val updatedVersionVector = mutableMapOf<UUID, Long>()
                    for (key in remoteRepositoryItem.versionVector.keys.union(oldLocalRepositoryItem.versionVector.keys)) {
                        val remoteRepositoryValue = remoteRepositoryItem.versionVector.getOrDefault(key, 0L)
                        val oldLocalRepositoryValue = oldLocalRepositoryItem.versionVector.getOrDefault(key, 0L)

                        if (remoteRepositoryValue > oldLocalRepositoryValue) {
                            remoteRepositoryGreater++
                        }

                        if (oldLocalRepositoryValue > remoteRepositoryValue) {
                            oldLocalRepositoryGreater++
                        }

                        updatedVersionVector[key] = max(remoteRepositoryValue, oldLocalRepositoryValue)
                    }

                    when {
                        (remoteRepositoryGreater == 0 && oldLocalRepositoryGreater == 0)
                                || remoteRepositoryItem.matches(oldLocalRepositoryItem) -> {
                            // Equal in version vector or value. Use remote (including vector) since our version effectively hasn't updated.
                            assert(remoteRepositoryItem.matches(oldLocalRepositoryItem))
                            remoteRepositoryItem
                        }

                        remoteRepositoryGreater == 0 -> {
                            // Local supersedes remote. Use local. Local vector equals updated vector.
                            oldLocalRepositoryItem
                        }

                        oldLocalRepositoryGreater == 0 -> {
                            // Remote supersedes local. Use remote. Remote vector equals updated vector.
                            remoteRepositoryItem
                        }

                        else -> {
                            // Conflict. Try and resolve
                            conflict = true
                            when (conflictResolver(context!!, remoteRepositoryItem, oldLocalRepositoryItem)) {
                                true -> remoteRepositoryItem  // Use remote (including vector) since our version effectively hasn't updated.
                                false -> oldLocalRepositoryItem.updated(updatedVersionVector)  // Use local with updated vector.
                                null -> {
                                    LOG.error { "Conflict resolution failed." }
                                    return null
                                }
                            }
                        }
                    }
                }
            })
        }

        return Pair(newLocalRepositoryItems, conflict)
    }

    private fun remoteRepositoryToLocalRepository(
        remoteRepository: RemoteRepository,
        localRepository: LocalRepository
    ): Boolean? {
        var conflict = false

        LOG.info { "remoteRepositoryToLocalRepository: ${remoteRepository.renames.size} remote repository renames" }
        LOG.info { "remoteRepositoryToLocalRepository: ${localRepository.renames.size} old local repository renames" }

        val renamesResult = remoteRepositoryToLocalRepositoryInternal(remoteRepository.renames, localRepository.renames)
        if (renamesResult == null) {
            return null
        } else {
            val (renames, newConflict) = renamesResult
            localRepository.renames = renames
                .map { it as RepositoryRename }
                .toMutableList()
            conflict = conflict or newConflict
        }

        LOG.info { "remoteRepositoryToLocalRepository: ${localRepository.renames.size} new local repository renames" }

        LOG.info { "remoteRepositoryToLocalRepository: ${remoteRepository.comments.size} remote repository comments" }
        LOG.info { "remoteRepositoryToLocalRepository: ${localRepository.comments.size} old local repository comments" }

        val commentsResult =
            remoteRepositoryToLocalRepositoryInternal(remoteRepository.comments, localRepository.comments)
        if (commentsResult == null) {
            return null
        } else {
            val (comments, newConflict) = commentsResult
            localRepository.comments = comments
                .map { it as RepositoryComment }
                .toMutableList()
            conflict = conflict or newConflict
        }

        LOG.info { "remoteRepositoryToLocalRepository: ${localRepository.comments.size} new local repository comments" }

        localRepository.users.putAll(remoteRepository.users)

        return conflict
    }

    private fun localRepositoryToProjectInternal(
        oldProjectItems: List<ProjectItem>,
        newProjectItems: List<ProjectItem>
    ): Set<String> {
        val classNamesDelta = mutableSetOf<String>()
        var oldProjectItemsIndex = 0
        var newProjectItemsIndex = 0
        while (oldProjectItemsIndex != oldProjectItems.size || newProjectItemsIndex != newProjectItems.size) {
            val oldProjectItem = oldProjectItems.getOrNull(oldProjectItemsIndex)
            val newProjectItem = newProjectItems.getOrNull(newProjectItemsIndex)

            when {
                (oldProjectItem == null || (newProjectItem != null && oldProjectItem.identifier > newProjectItem.identifier)) -> {
                    classNamesDelta.add(newProjectItem!!.identifier.nodeRef.declaringClass.substringBefore("$"))
                    newProjectItemsIndex++
                }

                (newProjectItem == null || (oldProjectItem != null && oldProjectItem.identifier < newProjectItem.identifier)) -> {
                    classNamesDelta.add(oldProjectItem.identifier.nodeRef.declaringClass.substringBefore("$"))
                    oldProjectItemsIndex++
                }

                else -> {
                    if (!oldProjectItem.matches(newProjectItem)) {
                        classNamesDelta.add(oldProjectItem.identifier.nodeRef.declaringClass.substringBefore("$"))
                    }

                    oldProjectItemsIndex++
                    newProjectItemsIndex++
                }
            }
        }

        return classNamesDelta
    }

    private fun shouldUpdate(query: String, delta: String): Boolean {
        return query == delta
                || query.startsWith("$delta.")  // Delta is a parent package of query.
                || query.startsWith("$delta$")  // Delta is a parent class of query.
    }

    private fun localRepositoryToProject(localRepository: LocalRepository) {
        val oldProjectRenames = getProjectRenames()
        LOG.info { "localRepositoryToProject: ${localRepository.renames.size} local repository renames" }
        LOG.info { "localRepositoryToProject: ${oldProjectRenames.size} old project renames" }

        val newProjectRenames = localRepository.renames.mapNotNull { it.convert() }
        setProjectRenames(newProjectRenames)
        LOG.info { "localRepositoryToProject: ${newProjectRenames.size} new project renames" }

        val oldProjectComments = getProjectComments()
        LOG.info { "localRepositoryToProject: ${localRepository.comments.size} local repository comments" }
        LOG.info { "localRepositoryToProject: ${oldProjectComments.size} old project comments" }

        val newProjectComments = localRepository.comments.mapNotNull { it.convert() }
        setProjectComments(newProjectComments)
        LOG.info { "localRepositoryToProject: ${newProjectComments.size} new project comments" }

        val classNamesDelta = mutableSetOf<String>()
        classNamesDelta.addAll(localRepositoryToProjectInternal(oldProjectRenames, newProjectRenames))
        classNamesDelta.addAll(localRepositoryToProjectInternal(oldProjectComments, newProjectComments))

        LOG.info { "localRepositoryToProject: ${classNamesDelta.size} classes changed" }

        val classesToUpdate = mutableListOf<JavaClass>()
        for (clazz in context!!.decompiler.classes) {
            if (classNamesDelta.any { classNameDelta -> shouldUpdate(clazz.rawName, classNameDelta) } || clazz.dependencies.any { subClass ->
                    classNamesDelta.any { classNameDelta -> shouldUpdate(subClass.rawName, classNameDelta) } }) {
                classesToUpdate.add(clazz)
                clazz.unload()
            }
        }

        LOG.info { "localRepositoryToProject: ${classesToUpdate.size} classes to update" }

        // A crafted rename node, with just enough implemented to get the classes we want to change to the rename service.
        val renameNode = object : JRenameNode {
            override fun getJavaNode(): JavaNode {
                throw NotImplementedError()
            }

            override fun getTitle(): String {
                throw NotImplementedError()
            }

            override fun getName(): String {
                throw NotImplementedError()
            }

            override fun getIcon(): Icon {
                throw NotImplementedError()
            }

            override fun canRename(): Boolean {
                throw NotImplementedError()
            }

            // A rename that should be impossible under normal circumstances, so it does not break the list of renames.
            override fun buildCodeRename(newName: String, renames: MutableSet<ICodeRename>) =
                ProjectRename(Identifier(NodeRef(IJavaNodeRef.RefType.PKG, "#", "#"), null), "").convert()

            override fun isValidName(newName: String): Boolean {
                throw NotImplementedError()
            }

            override fun removeAlias() {}

            override fun addUpdateNodes(toUpdate: MutableList<JavaNode>) {
                toUpdate.addAll(classesToUpdate)
            }

            override fun reload(mainWindow: MainWindow) {
                mainWindow.rebuildPackagesTree()
                mainWindow.reloadTree()
            }
        }

        val event = NodeRenamedByUser(null, "", "")
        event.renameNode = renameNode
        event.isResetName = true  // This will cause the rename not to be added to the list of renames.

        context!!.events().send(event)
    }

    private fun <T : RepositoryItem> countDifferences(list1: List<T>, list2: List<T>): Int {
        var changes = 0
        var i1 = 0
        var i2 = 0
        while (i1 != list1.size || i2 != list2.size) {
            val item1 = list1.getOrNull(i1)
            val item2 = list2.getOrNull(i2)
            when {
                item1 == null || (item2 != null && item1.identifier > item2.identifier) -> {
                    i2++
                    changes++
                }
                item2 == null || (item1 != null && item1.identifier < item2.identifier) -> {
                    i1++
                    changes++
                }
                else -> {
                    if (!item1.matches(item2)) changes++
                    i1++
                    i2++
                }
            }
        }
        return changes
    }

    private fun localRepositoryToRemoteRepository(
        localRepository: LocalRepository,
        remoteRepository: RemoteRepository
    ) {
        // Overwrite the remote repository with the remote repository (remote should have been merged into local beforehand).

        LOG.info { "localRepositoryToRemoteRepository: ${localRepository.renames.size} local repository renames" }
        LOG.info { "localRepositoryToRemoteRepository: ${remoteRepository.renames.size} old remote repository renames" }

        remoteRepository.renames = localRepository.renames

        LOG.info { "localRepositoryToRemoteRepository: ${remoteRepository.renames.size} new remote repository renames" }

        LOG.info { "localRepositoryToRemoteRepository: ${localRepository.comments.size} local repository comments" }
        LOG.info { "localRepositoryToRemoteRepository: ${remoteRepository.comments.size} old remote repository comments" }

        remoteRepository.comments = localRepository.comments

        LOG.info { "localRepositoryToRemoteRepository: ${remoteRepository.comments.size} new remote repository comments" }

        remoteRepository.users.putAll(localRepository.users)
    }

    private fun jgitPull(): Unit? {
        if (options.repository.isEmpty()) {
            LOG.error { "JGit pull failed: repository path is empty" }
            return null
        }
        val repoFile = File(options.repository).absoluteFile
        val repoParent = repoFile.parentFile ?: run {
            LOG.error { "JGit pull failed: could not determine parent directory" }
            return null
        }
        val repositoryBuilder = FileRepositoryBuilder().findGitDir(repoParent)
        val gitMetadataDir = repositoryBuilder.gitDir ?: run {
            LOG.error { "JGit pull failed: no Git repository found in or above ${repoParent.absolutePath}" }
            return null
        }
        val gitDir = gitMetadataDir.parentFile ?: run {
            LOG.error { "JGit pull failed: could not determine Git worktree from ${gitMetadataDir.absolutePath}" }
            return null
        }
        val previousClassLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = Plugin::class.java.classLoader
        try {
            Git.open(gitDir).use { git ->
                val pullCommand = git.pull()
                pullCommand.setCredentialsProvider(JadxCredentialsProvider())
                val pullResult = pullCommand.call()
                val mergeResult = pullResult.mergeResult
                val rebaseResult = pullResult.rebaseResult
                val hasMergeConflicts = mergeResult?.conflicts?.isNotEmpty() == true
                val mergeFailed = mergeResult != null && (!mergeResult.mergeStatus.isSuccessful || hasMergeConflicts)
                val rebaseFailed = rebaseResult != null && !rebaseResult.status.isSuccessful

                if (!pullResult.isSuccessful || mergeFailed || rebaseFailed) {
                    val mergeStatus = mergeResult?.mergeStatus?.toString() ?: "N/A"
                    val rebaseStatus = rebaseResult?.status?.toString() ?: "N/A"
                    val conflictFiles = mergeResult?.conflicts?.keys?.sorted()?.joinToString(", ")
                    LOG.error {
                        "JGit pull failed: isSuccessful=${pullResult.isSuccessful}, " +
                            "mergeStatus=$mergeStatus, rebaseStatus=$rebaseStatus" +
                            if (hasMergeConflicts) ", conflictFiles=$conflictFiles" else ""
                    }
                    return null
                }
                LOG.info { "JGit pull successful" }
            }
        } catch (e: Exception) {
            LOG.error(e) { "JGit pull exception" }
            return null
        } finally {
            Thread.currentThread().contextClassLoader = previousClassLoader
        }
        return Unit
    }

    private fun jgitPush(): Unit? {
        if (options.repository.isEmpty()) {
            LOG.error { "JGit push failed: repository path is empty" }
            return null
        }
        val repoFile = File(options.repository).absoluteFile
        val repoParent = repoFile.parentFile ?: run {
            LOG.error { "JGit push failed: could not determine parent directory" }
            return null
        }
        val repositoryBuilder = FileRepositoryBuilder().findGitDir(repoParent)
        val gitMetadataDir = repositoryBuilder.gitDir ?: run {
            LOG.error { "JGit push failed: no Git repository found in or above ${repoParent.absolutePath}" }
            return null
        }
        val gitDir = gitMetadataDir.parentFile ?: run {
            LOG.error { "JGit push failed: could not determine Git worktree from ${gitMetadataDir.absolutePath}" }
            return null
        }
        val previousClassLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = Plugin::class.java.classLoader
        try {
            Git.open(gitDir).use { git ->
                val repoPath = repoFile.relativeTo(gitDir).path.replace(File.separatorChar, '/')
                git.add().addFilepattern(repoPath).call()

                val stagingStatus = git.status().call()
                if (stagingStatus.added.isEmpty() && stagingStatus.changed.isEmpty()) {
                    LOG.info { "JGit push skipped: no content changes to commit" }
                    return Unit
                }

                git.commit().setMessage("jadx-collaboration push").call()

                val pushCommand = git.push()
                pushCommand.setCredentialsProvider(JadxCredentialsProvider())
                val pushResults = pushCommand.call()
                for (pushResult in pushResults) {
                    for (refUpdate in pushResult.remoteUpdates) {
                        val refStatus = refUpdate.status
                        if (refStatus != RemoteRefUpdate.Status.OK &&
                            refStatus != RemoteRefUpdate.Status.UP_TO_DATE) {
                            LOG.error { "JGit push failed: ref ${refUpdate.remoteName} status=$refStatus message=${refUpdate.message}" }
                            return null
                        }
                    }
                }
                LOG.info { "JGit push successful" }
            }
        } catch (e: Exception) {
            LOG.error(e) { "JGit push exception" }
            return null
        } finally {
            Thread.currentThread().contextClassLoader = previousClassLoader
        }
        return Unit
    }

    internal open suspend fun pull() {
        // Update local repository with project changes.
        // Pull remote repository into local repository.
        // Update project from local repository.

        val localRepository = readLocalRepository() ?: run {
            showError("Pull failed: Could not read local repository.")
            return
        }

        projectToLocalRepository(localRepository)

        jgitPull() ?: run {
            showError("Pull failed: JGit pull failed.")
            return
        }

        val remoteRepository = readRemoteRepository() ?: run {
            showError("Pull failed: Could not read remote repository.")
            return
        }

        val oldLocalRenames = localRepository.renames.toList()
        val oldLocalComments = localRepository.comments.toList()

        remoteRepositoryToLocalRepository(remoteRepository, localRepository) ?: run {
            showError("Pull failed: Conflict resolution failed.")
            return
        }

        val pulledChanges = countDifferences(oldLocalRenames, localRepository.renames) +
                countDifferences(oldLocalComments, localRepository.comments)

        writeLocalRepository(localRepository) ?: run {
            showError("Pull failed: Could not write local repository.")
            return
        }

        uiRun { localRepositoryToProject(localRepository) }
        showInfo("Pull completed successfully. ($pulledChanges changes pulled)")
    }

    internal open suspend fun push() {
        // Update local repository with project changes.
        // Pull remote repository into local repository until there is no conflict.

        var pulledChanges = 0
        var pushedChanges: Int? = null
        var localRepository: LocalRepository? = null
        for (i in 1..5) {
            pulledChanges = 0
            localRepository = readLocalRepository() ?: run {
                showError("Push failed: Could not read local repository.")
                return
            }

            projectToLocalRepository(localRepository)

            // Repeat if there is a conflict. Should limit the chance of race conditions, since the user may take time to resolve conflicts.
            var remoteRepository: RemoteRepository
            do {
                jgitPull() ?: run {
                    showError("Push failed: JGit pull failed.")
                    return
                }

                remoteRepository = readRemoteRepository() ?: run {
                    showError("Push failed: Could not read remote repository.")
                    return
                }

                val oldLocalRenames = localRepository.renames.toList()
                val oldLocalComments = localRepository.comments.toList()

                val conflictResult = remoteRepositoryToLocalRepository(remoteRepository, localRepository)
                if (conflictResult == null) {
                    showError("Push failed: Conflict resolution failed.")
                    return
                }
                val conflict = conflictResult

                pulledChanges += countDifferences(oldLocalRenames, localRepository.renames) +
                        countDifferences(oldLocalComments, localRepository.comments)
            } while (conflict)

            val oldRemoteRenames = remoteRepository.renames.toList()
            val oldRemoteComments = remoteRepository.comments.toList()

            localRepositoryToRemoteRepository(localRepository, remoteRepository)

            pushedChanges = pushedChanges ?: (countDifferences(oldRemoteRenames, remoteRepository.renames) +
                    countDifferences(oldRemoteComments, remoteRepository.comments))

            writeRemoteRepository(remoteRepository) ?: run {
                showError("Push failed: Could not write remote repository.")
                return
            }

            jgitPush() ?: run {
                showError("Push failed: JGit push failed.")
                return
            }
            break
        }

        // Think it is a good idea to do this after the script. If something goes wrong, the on-disk local repository should allow us to recover.
        writeLocalRepository(localRepository!!) ?: run {
            showError("Push failed: Could not write local repository.")
            return
        }

        uiRun { localRepositoryToProject(localRepository) }
        showInfo("Push completed successfully. (${pushedChanges ?: 0} changes pushed, $pulledChanges changes pulled)")
    }
}
