package me.junxiex.mikumsg.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
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
 *
 * <p><b>并发约束</b>：本类同时被两条线程写入——连接事件在 Netty 连接线程、
 * 隐身翻转事件在 MikuVanish 的命令/插件消息线程。二者对 {@link #announced} 的
 * 操作都是"读改写"，因此必须互斥：否则会出现"登记后立刻被隐身方撤销、导致该次
 * 隐身静默"或"隐身方取到了尚未对外播报过的服务器名、补发一条错误离线消息"。</p>
 *
 * <p>状态迁移、隐身状态复查与消息发出统一在 {@link #lock} 内完成（<b>广播也在锁内</b>）：
 * 消息的先后顺序本身就是本插件的正确性要求，若把广播挪出临界区，"登记 → 判定隐身 →
 * 广播"之间会重新出现窗口，使隐身玩家的进入消息与其离线消息乱序。锁内只做纯 CPU
 * 的格式化与一次全网发包，不阻塞、不回调外部代码，故不构成性能或死锁风险。</p>
 */
public final class ConnectionListener {

    private final MessageBroadcaster broadcaster;
    /** 为 null 表示 MikuAuth 未安装/版本过旧，不做认证过滤。 */
    private final MikuAuthBridge bridge;
    /** 为 null 表示 MikuVanish 未安装，不做隐身过滤。 */
    private final VanishBridge vanish;
    private final ProxyConfig config;
    private final ProxyServer proxy;

    /**
     * 已播报过"进入"的玩家 → 其最近一次所在服务器的注册名。
     * 断开时移除并用作退出消息的服务器名。
     */
    private final Map<UUID, String> announced = new ConcurrentHashMap<>();

    /** 保护 {@link #announced} 复合状态迁移的互斥锁（连接线程与隐身事件线程共用）。 */
    private final Object lock = new Object();

    public ConnectionListener(MessageBroadcaster broadcaster, MikuAuthBridge bridge,
                              VanishBridge vanish, ProxyConfig config, ProxyServer proxy) {
        this.broadcaster = broadcaster;
        this.bridge = bridge;
        this.vanish = vanish;
        this.config = config;
        this.proxy = proxy;
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
        String previousName = previous == null ? null : previous.getServerInfo().getName();

        // 状态迁移、隐身复查与消息发出必须在同一临界区内完成：
        // 若把广播挪到锁外，则"登记→判定隐身→广播"之间会重新出现窗口——玩家在
        // 释放锁后、广播前被置为隐身时，隐身方会先补发一条离线消息，本线程再补发
        // 一条进入消息，顺序颠倒，使隐身玩家反而在聊天栏露脸。
        // 代价说明：临界区包含一次全网广播（O(在线人数) 次发包）。本监听器只被
        // "连接事件线程"与"隐身事件线程"两条线程触碰，二者都是低频事件；即便开服
        // 瞬间 K 人同时进服，串行化后的总工作量仍与并行时相同（皆为 K×O(人数)），
        // 只是不再并行，实测量级为毫秒级的一次性开销——用可忽略的开销换取消息顺序
        // 绝对正确，对本插件的唯一目的（消息同步）是划算的。
        synchronized (lock) {
            // 与隐身翻转并发时的兜底：若此刻已进入隐身，绝不登记（否则会留下一条
            // 对隐身玩家可见的记录，且与 VanishListener 的撤销交错导致消息丢失）
            if (vanish != null && vanish.isVanished(playerId)) {
                return;
            }
            boolean wasAnnounced = announced.put(playerId, newServer) != null;
            if (previousName == null || isAuthServer(previousName)) {
                // 首次进入，或从认证服转出（登录完成补报）。
                // 已播报过且来自认证服 = 后端故障回退后自动送回：玩家从未离开网络，静默。
                if (!wasAnnounced) {
                    broadcaster.broadcastJoin(player.getUsername(), newServer);
                }
            } else if (wasAnnounced) {
                // 正式服之间跳转：按"切换"播报
                broadcaster.broadcastChange(player.getUsername(), newServer, previousName);
            } else {
                // 首次出现但前置服不是认证服：两种可达路径——auth-server 配置与实际不符，
                // 或该玩家曾隐身进入网络（隐身期间被移出 announced）现已解除隐身并跨服。
                // 按"进入"补报，避免消息被永久吞掉。
                broadcaster.broadcastJoin(player.getUsername(), newServer);
            }
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        // 只给播报过进入的玩家播报退出；批量假玩家（从未认证）在此被自然过滤。
        // 移除登记与广播同处一个临界区：否则"移除登记"与"发出离线消息"之间，
        // 隐身事件可能插入一条重复的离线消息。
        synchronized (lock) {
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
    }

    /**
     * 进入隐身：移出可见登记表，并在同一临界区内补发离线消息（供 {@link VanishListener} 调用）。
     *
     * <p>与连接事件共用 {@link #lock}，避免与 {@code onServerPostConnect} 的登记交错。</p>
     *
     * @param username 玩家名（用于补发离线消息）
     * @return true = 此前对外可见，已补发离线消息；false = 此前未播报过，无需补发
     */
    boolean beginVanish(UUID playerId, String username) {
        synchronized (lock) {
            String lastServer = announced.remove(playerId);
            if (lastServer == null) {
                return false;
            }
            broadcaster.broadcastLeave(username, lastServer);
            return true;
        }
    }

    /**
     * 解除隐身：恢复可见登记并补发进入消息（供 {@link VanishListener} 调用）。
     *
     * <p>仅在玩家<b>仍在线</b>且落点有效时才登记：解除隐身事件可能在玩家断开后才到达
     * （事件经命令/插件消息线程异步发布），若此时盲目登记，会留下一条永不清除的
     * 幽灵记录——该玩家下次上线时会被误判为"已播报过"，从而静默掉一次正常的进入消息。</p>
     *
     * @return true = 已登记并补发进入消息；false = 玩家已离线或落点无效，不补发
     */
    boolean endVanish(UUID playerId, String username, String serverName) {
        if (serverName == null || serverName.isEmpty() || isAuthServer(serverName)) {
            return false;
        }
        synchronized (lock) {
            // 在线性检查放在锁内，与断开事件的移除操作互斥：
            // 若玩家已断开，绝不能登记，否则该条目永不被清除
            if (!isOnline(playerId)) {
                return false;
            }
            announced.put(playerId, serverName);
            broadcaster.broadcastJoin(username, serverName);
            return true;
        }
    }

    /** 玩家是否仍在线且这条连接有效。 */
    private boolean isOnline(UUID playerId) {
        return proxy.getPlayer(playerId).filter(Player::isActive).isPresent();
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
