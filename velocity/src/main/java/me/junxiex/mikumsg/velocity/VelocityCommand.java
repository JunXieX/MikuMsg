package me.junxiex.mikumsg.velocity;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.concurrent.CompletableFuture;

/**
 * Velocity 端 /mmsg 命令。
 *
 * <ul>
 *   <li>{@code /mmsg fj <玩家名> [服务器]} —— 全网广播虚假"进入"消息。</li>
 *   <li>{@code /mmsg fl <玩家名> [服务器]} —— 全网广播虚假"退出"消息。</li>
 *   <li>{@code /mmsg reload} —— 重载代理配置，并经消息通道通知所有
 *       有玩家在线的后端服务器重载各自配置。</li>
 * </ul>
 *
 * <p>虚假消息复用 {@code join.format} / {@code leave.format}，与真实消息
 * 格式完全一致；玩家名经 MiniMessage 转义防止标签注入。服务器参数缺省时
 * 取命令执行者（玩家）当前所在服务器；控制台执行时必须显式指定。</p>
 */
public final class VelocityCommand {

    /** 代理 → 后端的配置重载通知通道。 */
    public static final MinecraftChannelIdentifier RELOAD_CHANNEL =
            MinecraftChannelIdentifier.create("mikumsg", "reload");

    private final ProxyServer proxy;
    private final Object plugin;
    private final ProxyConfig config;
    private final MessageBroadcaster broadcaster;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public VelocityCommand(ProxyServer proxy, Object plugin, ProxyConfig config, MessageBroadcaster broadcaster) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.config = config;
        this.broadcaster = broadcaster;
    }

    public void register() {
        // 注：向代理→后端发送插件消息无需登记通道（ChannelRegistrar 仅用于接收/拦截，
        // 见 Velocity 官方 plugin-messaging 文档 Case 2）。后端各自 registerIncoming 即可。
        CommandMeta meta = proxy.getCommandManager().metaBuilder("mmsg")
                .plugin(plugin)
                .build();
        proxy.getCommandManager().register(meta, new BrigadierCommand(buildRoot()));
    }

    private LiteralCommandNode<CommandSource> buildRoot() {
        return LiteralArgumentBuilder.<CommandSource>literal("mmsg")
                .executes(ctx -> {
                    showHelp(ctx.getSource());
                    return 1;
                })
                .then(LiteralArgumentBuilder.<CommandSource>literal("fj")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                                .suggests(this::suggestPlayers)
                                .executes(ctx -> fake(ctx, true, null))
                                .then(RequiredArgumentBuilder.<CommandSource, String>argument("server", StringArgumentType.string())
                                        .suggests(this::suggestServers)
                                        .executes(ctx -> fake(ctx, true,
                                                StringArgumentType.getString(ctx, "server"))))))
                .then(LiteralArgumentBuilder.<CommandSource>literal("fl")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                                .suggests(this::suggestPlayers)
                                .executes(ctx -> fake(ctx, false, null))
                                .then(RequiredArgumentBuilder.<CommandSource, String>argument("server", StringArgumentType.string())
                                        .suggests(this::suggestServers)
                                        .executes(ctx -> fake(ctx, false,
                                                StringArgumentType.getString(ctx, "server"))))))
                .then(LiteralArgumentBuilder.<CommandSource>literal("reload")
                        .executes(this::reload))
                .build();
    }

    private int fake(CommandContext<CommandSource> ctx, boolean join, String serverArg) {
        if (!require(ctx.getSource(), "mikumsg.fake")) {
            return 0;
        }
        String playerName = StringArgumentType.getString(ctx, "player");
        // 显式指定的服务器参数是任意字符串（string() 允许引号内容），必须转义
        // MiniMessage 标签后才可进入全网广播，避免注入可点击/悬浮组件；
        // 执行者当前服务器取自代理登记表，属可信来源，无需转义。
        String server = resolveServer(ctx.getSource(),
                serverArg != null ? miniMessage.escapeTags(serverArg) : null);
        if (server == null) {
            ctx.getSource().sendMessage(Component.text(
                    "无法确定服务器：请显式指定服务器参数（控制台执行时必填）。", NamedTextColor.RED));
            return 0;
        }
        if (join) {
            broadcaster.broadcastFakeJoin(playerName, server);
        } else {
            broadcaster.broadcastFakeLeave(playerName, server);
        }
        return 1;
    }

    private int reload(CommandContext<CommandSource> ctx) {
        if (!require(ctx.getSource(), "mikumsg.reload")) {
            return 0;
        }
        config.load();
        int delivered = 0;
        for (RegisteredServer server : proxy.getAllServers()) {
            // 插件消息经该服务器上任一玩家连接转发；无玩家在线时无法送达
            if (server.sendPluginMessage(RELOAD_CHANNEL, new byte[0])) {
                delivered++;
            }
        }
        ctx.getSource().sendMessage(Component.text(
                "代理配置已重载；已通知 " + delivered + " 台后端服务器重载配置"
                        + "（无玩家在线的后端收不到通知，请在其后端控制台执行 /mmsg reload）。",
                NamedTextColor.GREEN));
        return 1;
    }

    /**
     * 解析虚假消息使用的服务器名：显式参数 → 执行者（玩家）当前服务器。
     * 控制台执行且未指定服务器时返回 null（由调用方提示补参），
     * 绝不回退到"任意一台登记服务器"——getAllServers 无顺序保证，猜错服务器会误导管理员。
     */
    private String resolveServer(CommandSource source, String explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        if (source instanceof Player player) {
            return player.getCurrentServer()
                    .map(c -> c.getServerInfo().getName())
                    .orElse(null);
        }
        return null;
    }

    private void showHelp(CommandSource source) {
        source.sendMessage(Component.text("MikuMsg 命令：", NamedTextColor.GOLD));
        source.sendMessage(Component.text("  /mmsg fj <玩家名> [服务器] - 虚假进入消息", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /mmsg fl <玩家名> [服务器] - 虚假退出消息", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /mmsg reload - 重载代理与后端配置", NamedTextColor.YELLOW));
    }

    /**
     * 权限判定，不足时给出明确提示并返回 false。
     *
     * <p>权限放在执行体内判定，而不是命令树的 {@code requires}：被 {@code requires}
     * 过滤掉的节点在客户端表现为"未知命令 / 参数错误"，使用者无从得知失败原因
     * （代理端权限需由 LuckPerms 等权限插件授予，没有权限提供者时玩家一律不通过）。</p>
     */
    private boolean require(CommandSource source, String permission) {
        if (source.hasPermission(permission)) {
            return true;
        }
        source.sendMessage(Component.text(
                "你没有权限执行该命令（需要 " + permission + "）。", NamedTextColor.RED));
        return false;
    }

    /** 玩家名补全：当前在线玩家。 */
    private CompletableFuture<Suggestions> suggestPlayers(
            CommandContext<CommandSource> ctx, SuggestionsBuilder builder) {
        for (Player online : proxy.getAllPlayers()) {
            builder.suggest(online.getUsername());
        }
        return builder.buildFuture();
    }

    /** 服务器名补全：代理登记的全部服务器。 */
    private CompletableFuture<Suggestions> suggestServers(
            CommandContext<CommandSource> ctx, SuggestionsBuilder builder) {
        for (RegisteredServer server : proxy.getAllServers()) {
            builder.suggest(server.getServerInfo().getName());
        }
        return builder.buildFuture();
    }
}
