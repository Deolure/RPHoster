package dev.dominosmersi.rphoster.client

import net.fabricmc.api.ClientModInitializer

class RPhosterClient : ClientModInitializer {

    override fun onInitializeClient() {
        RPHostCommand().register()
    }
}
