package uk.oshawk.jadx.collaboration

import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class JGitPushTest {
    @Test
    fun testJGitPushSkipped() {
        val repo = Files.createTempDirectory("test-repo").toFile()
        val git = Git.init().setDirectory(repo).call()
        val file = File(repo, "repository.json")
        file.writeText("""{"users":{"123":"Old Name"}}""")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("init").call()
        
        // Simulating the user changing their name without any other changes
        file.writeText("""{"users":{"123":"New Name"}}""")
        
        // This is what jgitPush() does:
        git.add().addFilepattern("repository.json").call()
        val stagingStatus = git.status().call()
        
        println("changed: " + stagingStatus.changed)
        println("added: " + stagingStatus.added)
        
        if (stagingStatus.added.isEmpty() && stagingStatus.changed.isEmpty()) {
            println("Push skipped!")
        } else {
            println("Push NOT skipped!")
            git.commit().setMessage("jadx-collaboration push").call()
        }
    }
}
