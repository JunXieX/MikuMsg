package me.junxiex.mikumsg.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接事件监听器：过滤未认证与隐身玩家后，把事件交给广播器。
 *
 * <p>过滤规则：</p>
 * <ul>
 *   <li><b>未认证</b>（MikuAuth 已装）：滞留在认证服等待登录的玩家不广播；
 *       其完成登录被送往正式服时，按"进入"补报一条 join 消息。</li>
 *   <li><b>隐身</b>（MikuVanish 已装）：处于隐身状态的玩家，其进入/退出/切换
 *       一律不广播——隐身期间该玩家的网络存在对外界不可见；进入隐身时补发一条
 *       离线消息、解除隐身时补发进入消息（见 {@link VanishListener}）。</li>
 *   <li><b>退出</b>：只对"曾播报过进入"的玩家播报退出，服务器名取自缓存而非
 *       实时查询——MikuAuth 在 {@link DisconnectEvent} 中会清理认证状态，两个
 *       插件监听器的触发顺序不保证，实时查询存在竞态；且断开后
 *       {@code getCurrentServer()} 的可用性不属 API 契约，故一并规避。</li>
 *   <li><b>认证服跳转静默</b>：与认证服（默认 {@code limbo}）相关的切换不播报，
 *       避免"limbo → 生存服"或后端故障回退造成的噪音。</li>
 * </ul>
 */
public final class ConnectionListener {

    private final MessageBroadcaster broadcaster;
    /** 为 null 表示 MikuAuth 未安装/版本过旧，不做认证过滤。 */
    private final MikuAuthBridge bridge;
    /** 为 null 表示 MikuVanish 未安装，不做隐身过滤。 */
    private final VanishBridge vanish;
    private final ProxyConfig config;

    /**
     * 已播报过"进入"的玩家 → 其最近一次所在服务器的注册名。
     * 断开时移除并用作退出消息的服务器名。
     */
    private final Map<UUID, String> announced = new ConcurrentHashMap<>();

    public ConnectionListener(MessageBroadcaster broadcaster, MikuAuthBridge bridge,
                              VanishBridge vanish, ProxyConfig config) {
        this.broadcaster = broadcaster;
        this.bridge = bridge;
        this.vanish = vanish;
        this.config = config;
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // 未认证玩家（等待登录/注册）：不播报
        if (bridge != null && !bridge.isAuthenticated(playerId)) {
            return;
        }
        // 隐身玩家：进入/切换一律不播报（其网络存在对外界不可见）
        if (vanish != null && vanish.isVanished(playerId)) {
            return;
        }
        String newServer = currentServerName(player);
        if (newServer == null) {
            return;
        }
        // 落入认证服（未认证流程/故障回退）：静默
        if (isAuthServer(newServer)) {
            return;
        }
        RegisteredServer previous = event.getPreviousServer();
        boolean wasAnnounced = announced.put(playerId, newServer) != null;
        // 与隐身翻转并发时的兜底：登记后若已进入隐身，撤销登记并静默。
        // （隐身事件由命令线程发布，连接事件在连接线程，存在微秒级窗口）
        if (vanish != null && vanish.isVanished(playerId)) {
            announced.remove(playerId);
            return;
        }
        if (previous == null || isAuthServer(previous.getServerInfo().getName())) {
            // 首次进入，或从认证服转出（登录完成补报）。
            // 已播报过且来自认证服 = 后端故障回退后自动送回：玩家从未离开网络，静默。
            if (!wasAnnounced) {
                broadcaster.broadcastJoin(player.getUsername(), newServer);
            }
            return;
        }
        if (wasAnnounced) {
            // 正式服之间跳转：按"切换"播报
            broadcaster.broadcastChange(player.getUsername(), newServer, previous.getServerInfo().getName());
        } else {
            // 首次出现但前置服不是认证服：两种可达路径——auth-server 配置与实际不符，
            // 或该玩家曾隐身进入网络（隐身期间被移出 announced）现已解除隐身并跨服。
            // 按"进入"补报，避免消息被永久吞掉。
            broadcaster.broadcastJoin(player.getUsername(), newServer);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        // 只给播报过进入的玩家播报退出；批量假玩家（从未认证）在此被自然过滤
        String lastServer = announced.remove(playerId);
        if (lastServer == null) {
            return;
        }
        // 隐身玩家断开不播报退出（其存在对外不可见）
        if (vanish != null && vanish.isVanished(playerId)) {
            return;
        }
        broadcaster.broadcastLeave(player.getUsername(), lastServer);
    }

    /**
     * 从可见登记表移除并返回其最后所在服务器（供 {@link VanishListener} 进入隐身时调用）。
     *
     * @return 最后一次对外播报的服务器名；从未播报过返回 null
     */
    String forgetVisible(UUID playerId) {
        return announced.remove(playerId);
    }

    /** 恢复可见登记（供 {@link VanishListener} 解除隐身时调用）。 */
    void markVisible(UUID playerId, String serverName) {
        announced.put(playerId, serverName);
    }

    /** 该服务器是否为认证服（MikuAuth 的 limbo）。 */
    boolean isAuthServer(String serverName) {
        return serverName.equalsIgnoreCase(config.authServer());
    }

    private String currentServerName(Player player) {
        Optional<ServerConnection> current = player.getCurrentServer();
        return current.map(c -> c.getServerInfo().getName()).orElse(null);
    }
}
