package me.junxiex.mikumsg.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * MikuMsg —— Velocity 端消息同步插件。
 *
 * <p>监听玩家进入、退出、切换服务器三类事件，并将格式化后的消息一次性广播给
 * 全网所有在线玩家（即"同步到后端子服务器"）。配合后端 MikuMsg-Paper 插件
 * 抑制各后端子服务器自带的进出消息，避免重复刷屏。</p>
 *
 * <p>与 MikuAuth 联动：未完成登录的玩家（含批量假玩家压测连接）的进出/切换
 * 消息一律不广播，防止聊天栏被虚假消息刷屏。</p>
 *
 * <p>与 MikuVanish 联动：处于隐身状态的玩家，其进出/切换消息一律不广播；
 * 进入隐身时补发一条离线消息、解除隐身时补发进入消息（由 MikuVanish
 * 发布的状态变更事件驱动，无轮询）。</p>
 *
 * @author JunXieX
 */
@Plugin(
        id = "mikumsg",
        name = "MikuMsg",
        version = "1.5.0",
        description = "Velocity 端玩家进入/退出/切换服务器消息全网同步（联动 MikuAuth 过滤未登录玩家）",
        authors = {"JunXieX"},
        dependencies = {
                @Dependency(id = "mikuauth", optional = true),
                @Dependency(id = "mikuvanish", optional = true)
        }
)
public final class MikuMsgVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public MikuMsgVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path injectedDataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        // @DataDirectory 注入的是 plugins/<插件id>（全小写 mikumsg）。这里改用插件显示名，
        // 与 MikuAuth 的 plugins/MikuAuth 约定一致：Linux 区分大小写，两套目录名会让人
        // 以为配置丢了；Windows 不区分，实际是同一目录。
        this.dataDirectory = injectedDataDirectory.getParent().resolve("MikuMsg");
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        ProxyConfig config = new ProxyConfig(dataDirectory, logger);
        config.load();
        MessageBroadcaster broadcaster = new MessageBroadcaster(proxy, config, logger);

        MikuAuthBridge auth = MikuAuthBridge.discover(proxy, logger);
        VanishBridge vanish = VanishBridge.discover(logger);

        ConnectionListener connections = new ConnectionListener(broadcaster, auth, vanish, config);
        proxy.getEventManager().register(this, connections);
        if (vanish != null && vanish.supportsStateEvents()) {
            // 仅当 MikuVanish 提供状态变更事件时注册：该监听器的方法签名引用其事件类型，
            // 而反射扫描方法签名在类型缺失时会抛 NoClassDefFoundError——未安装或版本
            // 过旧时不注册，VanishListener 整类不会被加载，主监听器不受影响。
            proxy.getEventManager().register(this, new VanishListener(connections, broadcaster, logger));
        }
        new VelocityCommand(proxy, this, config, broadcaster).register();
        logger.info("MikuMsg (Velocity) 已启用。");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        logger.info("MikuMsg (Velocity) 已停用。");
    }
}
