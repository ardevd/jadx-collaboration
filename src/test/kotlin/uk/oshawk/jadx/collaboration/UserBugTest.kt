package uk.oshawk.jadx.collaboration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class UserBugTest {
    @Test
    fun testUserBug() {
        val options = Options()
        options.username = "Old Name"
        
        val local = LocalRepository()
        val remote = RemoteRepository()
        
        // Initial sync
        if (options.username.isNotEmpty()) {
            local.users[local.uuid] = options.username
        }
        remote.users.forEach { (uuid, user) -> local.users.putIfAbsent(uuid, user) }
        remote.users.clear()
        remote.users.putAll(local.users)
        
        assertEquals("Old Name", remote.users[local.uuid])
        
        // Now user changes username
        options.username = "New Name"
        
        // Push
        if (options.username.isNotEmpty()) {
            local.users[local.uuid] = options.username
        }
        remote.users.forEach { (uuid, user) -> local.users.putIfAbsent(uuid, user) }
        remote.users.clear()
        remote.users.putAll(local.users)
        
        assertEquals("New Name", remote.users[local.uuid])
    }
}
