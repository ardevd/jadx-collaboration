package uk.oshawk.jadx.collaboration

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.data.IJavaNodeRef
import jadx.api.data.impl.JadxCodeData
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.events.IJadxEvents
import jadx.api.plugins.gui.JadxGuiContext
import jadx.gui.jobs.BackgroundExecutor
import jadx.gui.ui.MainWindow
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.mockito.kotlin.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*

class PluginMockery(conflictResolver: (context: JadxPluginContext, remote: RepositoryItem, local: RepositoryItem) -> Boolean?) {
    val jadxCodeData = JadxCodeData()
    val jadxArgs = mock<JadxArgs> {
        on { codeData } doReturn jadxCodeData
    }

    val jadxDecompiler = mock<JadxDecompiler> {
        on { classes } doReturn listOf()
    }

    val iJadxEvents = mock<IJadxEvents> {
        doNothing().on { send(any()) }  // Don't like that this has a different syntax.
    }

    var pull: Runnable? = null
    var push: Runnable? = null
    val jadxGuiContext = mock<JadxGuiContext> {
        on { addMenuAction(any(), any()) } doAnswer {
            val name = it.getArgument<String>(0)
            val action = it.getArgument<Runnable>(1)
            when (name) {
                "Pull" -> {
                    pull = action
                }

                "Push" -> {
                    push = action
                }
            }
        }
        on { uiRun(any()) } doAnswer { it.getArgument<Runnable>(0).run() }
    }

    var options: Options? = null
    val jadxPluginContext = mock<JadxPluginContext> {
        on { args } doReturn jadxArgs
        on { decompiler } doReturn jadxDecompiler
        on { events() } doReturn iJadxEvents
        on { guiContext } doReturn jadxGuiContext
        on { registerOptions(any()) } doAnswer { options = it.getArgument<Options>(0) }
    }

    val plugin = Plugin(conflictResolver, false)

    init {
        plugin.init(jadxPluginContext)
    }

    var renames: List<ProjectRename>
        get() = jadxArgs.codeData.renames
            .map { ProjectRename(it) }
            .sortedBy { it.identifier }  // For comparison.
        set(value) {
            (jadxArgs.codeData as JadxCodeData).renames = value.map { it.convert() }
        }
}

class RepositoryMockery(
    conflictResolver: (context: JadxPluginContext, remote: RepositoryItem, local: RepositoryItem) -> Boolean? = { _, _, _ ->
        fail(
            "Conflict!"
        )
    }
) {
    val leftDirectory = createTempDirectory("left")
    val leftRemote = Path(leftDirectory.toString(), "repository")
    val leftLocal = Path(leftDirectory.toString(), "repository.local")

    val rightDirectory = createTempDirectory("right")
    val rightRemote = Path(rightDirectory.toString(), "repository")
    val rightLocal = Path(rightDirectory.toString(), "repository.local")

    val remoteDirectory = createTempDirectory("remote")
    val bareGitDir = File(remoteDirectory.toFile(), "repo.git")

    val leftPlugin = PluginMockery(conflictResolver)
    val rightPlugin = PluginMockery(conflictResolver)

    init {
        // On some platforms, directories seem to be reused.
        leftRemote.deleteIfExists()
        leftLocal.deleteIfExists()
        rightRemote.deleteIfExists()
        rightLocal.deleteIfExists()
    }

    init {
        initBareRepo()
        cloneRepo(leftDirectory.toFile())
        cloneRepo(rightDirectory.toFile())

        leftPlugin.options!!.repository = leftRemote.toString()
        rightPlugin.options!!.repository = rightRemote.toString()
    }

    private fun setGitIdentity(git: Git) {
        git.repository.config.apply {
            setString("user", null, "name", "Test User")
            setString("user", null, "email", "test@example.com")
            save()
        }
    }

    private fun initBareRepo() {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bareGitDir).call().close()
        val tempDir = createTempDirectory("init_tmp").toFile()
        try {
            Git.init().setInitialBranch("main").setDirectory(tempDir).call().use { git ->
                setGitIdentity(git)
                git.remoteAdd().setName("origin").setUri(URIish(bareGitDir.toURI().toURL())).call()
                git.commit().setMessage("Initial commit").setAllowEmpty(true).call()
                git.push().setRemote("origin")
                    .setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun cloneRepo(targetDir: File) {
        Git.cloneRepository().setURI(bareGitDir.toURI().toString()).setDirectory(targetDir)
            .setBranch("refs/heads/main")
            .call().use { git -> setGitIdentity(git) }
    }

    fun leftPull() = runBlocking { leftPlugin.plugin.pull() }

    fun rightPull() = runBlocking { rightPlugin.plugin.pull() }

    fun leftPush() = runBlocking { leftPlugin.plugin.push() }

    fun rightPush() = runBlocking { rightPlugin.plugin.push() }
}

class PluginTest {
    fun genRename(i: Int) = ProjectRename(Identifier(NodeRef(IJavaNodeRef.RefType.CLASS, "a$i", "b$i"), null), "c$i")

    fun modRename(rename: ProjectRename) = ProjectRename(rename.identifier, "${rename.newName}m")

    fun assertProjectRenamesEqual(left: Iterable<ProjectRename>, right: Iterable<ProjectRename>) {
        val leftIterator = left.iterator()
        val rightIterator = right.iterator()
        while (leftIterator.hasNext() && rightIterator.hasNext()) {
            val leftNext = leftIterator.next()
            val rightNext = rightIterator.next()
            assertEquals(0, leftNext.identifier.compareTo(rightNext.identifier), "Element mismatch in iterator.")
            assertEquals(leftNext.newName, rightNext.newName, "Element mismatch in iterator.")
        }

        assertFalse(leftIterator.hasNext(), "Size mismatch in iterator (left > right).")
        assertFalse(rightIterator.hasNext(), "Size mismatch in iterator (left < right).")
    }

    @Test
    fun basic0() {
        // l.set([0, 1])
        // l.push()
        // r.pull()
        // assert(l == [0, 1])
        // assert(l == r)

        val mockery = RepositoryMockery()

        mockery.leftPlugin.renames = listOf(genRename(0), genRename(1))
        mockery.leftPush()

        mockery.rightPull()

        assertProjectRenamesEqual(mockery.leftPlugin.renames, listOf(genRename(0), genRename(1)))
        assertProjectRenamesEqual(mockery.leftPlugin.renames, mockery.rightPlugin.renames)
    }

    @Test
    fun basic1() {
        // l.set([0])
        // l.push()
        // r.set([1])
        // r.push()
        // assert(l == [0])
        // assert(r == [0, 1])
        // l.pull()
        // assert(l == [0, 1])
        // assert(l == r)

        val mockery = RepositoryMockery()

        mockery.leftPlugin.renames = listOf(genRename(0))
        mockery.leftPush()

        mockery.rightPlugin.renames = listOf(genRename(1))
        mockery.rightPush()

        assertProjectRenamesEqual(mockery.leftPlugin.renames, listOf(genRename(0)))
        assertProjectRenamesEqual(mockery.rightPlugin.renames, listOf(genRename(0), genRename(1)))

        mockery.leftPull()

        assertProjectRenamesEqual(mockery.leftPlugin.renames, listOf(genRename(0), genRename(1)))
        assertProjectRenamesEqual(mockery.leftPlugin.renames, mockery.rightPlugin.renames)
    }

    @Test
    fun basic2() {
        // l.set([0])
        // l.push()
        // l.set([0m])
        // l.push()
        // r.pull()
        // assert(l == [0m])
        // assert(l == r)

        val mockery = RepositoryMockery()

        mockery.leftPlugin.renames = listOf(genRename(0))
        mockery.leftPush()

        mockery.leftPlugin.renames = listOf(modRename(genRename(0)))
        mockery.leftPush()

        mockery.rightPull()

        assertProjectRenamesEqual(mockery.leftPlugin.renames, listOf(modRename(genRename(0))))
        assertProjectRenamesEqual(mockery.leftPlugin.renames, mockery.rightPlugin.renames)
    }

    //
    // The following test conflicts. Conflicts should only occur where indicated.
    //

    @Test
    fun conflict0() {
        // l.set([0])
        // l.push()
        // r.push()
        // l.set([0m])
        // l.push()
        // r.pull()

        val mockery = RepositoryMockery()

        mockery.leftPlugin.renames = listOf(genRename(0))
        mockery.leftPush()

        mockery.rightPush()

        mockery.leftPlugin.renames = listOf(modRename(genRename(0)))
        mockery.leftPush()

        mockery.rightPull()
    }

    @Test
    fun conflict1() {
        // l.set([0])
        // l.push()
        // assert(conflicts == 0)
        // r.set([0m])
        // r.push()  // CONFLICT
        // assert(conflicts == 1)

        var conflicts = 0

        val mockery = RepositoryMockery { _, _, _ ->
            conflicts++
            true
        }

        mockery.leftPlugin.renames = listOf(genRename(0))
        mockery.leftPush()
        assertEquals(0, conflicts)

        mockery.rightPlugin.renames = listOf(modRename(genRename(0)))
        mockery.rightPush()
        assertEquals(1, conflicts)
    }

    @Test
    fun conflict2() {
        // l.set([0])
        // l.push()
        // assert(conflicts == 0)
        // r.set([0m])
        // r.push()  // CONFLICT, CHOOSE R
        // assert(conflicts == 1)
        // l.push()
        // assert(conflicts == 1)

        var conflicts = 0

        val mockery = RepositoryMockery { _, _, _ ->
            conflicts++
            false
        }

        mockery.leftPlugin.renames = listOf(genRename(0))
        mockery.leftPush()
        assertEquals(0, conflicts)

        mockery.rightPlugin.renames = listOf(modRename(genRename(0)))
        mockery.rightPush()
        assertEquals(1, conflicts)

        mockery.leftPush()
        assertEquals(1, conflicts)
    }

    @Test
    fun conflict3() {
        // l.set([0])
        // l.push()
        // assert(conflicts == 0)
        // r.set([0m])
        // r.push()  // CONFLICT, CHOOSE R
        // assert(conflicts == 1)
        // l.set([0mm])
        // l.push()  // CONFLICT
        // assert(conflicts == 2)

        var conflicts = 0

        val mockery = RepositoryMockery { _, _, _ ->
            conflicts++
            false
        }

        mockery.leftPlugin.renames = listOf(genRename(0))
        mockery.leftPush()
        assertEquals(0, conflicts)

        mockery.rightPlugin.renames = listOf(modRename(genRename(0)))
        mockery.rightPush()
        assertEquals(1, conflicts)

        mockery.leftPlugin.renames = listOf(modRename(modRename(genRename(0))))
        mockery.leftPush()
        assertEquals(2, conflicts)
    }

    @Test
    fun conflict4() {
        // l.set([0])
        // l.push()
        // assert(conflicts == 0)
        // r.set([0m])
        // r.push()  // CONFLICT, CHOOSE L
        // assert(conflicts == 1)
        // l.set([0mm])
        // l.push()
        // assert(conflicts == 1)

        var conflicts = 0

        val mockery = RepositoryMockery { _, _, _ ->
            conflicts++
            true
        }

        mockery.leftPlugin.renames = listOf(genRename(0))
        mockery.leftPush()
        assertEquals(0, conflicts)

        mockery.rightPlugin.renames = listOf(modRename(genRename(0)))
        mockery.rightPush()
        assertEquals(1, conflicts)

        mockery.leftPlugin.renames = listOf(modRename(modRename(genRename(0))))
        mockery.leftPush()
        assertEquals(1, conflicts)
    }

    @Test
    fun conflict5() {
        // l.set([0])
        // l.push()
        // assert(conflicts == 0)
        // r.set([0m])
        // r.push()  // CONFLICT, CHOOSE R
        // assert(conflicts == 1)
        // l.set([0m])
        // l.push()
        // assert(conflicts == 1)

        var conflicts = 0

        val mockery = RepositoryMockery { _, _, _ ->
            conflicts++
            false
        }

        mockery.leftPlugin.renames = listOf(genRename(0))
        mockery.leftPush()
        assertEquals(0, conflicts)

        mockery.rightPlugin.renames = listOf(modRename(genRename(0)))
        mockery.rightPush()
        assertEquals(1, conflicts)

        mockery.leftPlugin.renames = listOf(modRename(genRename(0)))
        mockery.leftPush()
        assertEquals(1, conflicts)
    }
}

class BackgroundExecutorTest {

    companion object {
        private const val TEST_TIMEOUT_MS = 5000L
    }

    private fun buildPluginWithBackgroundExecutor(
        mockBackgroundExecutor: BackgroundExecutor
    ): Triple<Plugin, Runnable?, Runnable?> {
        val jadxCodeData = JadxCodeData()
        val jadxArgs = mock<JadxArgs> { on { codeData } doReturn jadxCodeData }
        val jadxDecompiler = mock<JadxDecompiler> { on { classes } doReturn listOf() }
        val iJadxEvents = mock<IJadxEvents> { doNothing().on { send(any()) } }

        val mockMainWindow = mock<MainWindow> {
            on { backgroundExecutor } doReturn mockBackgroundExecutor
        }

        var pullAction: Runnable? = null
        var pushAction: Runnable? = null
        val jadxGuiContext = mock<JadxGuiContext> {
            on { addMenuAction(any(), any()) } doAnswer {
                when (it.getArgument<String>(0)) {
                    "Pull" -> pullAction = it.getArgument(1)
                    "Push" -> pushAction = it.getArgument(1)
                }
            }
            on { mainFrame } doReturn mockMainWindow
            on { uiRun(any()) } doAnswer { it.getArgument<Runnable>(0).run() }
        }

        val jadxPluginContext = mock<JadxPluginContext> {
            on { args } doReturn jadxArgs
            on { decompiler } doReturn jadxDecompiler
            on { events() } doReturn iJadxEvents
            on { guiContext } doReturn jadxGuiContext
            on { registerOptions(any()) } doAnswer { }
        }

        val plugin = Plugin({ _, _, _ -> null }, false)
        plugin.init(jadxPluginContext)
        return Triple(plugin, pullAction, pushAction)
    }

    @Test
    fun pullMenuActionUsesBackgroundExecutor() {
        val executedTitles = mutableListOf<String>()
        val mockBackgroundExecutor = mock<BackgroundExecutor> {
            on { execute(any<String>(), any<Runnable>()) } doAnswer { inv ->
                executedTitles.add(inv.getArgument(0))
                inv.getArgument<Runnable>(1).run()
            }
        }

        val (_, pullAction, _) = buildPluginWithBackgroundExecutor(mockBackgroundExecutor)
        pullAction?.run()

        assertEquals(listOf("JADX Collaboration: Pulling..."), executedTitles)
    }

    @Test
    fun pushMenuActionUsesBackgroundExecutor() {
        val executedTitles = mutableListOf<String>()
        val mockBackgroundExecutor = mock<BackgroundExecutor> {
            on { execute(any<String>(), any<Runnable>()) } doAnswer { inv ->
                executedTitles.add(inv.getArgument(0))
                inv.getArgument<Runnable>(1).run()
            }
        }

        val (_, _, pushAction) = buildPluginWithBackgroundExecutor(mockBackgroundExecutor)
        pushAction?.run()

        assertEquals(listOf("JADX Collaboration: Pushing..."), executedTitles)
    }

    @Test
    fun executionIsSerializedThroughPluginScope() {
        // Verify that actions submitted via backgroundExecutor are routed through
        // pluginScope (limitedParallelism(1)), so they cannot execute concurrently.
        val activeCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val firstStarted = CountDownLatch(1)
        val firstMayFinish = CountDownLatch(1)

        val mockBackgroundExecutor = mock<BackgroundExecutor> {
            on { execute(any<String>(), any<Runnable>()) } doAnswer { inv ->
                // Execute the runnable on a background thread to simulate real executor behavior
                Thread { inv.getArgument<Runnable>(1).run() }.also { it.start() }.join(TEST_TIMEOUT_MS)
            }
        }

        val jadxCodeData = JadxCodeData()
        val jadxArgs = mock<JadxArgs> { on { codeData } doReturn jadxCodeData }
        val jadxDecompiler = mock<JadxDecompiler> { on { classes } doReturn listOf() }
        val iJadxEvents = mock<IJadxEvents> { doNothing().on { send(any()) } }

        val mockMainWindow = mock<MainWindow> {
            on { backgroundExecutor } doReturn mockBackgroundExecutor
        }

        var pullAction: Runnable? = null
        var pushAction: Runnable? = null
        val jadxGuiContext = mock<JadxGuiContext> {
            on { addMenuAction(any(), any()) } doAnswer {
                when (it.getArgument<String>(0)) {
                    "Pull" -> pullAction = it.getArgument(1)
                    "Push" -> pushAction = it.getArgument(1)
                }
            }
            on { mainFrame } doReturn mockMainWindow
            on { uiRun(any()) } doAnswer { it.getArgument<Runnable>(0).run() }
        }

        val jadxPluginContext = mock<JadxPluginContext> {
            on { args } doReturn jadxArgs
            on { decompiler } doReturn jadxDecompiler
            on { events() } doReturn iJadxEvents
            on { guiContext } doReturn jadxGuiContext
            on { registerOptions(any()) } doAnswer { }
        }

        // Custom plugin that tracks concurrent coroutine executions
        val plugin = object : Plugin({ _, _, _ -> null }, false) {
            override suspend fun pull() {
                val current = activeCount.incrementAndGet()
                maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                firstStarted.countDown()
                firstMayFinish.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                activeCount.decrementAndGet()
            }

            override suspend fun push() {
                val current = activeCount.incrementAndGet()
                maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                activeCount.decrementAndGet()
            }
        }
        plugin.init(jadxPluginContext)

        // Start pull action in a thread; it will block until firstMayFinish is released
        val pullThread = Thread { pullAction?.run() }.also { it.start() }

        // Wait for pull to start, then trigger push concurrently
        assertTrue(firstStarted.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS), "Pull action did not start in time")
        val pushThread = Thread { pushAction?.run() }.also { it.start() }

        // Allow the first action to complete, then the second should run
        firstMayFinish.countDown()

        pullThread.join(TEST_TIMEOUT_MS)
        pushThread.join(TEST_TIMEOUT_MS)

        assertFalse(pullThread.isAlive, "Pull thread did not complete")
        assertFalse(pushThread.isAlive, "Push thread did not complete")
        assertEquals(1, maxConcurrent.get(), "Actions ran concurrently — serialization failed")
    }
}
