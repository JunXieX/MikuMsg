package me.junxiex.mikumsg.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 抑制本服务器原生的玩家进入/退出消息。
 *
 * <p>在 MONITOR（最后）优先级将消息置空，确保无论默认消息还是其他插件
 * 设置的自定义消息都不再向本服玩家广播——全网消息已由 Velocity 端统一
 * 同步，避免重复刷屏。事件处理为纯同步轻量操作，天然兼容 Folia。</p>
 */
public final class JoinQuitListener implements Listener {

    private final MikuMsgPaper plugin;

    public JoinQuitListener(MikuMsgPaper plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.cfg().suppressJoin()) {
            event.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.cfg().suppressQuit()) {
            event.quitMessage(null);
        }
    }
}
