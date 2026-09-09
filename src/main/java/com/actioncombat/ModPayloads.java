package com.actioncombat;

import com.actioncombat.combat.ServerCombat;
import com.actioncombat.client.ClientCombat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModPayloads {
    public record ClientboundSpearRecovery() implements CustomPacketPayload {
        public static final Type<ClientboundSpearRecovery> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "spear_recovery"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundSpearRecovery> STREAM_CODEC =
                StreamCodec.unit(new ClientboundSpearRecovery());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: client finished deciding to enter/leave combat mode (predicted locally).
     *  {@code katana} is 左手拔刀 — offhand sword, two-handed stance. */
    public record ServerboundSetDrawn(boolean drawn, boolean katana) implements CustomPacketPayload {
        public static final Type<ServerboundSetDrawn> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "set_drawn"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundSetDrawn> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ServerboundSetDrawn::drawn,
                        ByteBufCodecs.BOOL, ServerboundSetDrawn::katana,
                        ServerboundSetDrawn::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: katana lock-on target; -1 clears the lock. */
    public record ServerboundKatanaLock(int targetId) implements CustomPacketPayload {
        public static final Type<ServerboundKatanaLock> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "katana_lock"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundKatanaLock> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ServerboundKatanaLock::targetId,
                        ServerboundKatanaLock::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: attack request; side picks the slash direction, dual is the parry follow-up.
 *  {@code chargePiercingArmor} is set by rapier players whose last block-key hold
 *  primed a charge strike — the next rapier thrust consumes it to ignore 30 % armor. */
    public record ServerboundAttack(int side, boolean charged, boolean dual, boolean chargePiercingArmor)
            implements CustomPacketPayload {
        public static final Type<ServerboundAttack> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "attack"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundAttack> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ServerboundAttack::side,
                        ByteBufCodecs.BOOL, ServerboundAttack::charged,
                        ByteBufCodecs.BOOL, ServerboundAttack::dual,
                        ByteBufCodecs.BOOL, ServerboundAttack::chargePiercingArmor,
                        ServerboundAttack::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: rapier-style charge hold completed; next thrust ping consumes the marker. */
    public record ServerboundRapierChargeReady() implements CustomPacketPayload {
        public static final Type<ServerboundRapierChargeReady> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "rapier_charge_ready"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundRapierChargeReady> STREAM_CODEC =
                StreamCodec.unit(new ServerboundRapierChargeReady());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * C2S: drawn-combat dodge. Rapier / katana send the camera yaw and a WASD bitfield
     * for the scripted step; dual-wield only needs the request itself, because its dash
     * is a server-side max-speed boost and the client already owns its direction.
     */
    public record ServerboundDodge(float yaw, int dirs) implements CustomPacketPayload {
        public static final Type<ServerboundDodge> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "dodge"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundDodge> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.FLOAT, ServerboundDodge::yaw,
                        ByteBufCodecs.VAR_INT, ServerboundDodge::dirs,
                        ServerboundDodge::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: camera pose. Hits are traced from this ray; range is still from the player. */
    public record ServerboundAim(float yaw, float pitch, double camX, double camY, double camZ)
            implements CustomPacketPayload {
        public static final Type<ServerboundAim> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "aim"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundAim> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.FLOAT, ServerboundAim::yaw,
                        ByteBufCodecs.FLOAT, ServerboundAim::pitch,
                        ByteBufCodecs.DOUBLE, ServerboundAim::camX,
                        ByteBufCodecs.DOUBLE, ServerboundAim::camY,
                        ByteBufCodecs.DOUBLE, ServerboundAim::camZ,
                        ServerboundAim::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: guard stance held while the key is down. */
    public record ServerboundSetDefending(boolean defending) implements CustomPacketPayload {
        public static final Type<ServerboundSetDefending> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "set_defending"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundSetDefending> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, ServerboundSetDefending::defending, ServerboundSetDefending::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: authoritative drawn state for one player. */
    public record ClientboundDrawState(int playerId, boolean drawn, boolean katana) implements CustomPacketPayload {
        public static final Type<ClientboundDrawState> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "draw_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundDrawState> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ClientboundDrawState::playerId,
                        ByteBufCodecs.BOOL, ClientboundDrawState::drawn,
                        ByteBufCodecs.BOOL, ClientboundDrawState::katana,
                        ClientboundDrawState::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: authoritative katana cadence for prediction (hit streak / guard haste). */
    public record ClientboundKatanaSpeed(int intervalTicks, long hasteUntilGameTime) implements CustomPacketPayload {
        public static final Type<ClientboundKatanaSpeed> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "katana_speed"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundKatanaSpeed> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ClientboundKatanaSpeed::intervalTicks,
                        ByteBufCodecs.LONG, ClientboundKatanaSpeed::hasteUntilGameTime,
                        ClientboundKatanaSpeed::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: tells the attacking client a swing connected (FOV punch + feedback). */
    public record ClientboundHitConfirm(boolean hit) implements CustomPacketPayload {
        public static final Type<ClientboundHitConfirm> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "hit_confirm"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundHitConfirm> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, ClientboundHitConfirm::hit, ClientboundHitConfirm::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * S2C: the sender's rapier hit streak changed. {@code targetId} is the enemy the
     * streak is counted on (or -1 when it reset), {@code hits} is the streak length
     * and {@code silenceTicks} the AI-deprivation time that just started (0 = none).
     */
    public record ClientboundRapierCombo(int targetId, int hits, int silenceTicks)
            implements CustomPacketPayload {
        public static final Type<ClientboundRapierCombo> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "rapier_combo"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundRapierCombo> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ClientboundRapierCombo::targetId,
                        ByteBufCodecs.VAR_INT, ClientboundRapierCombo::hits,
                        ByteBufCodecs.VAR_INT, ClientboundRapierCombo::silenceTicks,
                        ClientboundRapierCombo::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: broadcast so every client plays the swing on that player's dummy. */
    public record ClientboundAttackAnim(int playerId, int side, int depth, boolean dual, int intervalTicks)
            implements CustomPacketPayload {
        public static final Type<ClientboundAttackAnim> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "attack_anim"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAttackAnim> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ClientboundAttackAnim::playerId,
                        ByteBufCodecs.VAR_INT, ClientboundAttackAnim::side,
                        ByteBufCodecs.VAR_INT, ClientboundAttackAnim::depth,
                        ByteBufCodecs.BOOL, ClientboundAttackAnim::dual,
                        ByteBufCodecs.VAR_INT, ClientboundAttackAnim::intervalTicks,
                        ClientboundAttackAnim::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: this player just parried; both buttons together can fire the dual cut. */
    public record ClientboundParry(int playerId) implements CustomPacketPayload {
        public static final Type<ClientboundParry> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "parry"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundParry> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ClientboundParry::playerId,
                        ClientboundParry::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: broadcast so every client plays the flinch on that player's dummy. */
    public record ClientboundHurt(int playerId) implements CustomPacketPayload {
        public static final Type<ClientboundHurt> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "hurt"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundHurt> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ClientboundHurt::playerId,
                        ClientboundHurt::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * S2C: this player started a scripted step dodge; play the dash clip on their
     * dummy. The dual-wield speed dash never sends this — it has no clip to play.
     */
    public record ClientboundDodge(int playerId) implements CustomPacketPayload {
        public static final Type<ClientboundDodge> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "dodge_anim"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundDodge> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ClientboundDodge::playerId,
                        ClientboundDodge::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: broadcast so other clients show the guard stance on that player's dummy. */
    public record ClientboundDefendState(int playerId, boolean defending) implements CustomPacketPayload {
        public static final Type<ClientboundDefendState> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "defend_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundDefendState> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ClientboundDefendState::playerId,
                        ByteBufCodecs.BOOL, ClientboundDefendState::defending,
                        ClientboundDefendState::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClientboundThrust(int playerId, boolean accepted, boolean hit, int cooldownTicks, int recoveryTicks) implements CustomPacketPayload {
        public static final Type<ClientboundThrust> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "thrust"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundThrust> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ClientboundThrust::playerId, ByteBufCodecs.BOOL, ClientboundThrust::accepted,
                ByteBufCodecs.BOOL, ClientboundThrust::hit, ByteBufCodecs.VAR_INT, ClientboundThrust::cooldownTicks,
                ByteBufCodecs.VAR_INT, ClientboundThrust::recoveryTicks, ClientboundThrust::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record ClientboundGuardBash(int playerId) implements CustomPacketPayload {
        public static final Type<ClientboundGuardBash> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ActionCombat.MODID, "guard_bash"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundGuardBash> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ClientboundGuardBash::playerId, ClientboundGuardBash::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(ModPayloads::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("10");

        registrar.playToClient(ClientboundSpearRecovery.TYPE, ClientboundSpearRecovery.STREAM_CODEC,
                (p, c) -> c.enqueueWork(ClientCombat::onSpearRecovery));
        registrar.playToClient(ClientboundThrust.TYPE, ClientboundThrust.STREAM_CODEC,
                (p, c) -> c.enqueueWork(() -> ClientCombat.onThrust(p.playerId(), p.accepted(), p.hit(), p.cooldownTicks(), p.recoveryTicks())));
        registrar.playToClient(ClientboundGuardBash.TYPE, ClientboundGuardBash.STREAM_CODEC,
                (p, c) -> c.enqueueWork(() -> ClientCombat.onGuardBash(p.playerId())));
        registrar.playToServer(
                ServerboundSetDrawn.TYPE, ServerboundSetDrawn.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ServerCombat.handleSetDrawn((ServerPlayer) context.player(),
                                payload.drawn(), payload.katana())));

        registrar.playToServer(
                ServerboundKatanaLock.TYPE, ServerboundKatanaLock.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ServerCombat.handleKatanaLock((ServerPlayer) context.player(), payload.targetId())));

        registrar.playToServer(
                ServerboundAttack.TYPE, ServerboundAttack.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ServerCombat.handleAttack((ServerPlayer) context.player(),
                                com.actioncombat.combat.SwingSide.byId(payload.side()),
                                payload.charged(), payload.dual(), payload.chargePiercingArmor())));

        registrar.playToServer(
                ServerboundRapierChargeReady.TYPE, ServerboundRapierChargeReady.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ServerCombat.handleRapierChargeReady((ServerPlayer) context.player())));

        registrar.playToServer(
                ServerboundDodge.TYPE, ServerboundDodge.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ServerCombat.handleDodge((ServerPlayer) context.player(),
                                payload.yaw(), payload.dirs())));

        registrar.playToServer(
                ServerboundAim.TYPE, ServerboundAim.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ServerCombat.handleAim((ServerPlayer) context.player(),
                                payload.yaw(), payload.pitch(),
                                payload.camX(), payload.camY(), payload.camZ())));

        registrar.playToServer(
                ServerboundSetDefending.TYPE, ServerboundSetDefending.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ServerCombat.handleSetDefending((ServerPlayer) context.player(), payload.defending())));

        registrar.playToClient(
                ClientboundDrawState.TYPE, ClientboundDrawState.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientCombat.onDrawState(payload.playerId(), payload.drawn(), payload.katana())));

        registrar.playToClient(
                ClientboundKatanaSpeed.TYPE, ClientboundKatanaSpeed.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientCombat.onKatanaSpeed(payload.intervalTicks(), payload.hasteUntilGameTime())));

        registrar.playToClient(
                ClientboundHitConfirm.TYPE, ClientboundHitConfirm.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientCombat.onHitConfirm(payload.hit())));

        registrar.playToClient(
                ClientboundRapierCombo.TYPE, ClientboundRapierCombo.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientCombat.onRapierCombo(payload.targetId(), payload.hits(),
                                payload.silenceTicks())));

        registrar.playToClient(
                ClientboundAttackAnim.TYPE, ClientboundAttackAnim.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientCombat.onAttackAnim(payload.playerId(),
                                com.actioncombat.combat.SwingSide.byId(payload.side()),
                                payload.depth(), payload.dual(), payload.intervalTicks())));

        registrar.playToClient(
                ClientboundParry.TYPE, ClientboundParry.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientCombat.onParry(payload.playerId())));

        registrar.playToClient(
                ClientboundHurt.TYPE, ClientboundHurt.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientCombat.onHurt(payload.playerId())));

        registrar.playToClient(
                ClientboundDodge.TYPE, ClientboundDodge.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientCombat.onDodgeAnim(payload.playerId())));

        registrar.playToClient(
                ClientboundDefendState.TYPE, ClientboundDefendState.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ClientCombat.onDefendState(payload.playerId(), payload.defending())));
    }

    private ModPayloads() {}
}
