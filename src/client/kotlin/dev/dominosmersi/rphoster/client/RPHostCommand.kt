package dev.dominosmersi.rphoster.client

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.string
import kotlinx.coroutines.runBlocking
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.server.command.CommandManager
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.Text
import net.minecraft.text.TextColor
import net.ormr.katbox.Catbox
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class RPHostCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("rphost")
                    .then(
                        argument("resourcepack", string())
                            .suggests { context, builder ->
                                getResourcepacks().forEach {
                                    builder.suggest(it)
                                }
                                builder.buildFuture()
                            }
                            .executes { context ->
                                val rp = getString(context, "resourcepack")

                                Thread {
                                    val result = uploadRp(rp)

                                    val client = MinecraftClient.getInstance()

                                    client.execute {
                                        client.player?.sendMessage(result, false)
                                    }
                                }.start()

                                1
                            }
                    )
            )
        }
    }
    fun getResourcepacks(): List<String> {
        val gameDir = MinecraftClient.getInstance().runDirectory
        val rpDir = gameDir.resolve("resourcepacks")

        if (!rpDir.exists()) return emptyList()
        return rpDir.listFiles()?.filter {it.isDirectory || it.extension == "zip"}
            ?.map { it.name }
            ?: emptyList()
    }

    fun uploadRp(filename: String): Text = runBlocking {
        try {
            val gameDir = MinecraftClient.getInstance().runDirectory
            val rpDir = File(gameDir, "resourcepacks")
            val file = File(rpDir, filename)

            if (!file.exists()) {
                val error = Text.translatable("rphoster.error.folder_missing", filename).styled {
                    it
                        .withColor(TextColor.fromRgb(0xFF6E6E))
                }
                val message = Text.literal("RPHoster ").styled {
                    it.withColor(TextColor.fromRgb(0xFFB657))
                }.append(
                    Text.literal("» ").styled {
                        it.withColor(TextColor.fromRgb(0x7A8085))
                    }.append(
                        Text.translatable("rphoster.error", error).styled {
                            it.withColor(TextColor.fromRgb(0xABC4D6))
                        }
                    )
                )
                return@runBlocking message
            }
            if (hasFileInFolder(file,"pack.mcmeta")) {
                val bytes = when {
                    file.isDirectory -> zipDirectory(file)
                    file.isFile && file.extension.lowercase() == "zip" -> file.readBytes()

                    else -> {
                        return@runBlocking Text.translatable("rphoster.error.invalid_file").styled {
                            it.withColor(TextColor.fromRgb(0xFF6E6E))
                        }
                    }
                }

                val safeName = "${UUID.randomUUID()}.zip"
                val url = uploadToCatbox(bytes)

                val link = Text.literal(url).styled {
                    it
                        .withColor(TextColor.fromRgb(0xA6FF6E))
                        .withClickEvent(
                            ClickEvent.OpenUrl(URI.create(url))
                        )
                        .withHoverEvent(
                            HoverEvent.ShowText(Text.translatable("rphoster.click"))
                        )
                }
                val message = Text.literal("RPHoster ").styled {
                    it.withColor(TextColor.fromRgb(0xFFB657))
                }.append(
                    Text.literal("» ").styled {
                        it.withColor(TextColor.fromRgb(0x7A8085))
                    }.append(
                        Text.translatable("rphoster.success", link).styled {
                            it.withColor(TextColor.fromRgb(0xABC4D6))
                        }
                    )
                )
                return@runBlocking message
            } else {

                val error = Text.translatable("rphoster.error.no_pack_mcmeta").styled {
                    it
                        .withColor(TextColor.fromRgb(0xFF6E6E))
                }

                val message = Text.literal("RPHoster ").styled {
                    it.withColor(TextColor.fromRgb(0xFFB657))
                }.append(
                    Text.literal("» ").styled {
                        it.withColor(TextColor.fromRgb(0x7A8085))
                    }.append(
                        Text.translatable("rphoster.error", error).styled {
                            it.withColor(TextColor.fromRgb(0xABC4D6))
                        }
                    )
                )
                return@runBlocking message
            }

        } catch (e: Exception) {
            e.printStackTrace()
            val error = Text.literal("ERROR: ${e.message}")

            val message = Text.literal("RPHoster ").styled {
                it.withColor(TextColor.fromRgb(0xFFB657))
            }.append(
                Text.literal("» ").styled {
                    it.withColor(TextColor.fromRgb(0x7A8085))
                }.append(
                    Text.translatable("rphoster.error", error).styled {
                        it.withColor(TextColor.fromRgb(0xABC4D6))
                    }
                )
            )
            return@runBlocking message
        }
    }
    fun hasFileInFolder(file: File, path: String): Boolean {
        return if (file.isDirectory) {
            File(file, path).exists()
        } else if (file.extension.lowercase() == "zip") {
            ZipFile(file).use { zip ->
                zip.getEntry(path) != null
            }
        } else {
            false
        }
    }
    fun zipDirectory(sourceDir: File): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val entryName = file.relativeTo(sourceDir).path.replace("\\", "/")
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return baos.toByteArray()
    }
    fun uploadToCatbox(bytes: ByteArray): String {
        val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"
        val url = URL("https://catbox.moe/user/api.php")

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true

        connection.setRequestProperty(
            "Content-Type",
            "multipart/form-data; boundary=$boundary"
        )

        connection.outputStream.use { output ->
            val writer = output.bufferedWriter()

            // reqtype
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n")
            writer.write("fileupload\r\n")

            // file
            writer.write("--$boundary\r\n")
            writer.write(
                "Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"file.zip\"\r\n"
            )
            writer.write("Content-Type: application/zip\r\n\r\n")
            writer.flush()

            output.write(bytes)

            writer.write("\r\n--$boundary--\r\n")
            writer.flush()
        }

        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        return response.trim()
    }
}