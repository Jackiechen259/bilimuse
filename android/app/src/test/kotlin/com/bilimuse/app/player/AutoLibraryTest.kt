package com.bilimuse.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoLibraryTest {
    @Test
    fun folderMediaIdRoundTrips() {
        val mediaId = AutoLibrary.folderMediaId(12345L)

        assertEquals("bilimuse:folder:12345", mediaId)
        assertEquals(12345L, AutoLibrary.folderId(mediaId))
    }

    @Test
    fun nonFolderMediaIdsAreNotParsedAsFolders() {
        assertNull(AutoLibrary.folderId(AutoLibrary.ROOT_ID))
        assertNull(AutoLibrary.folderId("BV1xx411c7mD"))
        assertNull(AutoLibrary.folderId("bilimuse:folder:not-a-number"))
        assertNull(AutoLibrary.folderId("bilimuse:folder:"))
    }
}
