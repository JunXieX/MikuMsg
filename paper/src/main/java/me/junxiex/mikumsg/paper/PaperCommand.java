package me.junxiex.mikumsg.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/**
 * {@code /mmsg} 后端管理命令（Paper Brigadier 命令树）。
 *
 * <p>Paper 插件格式（paper-plugin.yml）不支持 {@code commands} 区段，命令由生命周期
 * COMMANDS 事件注册；本命令树带真实参数结构，客户端命令树因此完整，不会再出现
 * "参数错误"这类解析失败提示。</p>
 *
 * <p>权限在执行体内判定并给出明确提示，而非用 {@code requires} 过滤节点——被过滤掉的
 * 节点在客户端表现为"未知命令"，使用者无从得知失败原因。</p>
 *
 * <p>玩家在游戏内输入的 {@code /mmsg} 由 Velocity 代理拦截处理（代理注册了同名命令），
 * 本命令主要服务于后端控制台。</p>
 */
final class PaperCommand {

    private final MikuMsgPaper plugin;

    PaperCommand(MikuMsgPaper plugin) {
        this.plugin = plugin;
    }

    /** 构建命令树。 */
    LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("mmsg")
                .executes(ctx -> {
                    help(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("reload").executes(this::reload))
                .build();
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission("mikumsg.reload")) {
            sender.sendMessage(Component.text("你没有权限执行该命令（需要 mikumsg.reload）。", NamedTextColor.RED));
            return 0;
        }
        plugin.reloadPluginConfig();
        sender.sendMessage(Component.text("MikuMsg 配置已重载。", NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("MikuMsg 后端命令：", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  /mmsg reload - 重载本服配置", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  虚假进出消息请在代理端执行 /mmsg fj|fl", NamedTextColor.GRAY));
    }
}