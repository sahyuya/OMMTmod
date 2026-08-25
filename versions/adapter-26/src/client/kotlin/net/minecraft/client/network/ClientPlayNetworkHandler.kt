package net.minecraft.client.network

import net.minecraft.client.multiplayer.ClientPacketListener

class ClientPlayNetworkHandler internal constructor(
    internal val delegate: ClientPacketListener,
)
