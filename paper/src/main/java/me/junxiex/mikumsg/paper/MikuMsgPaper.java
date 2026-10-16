package me.junxiex.mikumsg.paper;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

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
     * 经通道重载的最小执行间隔（纳秒）。
     *
     * <p>该通道本意只承载"代理 → 后端"的配置重载通知，但同通道的客户端自定义
     * 数据包同样会进入 {@code registerIncomingPluginChannel} 的回调（服务端不校验
     * 发送方），因此必须按不可信输入设防：否则伪造客户端可持续触发
     * {@code reloadConfig()}（磁盘读 + YAML 解析）造成重复 IO。</p>
     *
     * <p>冷却期内的合法请求<b>不会被丢弃</b>，而是合并为一次补偿重载、在冷却结束时
     * 执行（见 {@link #requestChannelReload()}）——管理员连续两次 {@code /mmsg reload}
     * 都能生效，同时把实际重载频率限制在每秒一次。</p>
     */
    private static final long CHANNEL_RELOAD_COOLDOWN_NANOS = 1_000_000_000L;

    /** 重载节流状态：lastReloadNanos 为上次<b>实际执行</b>重载的时间戳。 */
    private final Object reloadGate = new Object();
    private long lastReloadNanos;
    private boolean reloadPending;

    /** 配置对象：reload 时整体替换引用（volatile 保证跨线程可见性）。 */
    private volatile Config config;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new Config(this);

        // 初值回拨一个冷却周期，使启用后的第一条合法通知不必等待即可通过
        synchronized (reloadGate) {
            lastReloadNanos = System.nanoTime() - CHANNEL_RELOAD_COOLDOWN_NANOS;
        }

        getServer().getPluginManager().registerEvents(new JoinQuitListener(this), this);
        getServer().getMessenger().registerIncomingPluginChannel(this, RELOAD_CHANNEL,
                (channel, player, message) -> requestChannelReload());

        // 命令走 Paper 原生的 Brigadier + 生命周期注册：Paper 插件格式
        // （paper-plugin.yml）不支持 commands 区段，注册失败由 helper 兜底。
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                registerQuietly(event.registrar(), new PaperCommand(this).create(), "MikuMsg 后端管理命令"));

        getLogger().info("MikuMsg (Paper) 已启用，原生进出消息抑制已就绪。");
    }

    /**
     * 处理一条经通道到达的重载请求（回调在 Paper 主线程 / Folia 玩家所在区域线程）。
     *
     * <p>冷却期外立即重载；冷却期内登记一次补偿重载，延迟到窗口结束时执行。
     * 这样既压制伪造客户端包的触发频率，又不丢任何一次合法操作。</p>
     */
    private void requestChannelReload() {
        long delayNanos;
        synchronized (reloadGate) {
            long now = System.nanoTime();
            long elapsed = now - lastReloadNanos;
            if (elapsed >= CHANNEL_RELOAD_COOLDOWN_NANOS) {
                lastReloadNanos = now;
                delayNanos = -1L;
            } else if (!reloadPending) {
                // 窗口内首个请求：登记补偿重载，其余请求在窗口内被合并
                reloadPending = true;
                delayNanos = CHANNEL_RELOAD_COOLDOWN_NANOS - elapsed;
            } else {
                // 已排定补偿重载，本次无需重复
                delayNanos = -2L;
            }
        }
        if (delayNanos == -1L) {
            reloadPluginConfig();
        } else if (delayNanos >= 0L) {
            try {
                // 补偿重载同样要落在「全局区域线程」而非异步池：reloadConfig() 属 Bukkit API，
                // 放到异步线程是跨线程访问。GlobalRegionScheduler 在 Paper 与 Folia 上通用，
                // 延迟单位为 tick（1 秒 = 20 tick），并至少推迟 1 tick 以避开窗口边界。
                long delayTicks = Math.max(1L, (delayNanos + 49_999_999L) / 50_000_000L);
                getServer().getGlobalRegionScheduler().runDelayed(this, task -> runPendingReload(), delayTicks);
            } catch (RuntimeException e) {
                // 插件停用瞬间调度会被拒（IllegalPluginAccessException）：此时放弃补偿重载即可，
                // 需复位 pending 标记，避免后续请求被误判为"已排定"而永久吞掉。
                synchronized (reloadGate) {
                    reloadPending = false;
                }
                getLogger().fine("插件停用中，已跳过延迟配置重载：" + e.getMessage());
            }
        }
    }

    /** 执行被合并的那次补偿重载。 */
    private void runPendingReload() {
        synchronized (reloadGate) {
            reloadPending = false;
            lastReloadNanos = System.nanoTime();
        }
        reloadPluginConfig();
    }

    /**
     * 注册命令并吞掉注册异常。
     *
     * <p>注册位于 COMMANDS 生命周期回调内，异常一旦抛出会中断命令调度器构建、连累其它插件，
     * 因此这里兜住所有运行时异常并降级为告警。注意 Paper 对同根命令的冲突处理是<b>静默覆盖</b>
     * （不抛异常），故此处无法探测"覆盖了别人的 /mmsg"，只能保证自身注册失败时不外溢。</p>
     */
    private void registerQuietly(Commands commands, LiteralCommandNode<CommandSourceStack> node, String description) {
        try {
            commands.register(node, description);
        } catch (RuntimeException e) {
            getLogger().warning("命令 /" + node.getName() + " 注册失败：" + e.getMessage());
        }
    }

    public Config cfg() {
        return config;
    }

    /**
     * 重载配置。synchronized 防止多个区域线程同时收到代理 reload 通知时，
     * Bukkit 的 reloadConfig()（非线程安全）被并发调用。
     *
     * <p>先自行严格解析一遍文件：Bukkit 的 {@code reloadConfig()} 在 YAML 语法错误时
     * 只打一条 SEVERE 就<b>静默回落为默认值</b>，管理员会看到"配置已重载"却拿到和磁盘
     * 内容不一致的默认配置。这里显式检测语法错误，失败则保留当前配置并给出明确告警。</p>
     *
     * @return true = 已按磁盘内容重载；false = 文件解析失败，已沿用当前配置
     */
    public synchronized boolean reloadPluginConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (file.isFile() && !isConfigParseable(file)) {
            getLogger().warning("config.yml 解析失败（YAML 语法错误），本次重载未生效，沿用当前配置。");
            return false;
        }
        reloadConfig();
        // 整体替换引用而非原地改字段：配合 volatile，读线程要么看到旧配置
        // 要么看到新配置，不会出现半更新状态
        this.config = new Config(this);
        return true;
    }

    /** 该文件能否被严格解析为合法 YAML（不写入任何状态，仅做校验）。 */
    private boolean isConfigParseable(File file) {
        try {
            new YamlConfiguration().load(file);
            return true;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().warning("config.yml 读取/解析错误：" + e.getMessage());
            return false;
        }
    }
}
