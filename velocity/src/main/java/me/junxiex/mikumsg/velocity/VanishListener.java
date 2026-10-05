package me.junxiex.mikumsg.velocity;

import com.velocitypowered.api.event.Subscribe;
import dev.junxiex.mikuvanish.api.VanishStateChangeEvent;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * 隐身状态翻转监听：补发瞬时消息，保持对外可见性与消息流一致。
 *
 * <p>进入隐身 → 若此前对外可见（在可见登记表中），用最后一次播报的服务器补发一条
 * 离线消息，并把玩家移出可见表——此后其退出/切换都不再播报；
 * 解除隐身 → 补发进入消息并恢复可见表。</p>
 *
 * <p>每次收到事件都会记录一行日志（含方向与落点），用于排查"某次切换为何没有消息"：
 * 日志出现说明事件已到达本插件（问题在消息分支），日志缺失说明事件未发布（上游问题）。</p>
 *
 * <p><b>为何独立成类</b>：本类的方法签名引用了 MikuVanish 的类型。反射扫描监听器
 * （Velocity 注册事件时解析方法参数类型）在类型缺失时会抛
 * {@code NoClassDefFoundError}，因此本类<b>只在 MikuVanish 提供该事件时才注册</b>
 * （见 {@link VanishBridge#supportsStateEvents()}），未安装或版本过旧时整类不被加载，
 * 不影响主监听器的注册与插件启动。</p>
 */
final class VanishListener {

    private final ConnectionListener connections;
    private final MessageBroadcaster broadcaster;
    private final Logger logger;

    VanishListener(ConnectionListener connections, MessageBroadcaster broadcaster, Logger logger) {
        this.connections = connections;
        this.broadcaster = broadcaster;
        this.logger = logger;
    }

    @Subscribe
    public void onVanishStateChange(VanishStateChangeEvent event) {
        UUID playerId = event.uuid();
        logger.info("[隐身] 收到状态变更：{} → {}（服务器 {}）",
                event.username(), event.vanished() ? "隐身" : "现身", event.serverId());
        if (event.vanished()) {
            String lastServer = connections.forgetVisible(playerId);
            if (lastServer != null) {
                broadcaster.broadcastLeave(event.username(), lastServer);
            } else {
                logger.info("[隐身] {} 此前未对外播报（如进服时已隐身），按设计不补发离线消息", event.username());
            }
            return;
        }
        // 解除隐身：需有有效落点。未认证玩家被 MikuAuth 限制在认证服内，
        // 故认证服判定即可覆盖（无需再查认证状态）。
        String server = event.serverId();
        if (server.isEmpty() || connections.isAuthServer(server)) {
            logger.info("[隐身] {} 解除隐身但落点无效或仍在认证服，按设计不补发进入消息", event.username());
            return;
        }
        connections.markVisible(playerId, server);
        broadcaster.broadcastJoin(event.username(), server);
    }
}