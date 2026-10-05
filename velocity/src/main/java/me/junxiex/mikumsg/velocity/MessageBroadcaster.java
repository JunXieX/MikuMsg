package me.junxiex.mikumsg.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

/**
 * 消息广播器：把进入/退出/切换事件格式化为 MiniMessage 组件，
 * 并通过 {@link ProxyServer#sendMessage} 一次性发送给全网所有在线玩家。
 *
 * <p>Velocity 的 {@code sendMessage} 会路由到所有已连接玩家，因此无需
 * 在后端安装同步插件即可实现"消息同步到后端子服务器"。</p>
 *
 * <p>服务器名由调用方（监听器）查询并判空后传入，本类不再重复查询
 * {@code player.getCurrentServer()}，避免冗余与潜在的 NPE。</p>
 *
 * <p>占位符采用单遍扫描填充（{@link #fill}）：替换结果不会被二次扫描，
 * 因此即使虚假玩家名或服务器参数中含有 {@code %server%} 等占位符字面量，
 * 也不会被再次展开；玩家名另经 MiniMessage 标签转义，双重防护注入。</p>
 */
public final class MessageBroadcaster {

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final Logger logger;
    private final MiniMessage miniMessage;

    public MessageBroadcaster(ProxyServer proxy, ProxyConfig config, Logger logger) {
        this.proxy = proxy;
        this.config = config;
        this.logger = logger;
        this.miniMessage = MiniMessage.miniMessage();
    }

    /**
     * 广播"进入"消息。
     *
     * @param username   玩家名
     * @param serverName 玩家当前所在服务器的注册名（非 null，已由调用方解析）
     */
    public void broadcastJoin(String username, String serverName) {
        if (!config.isJoinEnabled()) {
            return;
        }
        send(fill(config.joinFormat(), username, config.alias(serverName), null));
    }

    /**
     * 广播"退出"消息。
     *
     * @param username   玩家名
     * @param serverName 玩家断开前（或对外最后一次可见时）所在服务器的注册名
     */
    public void broadcastLeave(String username, String serverName) {
        if (!config.isLeaveEnabled()) {
            return;
        }
        send(fill(config.leaveFormat(), username, config.alias(serverName), null));
    }

    /**
     * 广播"切换服务器"消息。
     *
     * @param username      玩家名
     * @param newServerName 切换后所在服务器的注册名（非 null，已由调用方解析）
     * @param oldServerName 切换前所在服务器的注册名（非 null，已由调用方解析）
     */
    public void broadcastChange(String username, String newServerName, String oldServerName) {
        if (!config.isChangeEnabled()) {
            return;
        }
        send(fill(config.changeFormat(), username, config.alias(newServerName), config.alias(oldServerName)));
    }

    /**
     * 广播虚假"进入"消息（管理员命令触发，复用真实进入格式）。
     *
     * <p>不受 {@code join.enabled} 约束：该开关只控制自动播报，管理员
     * 显式执行 /mmsg fj 时若被开关静默吞掉会造成命令"无响应"的困惑。</p>
     *
     * @param playerName 虚假玩家名（任意字符串，已做注入防护）
     * @param serverName 服务器注册名
     */
    public void broadcastFakeJoin(String playerName, String serverName) {
        send(fill(config.joinFormat(), escapeTags(playerName), config.alias(serverName), null));
    }

    /**
     * 广播虚假"退出"消息（管理员命令触发，复用真实退出格式）。
     * 同 {@link #broadcastFakeJoin}：显式命令不受 {@code leave.enabled} 约束。
     *
     * @param playerName 虚假玩家名（任意字符串，已做注入防护）
     * @param serverName 服务器注册名
     */
    public void broadcastFakeLeave(String playerName, String serverName) {
        send(fill(config.leaveFormat(), escapeTags(playerName), config.alias(serverName), null));
    }

    /**
     * 单遍扫描填充占位符：{@code %player%}、{@code %server%}、{@code %old_server%}。
     *
     * <p>与链式 {@link String#replace} 不同，替换产生的内容不会被再次扫描，
     * 杜绝"值里含占位符字面量被二次展开"的注入。未识别的 {@code %...%}
     * 序列原样保留。{@code oldServer} 为 null 时 {@code %old_server%} 填充为空串。</p>
     */
    private static String fill(String format, String player, String server, String oldServer) {
        StringBuilder out = new StringBuilder(format.length() + 32);
        int i = 0;
        int len = format.length();
        while (i < len) {
            if (format.charAt(i) == '%') {
                if (format.startsWith("%player%", i)) {
                    out.append(player);
                    i += "%player%".length();
                    continue;
                }
                if (format.startsWith("%old_server%", i)) {
                    out.append(oldServer != null ? oldServer : "");
                    i += "%old_server%".length();
                    continue;
                }
                if (format.startsWith("%server%", i)) {
                    out.append(server != null ? server : "");
                    i += "%server%".length();
                    continue;
                }
            }
            out.append(format.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** 转义玩家名中的 MiniMessage 标签，防止通过假名注入格式或点击事件。 */
    private String escapeTags(String input) {
        return miniMessage.escapeTags(input);
    }

    private void send(String miniMessageText) {
        Component component;
        try {
            component = miniMessage.deserialize(miniMessageText);
        } catch (Exception ex) {
            // 配置里的 MiniMessage 标签非法（如未闭合）时降级为纯文本广播，
            // 不让单条消息因解析失败而静默丢失
            logger.warn("MikuMsg 消息解析失败，已降级为纯文本：{}", ex.getMessage());
            component = Component.text(miniMessageText);
        }
        proxy.sendMessage(component);
    }
}
