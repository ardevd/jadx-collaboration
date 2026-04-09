package uk.oshawk.jadx.collaboration

import org.junit.jupiter.api.Test
import java.util.UUID

class GsonTest {
    @Test
    fun testGson() {
        val GSON = Plugin.GSON
        val repo = LocalRepository()
        repo.users[repo.uuid] = "Alice"
        val json = GSON.toJson(repo)
        println("JSON: $json")
        
        val repo2 = GSON.fromJson(json, LocalRepository::class.java)
        println("Deserialized uuid class: " + repo2.uuid.javaClass)
        println("Deserialized users keys classes: " + repo2.users.keys.map { it?.javaClass?.name })
        
        val u = repo2.users.keys.first()
        println("Equals? " + (u == repo2.uuid))
        assert(u == repo2.uuid)
    }
}
