package com.autoplay;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Small, conservative combat-practice prototype for Minecraft 1.21.11.
 * It never enables itself automatically. Multiplayer use is gated by a local allowlist.
 */
public final class AutoPlayClient implements ClientModInitializer {
    private static final double BASE_RANGE = 3.1;
    private static final double MAX_RANGE = 3.6;
    private static final Map<UUID, OpponentMemory> MEMORY = new HashMap<>();
    private static boolean enabled = false;
    private static int ticksSinceAttack = 0;
    private static UUID observedTarget = null;
    private static float observedHealth = -1.0f;
    private static int ticksSinceObservation = 0;
    private static Path allowlistPath;

    @Override
    public void onInitializeClient() {
        allowlistPath = MinecraftClient.getInstance().runDirectory.toPath()
                .resolve("config").resolve("autoplay-allowed-servers.txt");
        createAllowlistIfMissing();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("autoplay")
                        .then(literal("on").executes(context -> {
                            enabled = true;
                            context.getSource().sendFeedback(Text.literal("AutoPlay AI enabled. Practice/allowlist checks remain active."));
                            return 1;
                        }))
                        .then(literal("off").executes(context -> {
                            enabled = false;
                            context.getSource().sendFeedback(Text.literal("AutoPlay AI disabled."));
                            return 1;
                        }))
                        .then(literal("status").executes(context -> {
                            context.getSource().sendFeedback(Text.literal("AutoPlay AI: " + (enabled ? "ON" : "OFF") + ". Memory entries: " + MEMORY.size()));
                            return 1;
                        }))
                        .then(literal("allowlist").executes(context -> {
                            context.getSource().sendFeedback(Text.literal("Add one private/authorized server address per line in: " + allowlistPath));
                            return 1;
                        }))));

        ClientTickEvents.END_CLIENT_TICK.register(AutoPlayClient::tick);
    }

    private static void tick(MinecraftClient client) {
        if (!enabled || client.player == null || client.world == null || client.interactionManager == null) {
            resetObservation();
            return;
        }
        if (!isAllowedContext(client)) {
            resetObservation();
            return;
        }

        ClientPlayerEntity player = client.player;
        ticksSinceAttack++;
        ticksSinceObservation++;
        updateLearning(client);

        PlayerEntity target = findTarget(client, player);
        if (target == null) {
            resetObservation();
            return;
        }

        OpponentMemory memory = MEMORY.computeIfAbsent(target.getUuid(), ignored -> new OpponentMemory());
        double learnedRange = Math.min(MAX_RANGE, BASE_RANGE + Math.min(0.35, memory.successRate * 0.35));
        if (player.distanceTo(target) > learnedRange) {
            return;
        }

        aimAt(player, target);
        if (ticksSinceAttack >= 2 && player.getAttackCooldownProgress(0.0f) >= 0.92f) {
            float beforeHealth = target.getHealth();
            client.interactionManager.attackEntity(player, target);
            player.swingHand(Hand.MAIN_HAND);
            observedTarget = target.getUuid();
            observedHealth = beforeHealth;
            ticksSinceObservation = 0;
            ticksSinceAttack = 0;
        }
    }

    private static PlayerEntity findTarget(MinecraftClient client, ClientPlayerEntity player) {
        PlayerEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PlayerEntity candidate : client.world.getPlayers()) {
            if (candidate == player || candidate.isSpectator() || candidate.isCreative() || !candidate.isAlive()) {
                continue;
            }
            double distance = player.distanceTo(candidate);
            if (distance <= MAX_RANGE && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static void aimAt(ClientPlayerEntity player, Entity target) {
        Vec3d from = player.getEyePos();
        Vec3d to = target.getBoundingBox().getCenter();
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        player.setYaw(player.getYaw() + MathHelper.wrapDegrees(yaw - player.getYaw()));
        player.setPitch(MathHelper.clamp(pitch, -90.0f, 90.0f));
    }

    private static void updateLearning(MinecraftClient client) {
        if (observedTarget == null || ticksSinceObservation < 2 || client.world == null) {
            return;
        }
        PlayerEntity previous = null;
        for (PlayerEntity p : client.world.getPlayers()) {
            if (p.getUuid().equals(observedTarget)) {
                previous = p;
                break;
            }
        }
        OpponentMemory memory = MEMORY.computeIfAbsent(observedTarget, ignored -> new OpponentMemory());
        memory.attempts++;
        if (previous != null && previous.getHealth() < observedHealth) {
            memory.successes++;
        }
        memory.successRate = memory.attempts == 0 ? 0.0 : (double) memory.successes / memory.attempts;
        observedTarget = null;
        observedHealth = -1.0f;
        ticksSinceObservation = 0;
    }

    private static boolean isAllowedContext(MinecraftClient client) {
        // Singleplayer is available for safe local testing. On multiplayer, require an explicit allowlist entry.
        if (client.isInSingleplayer()) {
            return true;
        }
        if (client.getCurrentServerEntry() == null || allowlistPath == null) {
            return false;
        }
        String address = client.getCurrentServerEntry().address.trim().toLowerCase(Locale.ROOT);
        if (address.isEmpty()) {
            return false;
        }
        try {
            return Files.readAllLines(allowlistPath, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .anyMatch(line -> !line.isEmpty() && !line.startsWith("#") && line.equals(address));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void createAllowlistIfMissing() {
        if (allowlistPath == null) return;
        try {
            Files.createDirectories(allowlistPath.getParent());
            if (Files.notExists(allowlistPath)) {
                Files.writeString(allowlistPath,
                        "# AutoPlay AI only runs on multiplayer servers you are authorized to automate.\n"
                                + "# Add the exact server address on its own line; default is intentionally empty.\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Fail closed: if the allowlist cannot be read, multiplayer automation remains disabled.
        }
    }

    private static void resetObservation() {
        observedTarget = null;
        observedHealth = -1.0f;
        ticksSinceObservation = 0;
    }

    private static final class OpponentMemory {
        private int attempts;
        private int successes;
        private double successRate;
    }
}
