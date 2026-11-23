package com.jrod.droidmapper.model

import com.jrod.droidmapper.data.MapStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MapStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `maps are kept apart and listed`() {
        val store = MapStore(tmp.root)
        val zork = store.create("Zork I", "West of House")
        val horror = store.create("The Lurking Horror", "Terminal Room")
        store.save(zork, store.load(zork)!!.walkToNew(Dir.N, "North of House"))
        assertEquals(setOf("Zork I", "The Lurking Horror"), store.list().map { it.name }.toSet())
        assertEquals(2, store.list().first { it.id == zork }.rooms)
        assertEquals(1, store.load(horror)!!.rooms.size)
    }

    @Test
    fun `rename and delete`() {
        val store = MapStore(tmp.root)
        val id = store.create("Zork", "West of House")
        store.rename(id, "Zork I")
        assertEquals("Zork I", store.load(id)!!.name)
        store.delete(id)
        assertEquals(emptyList<String>(), store.list().map { it.name })
    }

    @Test
    fun `the last opened map is remembered until it is deleted`() {
        val store = MapStore(tmp.root)
        val id = store.create("Zork I", "West of House")
        store.setLastOpened(id)
        assertEquals(id, MapStore(tmp.root).lastOpened())
        store.delete(id)
        assertNull(MapStore(tmp.root).lastOpened())
    }

    @Test
    fun `the single map from before 0_3 becomes the first map in the list`() {
        val old = MapStore(tmp.root)
        val id = old.create("My map", "Kitchen")
        File(tmp.root, "maps/$id.json").copyTo(File(tmp.root, "map.json"))
        old.delete(id)
        val store = MapStore(tmp.root)
        assertEquals(listOf("My map"), store.list().map { it.name })
        assertEquals(store.list().single().id, store.lastOpened())
        assertEquals(false, File(tmp.root, "map.json").exists())
    }
}
