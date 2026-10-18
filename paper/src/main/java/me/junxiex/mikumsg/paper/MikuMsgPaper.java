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
import java.util.logging.Level;

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
        // 与 Velocity 端同口径的兜底：任何一步失败都要留下明确的 error 日志，
        // 而不是让 Paper 只抛一条栈到控制台、插件处于"半初始化"状态。
        try {
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
        } catch (Throwable t) {
            // 链接失败是 Error 而非 Exception（依赖类缺失），故用 Throwable 兜底。
            // 注意 getLogger() 返回的是 java.util.logging.Logger，没有 SLF4J 的 error(...)，
            // 记录异常必须走 log(Level, String, Throwable)。
            getLogger().log(Level.SEVERE, "MikuMsg (Paper) 初始化失败，插件可能无法正常工作", t);
        }
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
            // 重载失败（配置语法错误）时不保留冷却：否则管理员改好配置后立刻重试，
            // 会被误判为"冷却期内"而白等一个窗口
            if (!reloadPluginConfig()) {
                synchronized (reloadGate) {
                    lastReloadNanos = System.nanoTime() - CHANNEL_RELOAD_COOLDOWN_NANOS;
                }
            }
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
        if (!reloadPluginConfig()) {
            // 与立即重载路径保持一致：失败不保留冷却，便于管理员改好配置后立刻重试
            synchronized (reloadGate) {
                lastReloadNanos = System.nanoTime() - CHANNEL_RELOAD_COOLDOWN_NANOS;
            }
        }
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

    /**
     * 当前配置快照。
     *
     * <p>惰性兜底：若 {@link #onEnable()} 在构造配置之前就失败（极端情形），这里返回一份
     * 按默认值构建的快照，而不是 null——否则每次玩家进出都会在事件回调里抛 NPE 刷屏。</p>
     */
    public Config cfg() {
        Config snapshot = config;
        if (snapshot != null) {
            return snapshot;
        }
        return new Config(new YamlConfiguration());
    }

    /**
     * 重载配置。synchronized 防止多个区域线程同时收到代理 reload 通知时被并发调用。
     *
     * <p><b>为什么先严格校验</b>：Bukkit 的 {@code reloadConfig()} /
     * {@code YamlConfiguration.loadConfiguration} 在 YAML 语法错误时都会把异常<b>吞掉</b>
     * （回落默认值或返回空配置，仅打一条 SEVERE），管理员会看到"已重载"却拿到与磁盘不一致
     * 的内容。因此这里先用 {@code new YamlConfiguration()+load()} 严格解析一次，失败就
     * 保留当前配置并返回 false，由调用方如实回执。</p>
     *
     * <p><b>为什么校验后仍调用 reloadConfig()</b>：它负责把新内容写回 Bukkit 托管的配置对象，
     * 保证 {@code getConfig()} 与 {@code cfg()} 一致（否则会出现两套数据源）。
     * 校验已确认文件可解析，因此这次二次读盘不会失败，开销也可接受。</p>
     *
     * @return true = 已按磁盘内容生效；false = 文件解析失败，已沿用当前配置
     */
    public synchronized boolean reloadPluginConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.isFile()) {
            // 文件缺失：重新生成默认配置后按默认值生效
            saveDefaultConfig();
            reloadConfig();
            this.config = new Config(this);
            return true;
        }
        try {
            new YamlConfiguration().load(file);
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            // 语法/IO 错误都不覆盖当前配置：磁盘内容未生效，沿用上一次的解析结果
            getLogger().warning("config.yml 解析失败，本次重载未生效，沿用当前配置：" + ex.getMessage());
            return false;
        }
        // 校验通过：交给 Bukkit 完成「读盘 + 应用 jar 内默认值 + 更新 getConfig()」
        reloadConfig();
        this.config = new Config(this);
        return true;
    }
}
