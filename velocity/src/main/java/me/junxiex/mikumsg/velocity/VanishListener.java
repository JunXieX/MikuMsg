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
 * 解除隐身 → 玩家仍在线且落点有效时补发进入消息并恢复可见表。</p>
 *
 * <p><b>并发</b>：本监听器由 MikuVanish 的命令/插件消息线程调用，与 Netty 连接线程上的
 * {@link ConnectionListener#onServerPostConnect} 并发。因此可见登记表的读改写不在本类
 * 直接进行，而是全部委托给 {@link ConnectionListener#beginVanish}/{@link ConnectionListener#endVanish}
 * ——它们在同一把锁内完成，杜绝"登记与撤销交错导致消息丢失或错报"。</p>
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
    private final Logger logger;

    VanishListener(ConnectionListener connections, Logger logger) {
        this.connections = connections;
        this.logger = logger;
    }

    @Subscribe
    public void onVanishStateChange(VanishStateChangeEvent event) {
        UUID playerId = event.uuid();
        logger.info("[隐身] 收到状态变更：{} → {}（服务器 {}）",
                event.username(), event.vanished() ? "隐身" : "现身", event.serverId());
        if (event.vanished()) {
            // 登记表变更与离线消息补发在 ConnectionListener 的同一临界区内完成，
            // 避免与 onServerPostConnect 的登记交错导致这次隐身被静默或消息乱序
            if (!connections.beginVanish(playerId, event.username())) {
                logger.info("[隐身] {} 此前未对外播报（如进服时已隐身），按设计不补发离线消息", event.username());
            }
            return;
        }
        // 解除隐身：仅在玩家仍在线且落点有效（非空、非认证服）时登记并补发进入消息。
        // 玩家已离线时不登记，否则会留下永不清除的幽灵记录、静默掉其下次上线的进入消息。
        if (!connections.endVanish(playerId, event.username(), event.serverId())) {
            logger.info("[隐身] {} 解除隐身但落点无效、玩家已离线或仍在认证服，按设计不补发进入消息",
                    event.username());
        }
    }
}