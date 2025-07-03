package pvpanalyzermod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class PVPAnalyzerMod implements ClientModInitializer {

	public static final String MOD_ID = "pvpanalyzermod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static boolean analysisActive = false;
	private static long startTime = 0;
	private static long lastCombatActivityTime = 0;

	private static int hitsGiven = 0;
	private static int hitsMissed = 0;
	private static float totalDamageDealt = 0.0f;
	private static float totalDamageReceived = 0.0f;
	private static int kills = 0;
	private static int deaths = 0;

	private static final Map<Long, Integer> cpsClicks = new HashMap<>();
	private static final Map<LivingEntity, Float> damagedEntitiesHealth = new HashMap<>();

	private static long lastLeftClickTime = 0;
	private static long lastRightClickTime = 0;

	@Override
	public void onInitializeClient() {
		LOGGER.info("PVP Analyzer Mod initialized!");

		// Comando toggle /pvpstats toggle
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				literal("pvpstats")
						.then(literal("toggle")
								.executes(context -> {
									PlayerEntity player = MinecraftClient.getInstance().player;
									if (analysisActive) {
										endPVPAnalysis(player, "Análisis finalizado manualmente por comando.");
									} else {
										startPVPAnalysis(player);
										if (player != null)
											player.sendMessage(Text.literal("§a[PVP Analyzer]§r Análisis iniciado. Usa /pvpstats stats para ver las estadísticas."), false);
									}
									return 1;
								}))
						.then(literal("stats")
								.executes(context -> {
									PlayerEntity player = MinecraftClient.getInstance().player;
									showPVPStats(player, "Estadísticas solicitadas manualmente.");
									return 1;
								}))
		));

		// Detectar golpes acertados
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (analysisActive && world.isClient() && player.equals(MinecraftClient.getInstance().player)) {
				if (entity instanceof LivingEntity target && target.isAlive()) {
					hitsGiven++;
					lastCombatActivityTime = world.getTime();
					damagedEntitiesHealth.put(target, target.getHealth());
				}
			}
			return ActionResult.PASS;
		});

		// Lógica principal por tick
		boolean[] wasDeadLastTick = {false};
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!analysisActive || client.player == null || client.world == null) return;

			long currentTime = client.world.getTime();

			// Detectar muerte
			if (client.player.isDead()) {
				if (!wasDeadLastTick[0]) {
					deaths++;
					endPVPAnalysis(client.player, "Has muerto.");
				}
				wasDeadLastTick[0] = true;
			} else {
				wasDeadLastTick[0] = false;
			}

			// Registrar clicks fallados
			if (client.options.attackKey.isPressed()) {
				if (currentTime - lastLeftClickTime >= 1) {
					long second = currentTime / 20;
					cpsClicks.put(second, cpsClicks.getOrDefault(second, 0) + 1);

					HitResult target = client.crosshairTarget;
					if (!(target instanceof EntityHitResult entityHit) || !(entityHit.getEntity() instanceof LivingEntity)) {
						hitsMissed++;
					}
				}
				lastLeftClickTime = currentTime;
			}

			// Estimar CPS derecho (opcional)
			if (client.options.useKey.isPressed()) {
				if (currentTime - lastRightClickTime >= 1) {
					long second = currentTime / 20;
					cpsClicks.put(second, cpsClicks.getOrDefault(second, 0) + 1);
				}
				lastRightClickTime = currentTime;
			}

			// Daño infligido: comprobar si las entidades bajaron vida
			damagedEntitiesHealth.entrySet().removeIf(entry -> {
				LivingEntity entity = entry.getKey();
				float oldHealth = entry.getValue();
				if (!entity.isAlive()) return true;
				float currentHealth = entity.getHealth();
				if (currentHealth < oldHealth) {
					totalDamageDealt += (oldHealth - currentHealth);
					return true;
				}
				return false;
			});

			// Daño recibido
			float missingHealth = client.player.getMaxHealth() - client.player.getHealth();
			if (missingHealth > totalDamageReceived) {
				totalDamageReceived = missingHealth;
			}

			// Inactividad por 30 segundos
			if (currentTime - lastCombatActivityTime > 20 * 30) {
				endPVPAnalysis(client.player, "Inactividad en el combate.");
			}
		});
	}

	private static void startPVPAnalysis(PlayerEntity player) {
		if (player == null) return;
		analysisActive = true;
		startTime = player.getWorld().getTime();
		lastCombatActivityTime = startTime;
		hitsGiven = 0;
		hitsMissed = 0;
		totalDamageDealt = 0.0f;
		totalDamageReceived = 0.0f;
		kills = 0;
		deaths = 0;
		cpsClicks.clear();
		damagedEntitiesHealth.clear();
		lastLeftClickTime = 0;
		lastRightClickTime = 0;
		LOGGER.info("PVP analysis started for {}", player.getName().getString());
	}

	private static void endPVPAnalysis(PlayerEntity player, String reason) {
		if (player == null) return;
		analysisActive = false;
		showPVPStats(player, reason);
		resetPVPStats();
		LOGGER.info("PVP analysis ended for {}. Reason: {}", player.getName().getString(), reason);
	}

	private static void showPVPStats(PlayerEntity player, String reason) {
		if (player == null) return;

		long totalTicks = player.getWorld().getTime() - startTime;
		Duration duration = Duration.ofMillis(totalTicks * 50);

		double accuracy = (hitsGiven + hitsMissed > 0)
				? ((double) hitsGiven / (hitsGiven + hitsMissed)) * 100.0 : 0.0;

		double totalCps = cpsClicks.values().stream().mapToDouble(i -> i).sum();
		double avgCps = cpsClicks.size() > 0 ? totalCps / cpsClicks.size() : 0.0;

		player.sendMessage(Text.literal(""), false);
		player.sendMessage(Text.literal("--- §b[Resumen de Análisis PVP]§r ---"), false);
		player.sendMessage(Text.literal("Estado: " + (analysisActive ? "§aACTIVO" : "§cINACTIVO")), false);
		player.sendMessage(Text.literal("Motivo: §7" + reason), false);
		player.sendMessage(Text.literal("Tiempo en combate: §e" + formatDuration(duration)), false);
		player.sendMessage(Text.literal("---------------------------"), false);
		player.sendMessage(Text.literal("Golpes Acertados: §a" + hitsGiven), false);
		player.sendMessage(Text.literal("Golpes Fallados: §6" + hitsMissed), false);
		player.sendMessage(Text.literal(String.format("Precisión: §d%.2f%%", accuracy)), false);
		player.sendMessage(Text.literal(String.format("Daño Infligido: §c%.2f♥", totalDamageDealt)), false);
		player.sendMessage(Text.literal(String.format("Daño Recibido: §c%.2f♥", totalDamageReceived)), false);
		player.sendMessage(Text.literal("Asesinatos: §b" + kills), false);
		player.sendMessage(Text.literal("Muertes: §4" + deaths), false);
		player.sendMessage(Text.literal(String.format("CPS Promedio: §9%.2f", avgCps)), false);
		player.sendMessage(Text.literal("---------------------------"), false);

		String consejo = getPVPAdvice(hitsGiven, (int) totalDamageReceived, hitsMissed, accuracy);
		player.sendMessage(Text.literal("Consejo: §f" + consejo), false);
		player.sendMessage(Text.literal(""), false);
	}

	private static void resetPVPStats() {
		analysisActive = false;
		hitsGiven = 0;
		hitsMissed = 0;
		totalDamageDealt = 0.0f;
		totalDamageReceived = 0.0f;
		kills = 0;
		deaths = 0;
		cpsClicks.clear();
		damagedEntitiesHealth.clear();
		lastLeftClickTime = 0;
		lastRightClickTime = 0;
	}

	private static String formatDuration(Duration duration) {
		long seconds = duration.getSeconds();
		long mins = seconds / 60;
		long sec = seconds % 60;
		return mins + " min " + sec + " seg";
	}

	private static String getPVPAdvice(int hits, int damageTaken, int misses, double acc) {
		if (misses > hits * 0.4 && hits > 5)
			return "Estás fallando muchos golpes. Concéntrate en tu puntería.";
		if (acc < 50.0 && hits > 5)
			return "Tu puntería necesita mejorar. Intenta conectar más ataques.";
		if (damageTaken > hits * 1.5 && hits > 5)
			return "Estás recibiendo mucho daño. Mejora tu defensa y movimiento.";
		if (hits > 10 && acc > 75.0)
			return "¡Excelente puntería! Sigue así.";
		return "Buen combate. Sigue practicando.";
	}
}
