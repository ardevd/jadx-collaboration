package uk.oshawk.jadx.collaboration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.util.UUID

class UsernameTest {
    @Test
    fun testUsernameUpdate() {
        val plugin = Plugin(showDialogs = false)
        val local = LocalRepository()
        val remote = RemoteRepository()
        
        // Simulating projectToLocalRepository
        val optionsUsername1 = "Old Name"
        local.users[local.uuid] = optionsUsername1
        
        // Simulating remoteRepositoryToLocalRepository
        remote.users.forEach { (uuid, user) ->
            local.users.putIfAbsent(uuid, user)
        }
        
        // Simulating localRepositoryToRemoteRepository
        remote.users.clear()
        remote.users.putAll(local.users)
        
        assertEquals("Old Name", remote.users[local.uuid])
        
        // Now user changes username
        val optionsUsername2 = "New Name"
        
        // Simulating push loop again
        // projectToLocalRepository
        local.users[local.uuid] = optionsUsername2
        
        // Simulating remoteRepositoryToLocalRepository
        remote.users.forEach { (uuid, user) ->
            local.users.putIfAbsent(uuid, user)
        }
        
        // Simulating localRepositoryToRemoteRepository
        remote.users.clear()
        remote.users.putAll(local.users)
        
        assertEquals("New Name", remote.users[local.uuid], "Remote repository should have the new username")
    }
}
