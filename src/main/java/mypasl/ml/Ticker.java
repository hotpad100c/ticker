package mypasl.ml;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldEvents;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.tick.TickPriority;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static mypasl.ml.WorldEventMapper.WORLD_EVENT_MAP;
import static mypasl.ml.WorldEventMapper.saveMapToFile;

public class Ticker implements ModInitializer {
	public static final String MOD_ID = "ticker";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/*private static final Map<String, Integer> WORLD_EVENT_MAP_BUILD = new HashMap<>(){};
	private static final List<String> WORLD_EVENT_NAMES;
	static {
		WORLD_EVENT_NAMES = Arrays.stream(WorldEvents.class.getFields())
				.filter(field -> field.getType() == int.class)
				.map(field -> {
					try {
						int value = field.getInt(null);
						WORLD_EVENT_MAP_BUILD.put(field.getName(), value);
						return field.getName();
					} catch (IllegalAccessException e) {
						throw new RuntimeException("Cant access: " + field.getName(), e);
					}
				})
				.collect(Collectors.toList());
	}*/

	// 解析事件名称 -> 事件数值
	public static Integer getEventId(String eventName) {
		return WORLD_EVENT_MAP.get(eventName);
	}

	private static final SuggestionProvider<ServerCommandSource> WORLD_EVENT_SUGGESTIONS =
			(context, builder) -> CommandSource.suggestMatching(WORLD_EVENT_MAP.keySet(), builder);
	@Override
	public void onInitialize() {
		// 注册指令
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommand(dispatcher, registryAccess));

		//saveMapToFile(WORLD_EVENT_MAP_BUILD);
	}

	private void registerCommand(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		dispatcher.register(CommandManager.literal("scheduleTick")
				.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
						.then(CommandManager.argument("block", BlockStateArgumentType.blockState(registryAccess))
								.then(CommandManager.argument("time", IntegerArgumentType.integer(0))
										.then(CommandManager.argument("priority", IntegerArgumentType.integer(-3, 3))
												.executes(context -> {
													ServerCommandSource source = context.getSource();

													BlockPos pos = BlockPosArgumentType.getBlockPos(context, "pos");
													Block block = BlockStateArgumentType.getBlockState(context, "block").getBlockState().getBlock();
													int time = IntegerArgumentType.getInteger(context, "time");
													int priority = IntegerArgumentType.getInteger(context, "priority");
													source.getWorld().scheduleBlockTick(pos, block,time, TickPriority.byIndex(priority));

													source.sendFeedback(() -> Text.literal("ScheduleTick for [" + Text.translatable(block.getTranslationKey()).getString() + "] was added at [" + pos.getX() + "," +
															pos.getY() + "," + pos.getZ() + "] with delay of [" + time + "]and priority[" + priority +"]."),true);

													return Command.SINGLE_SUCCESS;
												}))))));
		dispatcher.register(CommandManager.literal("blockEvent")
				.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
						.then(CommandManager.argument("block", BlockStateArgumentType.blockState(registryAccess))
								.then(CommandManager.argument("type", IntegerArgumentType.integer(0,2))
										.then(CommandManager.argument("data", IntegerArgumentType.integer(0, 5))
												.executes(context -> {
													ServerCommandSource source = context.getSource();

													BlockPos pos = BlockPosArgumentType.getBlockPos(context, "pos");
													Block block = BlockStateArgumentType.getBlockState(context, "block").getBlockState().getBlock();
													int type = IntegerArgumentType.getInteger(context, "type");
													int data = IntegerArgumentType.getInteger(context, "data");
													addBlockEvent(source,pos, block, type, data);

													return 1;
												}))))));
		dispatcher.register(
				CommandManager.literal("gameEvent")
						.then(CommandManager.argument("pos", Vec3ArgumentType.vec3())
								.then(CommandManager.argument("reason", StringArgumentType.word())
										.suggests((context, builder) -> CommandSource.suggestMatching(
                                                Registries.GAME_EVENT.streamEntries()
                                                        .map(entry -> entry.registryKey().getValue().toString().replace("minecraft:",""))
                                                        .collect(Collectors.toList()), builder
                                        ))
										.then(CommandManager.argument("entity", EntityArgumentType.entity())
												.then(CommandManager.argument("blockstate", BlockStateArgumentType.blockState(registryAccess))
														.executes(context -> {
															Vec3d pos = Vec3ArgumentType.getVec3(context, "pos");
															String reason = StringArgumentType.getString(context, "reason");
															Entity entity = EntityArgumentType.getEntity(context, "entity");
															BlockState blockState = BlockStateArgumentType.getBlockState(context, "blockstate").getBlockState();
															return addGameEvent(context.getSource(), pos, reason, entity, blockState);
														})
												)
										)
										.then(CommandManager.argument("entity", EntityArgumentType.entity())
												.executes(context -> {
													Vec3d pos = Vec3ArgumentType.getVec3(context, "pos");
													String reason = StringArgumentType.getString(context, "reason");
													Entity entity = EntityArgumentType.getEntity(context, "entity");
													return addGameEvent(context.getSource(), pos, reason, entity, null);
												})
										).then(CommandManager.argument("blockstate", BlockStateArgumentType.blockState(registryAccess))
												.executes(context -> {
													Vec3d pos = Vec3ArgumentType.getVec3(context, "pos");
													String reason = StringArgumentType.getString(context, "reason");
													BlockState blockState = BlockStateArgumentType.getBlockState(context, "blockstate").getBlockState();
													return addGameEvent(context.getSource(), pos, reason, null, blockState);
												})
										)
										.executes(context -> {
											Vec3d pos = Vec3ArgumentType.getVec3(context, "pos");
											String reason = StringArgumentType.getString(context, "reason");
											return addGameEvent(context.getSource(), pos, reason, null, null);
										})

								)
						)
		);
		dispatcher.register(CommandManager.literal("worldEvent")
				.then(CommandManager.argument("player", EntityArgumentType.player())
						.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
								.then(CommandManager.argument("event", StringArgumentType.string()).suggests(WORLD_EVENT_SUGGESTIONS)
										.then(CommandManager.argument("data", IntegerArgumentType.integer())
												.executes(context -> {
													ServerCommandSource source = context.getSource();

													PlayerEntity player = EntityArgumentType.getPlayer(context, "player");
													BlockPos pos = BlockPosArgumentType.getBlockPos(context, "pos");
													String event = StringArgumentType.getString(context, "event");
													int data = IntegerArgumentType.getInteger(context, "data");
													addWorldEvent(source, pos, event,player, data);

													return 1;
												}))))
				).then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
						.then(CommandManager.argument("event", StringArgumentType.string()).suggests(WORLD_EVENT_SUGGESTIONS)
								.then(CommandManager.argument("data", IntegerArgumentType.integer())
										.executes(context -> {
											ServerCommandSource source = context.getSource();

											BlockPos pos = BlockPosArgumentType.getBlockPos(context, "pos");
											String event = StringArgumentType.getString(context, "event");
											int data = IntegerArgumentType.getInteger(context, "data");
											addWorldEvent(source, pos, event,null, data);

											return 1;
										}))))
		);
	}

	public void addBlockEvent(ServerCommandSource source, BlockPos pos, Block block, int type, int data ){
		source.getWorld().addSyncedBlockEvent(pos, block, type, data);
		source.sendFeedback(() -> Text.literal("BlockEvent for [" + Text.translatable(block.getTranslationKey()).getString() + "] was emitted at [" + pos.getX() + "," +
				pos.getY() + "," + pos.getZ() + "] with type [" + type + "] and data[" +data+"]."),true);

	}
	public int addGameEvent(ServerCommandSource source, Vec3d pos, String reason, @Nullable Entity entity, @Nullable BlockState blockState){
		RegistryEntry<GameEvent> event = Registries.GAME_EVENT.getEntry(Identifier.of("minecraft:"+reason)).orElse(null);
		if (event == null) {
			source.sendError(Text.literal("Unknown GameEvent: " + reason));
			return 0;
		}
		source.getWorld().emitGameEvent(event, pos, new GameEvent.Emitter(entity, blockState));
		source.sendFeedback(() -> Text.literal("GameEvent <" + reason + "> was emitted at [" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "]" +
				(entity == null?" ":(" by entity <" + entity.getName() + ">")) + (blockState == null?" ":(" with block [" +
				Text.translatable(blockState.getBlock().getTranslationKey()).getString()+ "]"))),true);

		return 1;
	}
	public int addWorldEvent(ServerCommandSource source, BlockPos pos, String id, @Nullable PlayerEntity player, int data){
		int eventId = 1000;
		try {
			eventId = getEventId(id);
		} catch (Exception e) {
			source.sendError(Text.literal("Unknown WorldEvent: " + id));
		}
		source.getWorld().syncWorldEvent(player, eventId, pos, data);
		source.sendFeedback(() -> Text.literal("WorldEvent <" + id + "> was emitted at [" + pos.getX() + "," + pos.getY() + "," + pos.getZ() +
				"]　with data of　[" + data + "]　." + (player == null?" ":("But will not notify the player:" + player.getName() + "..."))),true);
		return 1;
	}
}