package me.junxiex.mikumsg.paper;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicLong;

/**
 * MikuMsg-Paper —— 后端子服务器辅助插件（Paper 插件格式，Paper/Folia）。
 *
 * <p>抑制本服务器原生的玩家进入/退出消息，避免与 Velocity 端 MikuMsg 的全网同步
 * 消息重复刷屏。</p>
 *
 * <p>同时监听代理的 {@code mikumsg:reload} 插件消息通道：管理员在 Velocity 端执行
 * {@code /mmsg reload} 时，本服配置一并重载。</p>
 *
 * @author JunXieX
 */
public final class MikuMsgPaper extends JavaPlugin {

    /** 与 Velocity 端 VelocityCommand.RELOAD_CHANNEL 一致。 */
    public static final String RELOAD_CHANNEL = "mikumsg:reload";

    /**
     * 代理 reload 通知的最小处理间隔（纳秒）。
     *
     * <p>该通道本意只承载"代理 → 后端"的配置重载通知，但同通道的客户端自定义
     * 数据包同样会进入 {@code registerIncomingPluginChannel} 的回调（服务端不校验
     * 发送方），因此必须按不可信输入设防：否则伪造客户端可持续触发
     * {@code reloadConfig()}（磁盘读 + YAML 解析）造成重复 IO。一次合法的
     * {@code /mmsg reload} 对每台后端只投递一条通知，1 秒冷却足以放行正常操作、
     * 抑制滥用。</p>
     */
    private static final long CHANNEL_RELOAD_COOLDOWN_NANOS = 1_000_000_000L;

    /** 上次经通道重载的时间戳（原子 CAS，回调在 Paper 主线程 / Folia 区域线程执行）。 */
    private final AtomicLong lastChannelReloadNanos = new AtomicLong();

    /** 配置对象：reload 时整体替换引用（volatile 保证跨线程可见性）。 */
    private volatile Config config;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new Config(this);

        // 初值回拨一个冷却周期，使启用后的第一条合法通知不必等待即可通过
        lastChannelReloadNanos.set(System.nanoTime() - CHANNEL_RELOAD_COOLDOWN_NANOS);

        getServer().getPluginManager().registerEvents(new JoinQuitListener(this), this);
        getServer().getMessenger().registerIncomingPluginChannel(this, RELOAD_CHANNEL,
                (channel, player, message) -> {
                    // 插件消息回调在 Paper 为主线程、Folia 为玩家所在区域线程。
                    // reloadConfig() 仅做文件读取与内存对象更新，不触碰世界/实体状态，
                    // 任意线程均可安全执行；绝不能用 Bukkit 调度器（Folia 已禁用）。
                    long now = System.nanoTime();
                    long previous = lastChannelReloadNanos.get();
                    if (now - previous < CHANNEL_RELOAD_COOLDOWN_NANOS) {
                        getLogger().fine("忽略冷却期内的配置重载通知（可能为伪造的客户端数据包）。");
                        return;
                    }
                    if (!lastChannelReloadNanos.compareAndSet(previous, now)) {
                        // 另一线程已处理本次并发通知，无需重复重载
                        return;
                    }
                    reloadPluginConfig();
                });

        // 命令走 Paper 原生的 Brigadier + 生命周期注册：Paper 插件格式
        // （paper-plugin.yml）不支持 commands 区段，同一进程内的重名冲突由 helper 兜底。
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                registerQuietly(event.registrar(), new PaperCommand(this).create(), "MikuMsg 后端管理命令"));

        getLogger().info("MikuMsg (Paper) 已启用，原生进出消息抑制已就绪。");
    }

    /**
     * 注册命令并吞掉重名异常。
     *
     * <p>同名命令可能已被其它插件占用，Brigadier 注册冲突时会抛
     * {@link IllegalArgumentException}；放任抛出会中断命令调度器构建，连累其它插件的命令。</p>
     */
    private void registerQuietly(Commands commands, LiteralCommandNode<CommandSourceStack> node, String description) {
        try {
            commands.register(node, description);
        } catch (IllegalArgumentException e) {
            getLogger().warning("命令 /" + node.getName() + " 注册失败（与其它插件重名）：" + e.getMessage());
        }
    }

    public Config cfg() {
        return config;
    }

    /**
     * 重载配置。synchronized 防止多个区域线程同时收到代理 reload 通知时，
     * Bukkit 的 reloadConfig()（非线程安全）被并发调用。
     */
    public synchronized void reloadPluginConfig() {
        reloadConfig();
        // 整体替换引用而非原地改字段：配合 volatile，读线程要么看到旧配置
        // 要么看到新配置，不会出现半更新状态
        this.config = new Config(this);
    }
}