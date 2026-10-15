package com.ersaz.hermesmobile
import com.ersaz.hermesmobile.media.WhisperModelStore
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
class WhisperModelStoreTest {
 @Test fun corruptOrOversizedDownloadCannotReplaceWorkingModel() {
  val dir=Files.createTempDirectory("whisper-model").toFile()
  try {
   val store=WhisperModelStore(dir)
   val good="verified weights".toByteArray()
   val sha=MessageDigest.getInstance("SHA-256").digest(good).joinToString(""){"%02x".format(it)}
   val model=store.install(ByteArrayInputStream(good),sha,100)
   assertArrayEquals(good,model.readBytes())
   try {store.install(ByteArrayInputStream("bad".toByteArray()),sha,100);fail("checksum mismatch")}catch (_:IllegalArgumentException){}
   assertArrayEquals(good,model.readBytes())
   try {store.install(ByteArrayInputStream(good),sha,1);fail("oversize")}catch (_:IllegalArgumentException){}
   assertArrayEquals(good,model.readBytes())
  } finally {dir.deleteRecursively()}
 }
}
