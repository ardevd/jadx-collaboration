package uk.oshawk.jadx.collaboration

import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class JGitTest {
    @Test
    fun testJGit() {
        val repo = Files.createTempDirectory("test-repo").toFile()
        val git = Git.init().setDirectory(repo).call()
        val file = File(repo, "test.txt")
        file.writeText("A")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("init").call()
        
        file.writeText("B")
        git.add().addFilepattern(".").call()
        
        val status = git.status().call()
        println("added: " + status.added)
        println("changed: " + status.changed)
        println("modified: " + status.modified)
    }
}
