package live.qsmc.qsync.fabric.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import live.qsmc.qsync.fabric.config.PortalConfig;
import live.qsmc.quipt.fabric.QuiptMod;
import live.qsmc.quipt.fabric.commands.FabricCommandExecutor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.command.ServerCommandSource;

import java.util.concurrent.CompletableFuture;

/**
 * {@code /portal} command for managing cross-server portal zones at runtime.
 *
 * <pre>
 *   /portal create  &lt;name&gt; &lt;target&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;
 *   /portal edit    &lt;name&gt; &lt;target&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;
 *   /portal delete  &lt;name&gt;
 *   /portal list
 *   /portal cooldown &lt;ms&gt;
 * </pre>
 * <p>
 * World is always inferred from the command executor's current world.
 * Requires operator level 4.
 */
public class PortalCommand extends FabricCommandExecutor {

    public PortalCommand(QuiptMod mod) {
        super(mod, "portal");
    }

    @Override
    public LiteralArgumentBuilder<ServerCommandSource> arguments() {
        return builder()
            .requires(src -> hasPermission(src, permission(4)))
            .executes(ctx -> showUsage(ctx, permission(4)))
            .then(literal("create")
                .then(argument("name", StringArgumentType.word())
                    .then(argument("target", StringArgumentType.word())
                        .then(argument("x1", IntegerArgumentType.integer())
                            .then(argument("y1", IntegerArgumentType.integer())
                                .then(argument("z1", IntegerArgumentType.integer())
                                    .then(argument("x2", IntegerArgumentType.integer())
                                        .then(argument("y2", IntegerArgumentType.integer())
                                            .then(argument("z2", IntegerArgumentType.integer())
                                                .executes(this::execCreate))))))))))
            .then(literal("edit")
                .then(argument("name", StringArgumentType.word())
                    .suggests(this::suggestZoneNames)
                    .then(argument("target", StringArgumentType.word())
                        .then(argument("x1", IntegerArgumentType.integer())
                            .then(argument("y1", IntegerArgumentType.integer())
                                .then(argument("z1", IntegerArgumentType.integer())
                                    .then(argument("x2", IntegerArgumentType.integer())
                                        .then(argument("y2", IntegerArgumentType.integer())
                                            .then(argument("z2", IntegerArgumentType.integer())
                                                .executes(this::execEdit))))))))))
            .then(literal("delete")
                .then(argument("name", StringArgumentType.word())
                    .suggests(this::suggestZoneNames)
                    .executes(this::execDelete)))
            .then(literal("list")
                .executes(this::execList))
            .then(literal("cooldown")
                .then(argument("ms", LongArgumentType.longArg(0))
                    .executes(this::execCooldown)));
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private int execCreate(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "name");
        String target = StringArgumentType.getString(ctx, "target");

        PortalConfig config = portalConfig();
        if (config.zones.contains(name)) {
            return logError(ctx, "A portal zone named '" + name + "' already exists. Use /portal edit to update it.");
        }

        PortalConfig.Zone zone = buildZone(ctx, name, target);
        config.zones.put(zone);
        config.save();

        return logSuccess(ctx, "Created portal '" + name + "' → " + target
            + " in " + zone.world + " [(" + zone.min_x + "," + zone.min_y + "," + zone.min_z
            + ") – (" + zone.max_x + "," + zone.max_y + "," + zone.max_z + ")]");
    }

    private int execEdit(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "name");
        String target = StringArgumentType.getString(ctx, "target");

        PortalConfig config = portalConfig();
        if (!config.zones.contains(name)) {
            return logError(ctx, "No portal zone named '" + name + "'. Use /portal create to add it.");
        }

        PortalConfig.Zone zone = buildZone(ctx, name, target);
        config.zones.remove(name);
        config.zones.put(zone);
        mod().integration().configs().save(config);

        return logSuccess(ctx, "Updated portal '" + name + "' → " + target
            + " in " + zone.world + " [(" + zone.min_x + "," + zone.min_y + "," + zone.min_z
            + ") – (" + zone.max_x + "," + zone.max_y + "," + zone.max_z + ")]");
    }

    private int execDelete(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");

        PortalConfig config = portalConfig();
        if (!config.zones.contains(name)) {
            return logError(ctx, "No portal zone named '" + name + "'.");
        }

        config.zones.remove(name);
        config.save();

        return logSuccess(ctx, "Deleted portal '" + name + "'.");
    }

    private int execList(CommandContext<ServerCommandSource> ctx) {
        PortalConfig config = portalConfig();

        if (config.zones.isEmpty()) {
            return log(ctx, "No portal zones configured. Use /portal create to add one.", NamedTextColor.YELLOW, 1);
        }

        sendMessage(ctx.getSource(), Component.text("--- Portal Zones (cooldown: "
            + config.arrival_cooldown_ms + "ms) ---").color(NamedTextColor.GOLD));

        for (PortalConfig.Zone zone : config.zones.values()) {
            sendMessage(ctx.getSource(),
                Component.text("  ").append(Component.text(zone.id).color(NamedTextColor.AQUA))
                    .append(Component.text(": " + zone.world + " → ", NamedTextColor.WHITE))
                    .append(Component.text(zone.target_server, NamedTextColor.GREEN))
                    .append(Component.text(
                        " [(" + zone.min_x + "," + zone.min_y + "," + zone.min_z
                            + ")–(" + zone.max_x + "," + zone.max_y + "," + zone.max_z + ")]",
                        NamedTextColor.GRAY)));
        }
        return 1;
    }

    private int execCooldown(CommandContext<ServerCommandSource> ctx) {
        long ms = LongArgumentType.getLong(ctx, "ms");

        PortalConfig config = portalConfig();
        config.arrival_cooldown_ms = ms;
        config.save();

        return logSuccess(ctx, "Portal arrival cooldown set to " + ms + "ms (" + (ms / 1000.0) + "s).");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a Zone from the standard 8-argument create/edit command context.
     */
    private PortalConfig.Zone buildZone(CommandContext<ServerCommandSource> ctx, String name, String target)
        throws CommandSyntaxException {
        int x1 = IntegerArgumentType.getInteger(ctx, "x1");
        int y1 = IntegerArgumentType.getInteger(ctx, "y1");
        int z1 = IntegerArgumentType.getInteger(ctx, "z1");
        int x2 = IntegerArgumentType.getInteger(ctx, "x2");
        int y2 = IntegerArgumentType.getInteger(ctx, "y2");
        int z2 = IntegerArgumentType.getInteger(ctx, "z2");
        String world = ctx.getSource().getWorld().getRegistryKey().getValue().toString();

        PortalConfig.Zone zone = new PortalConfig.Zone(world, x1, y1, z1, x2, y2, z2, target);
        zone.id = name;
        return zone;
    }

    /**
     * Suggests existing zone names for tab-completion.
     */
    private CompletableFuture<Suggestions> suggestZoneNames(
        CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        String[] names = portalConfig().zones.values().stream()
            .map(z -> z.id)
            .toArray(String[]::new);
        return onlySimilar(names, "name", ctx, builder);
    }

    private PortalConfig portalConfig() {
        return mod().integration().configs().config(PortalConfig.class);
    }
}
